package com.wdtt.client.csqtt

import android.content.Context
import android.util.Log
import com.wdtt.client.RawTunEngine
import com.wdtt.client.TunFdBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

/** Ошибка запуска/остановки движка CSQTT (текст уже пригоден для UI). */
class CsqttEngineException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Колбэки для [com.wdtt.client.TunnelManager]: движок не знает про UI и не
 * трогает StateFlow'ы менеджера напрямую.
 *
 * Все методы вызываются из фоновых потоков; реализации должны быть
 * потокобезопасными (TunnelManager использует MutableStateFlow.update).
 */
interface CsqttEngineListener {
    fun onLog(entry: CsqttLogEntry)

    /** Процесс запущен/остановлен (не путать с готовностью TUN). */
    fun onStateChanged(running: Boolean) {}

    /** Количество активных воркеров из события `STATS`. */
    fun onStats(stats: String, active: Int, bytesUp: Long, bytesDown: Long) {}

    /** TUN поднят и fd передан движку с подтверждённым ACK. */
    fun onTunReady(conf: CsqttTunConf) {}

    /** Движок просит показать WebView-капчу. */
    fun onCaptchaRequested(request: CsqttOutput.CaptchaRequest) {}

    /** Правила маршрутизации от сервера (в v1 только сохраняются в лог). */
    fun onRouting(routing: String) {}

    /** Неисправимая ошибка сессии: движок уже останавливается. */
    fun onFatalError(message: String) {}

    /** Сервер отклонил авторизацию (неверный пароль, срок и т.п.). */
    fun onAuthDenied(denial: CsqttAuthDenial) {}

    /** Движок сообщил, что TUN-интерфейс потерян (exit code 3). */
    fun onTunLost() {}

    /** Нужно переподключить сессию (TUN потерян, панель перезапустилась). */
    fun onReconnectRequested(reason: String) {}

    /** Процесс завершился сам; [exitCode] — код выхода движка. */
    fun onProcessExited(exitCode: Int) {}
}

/**
 * Управление процессом `libcsqtt.so` и передачей TUN fd.
 *
 * Контракт с Rust-движком (`csqtt-main/rust-client`):
 *  1. движок сам биндит abstract UDS `-tun-uds <bare name>` и ждёт fd;
 *  2. Android подключается, отправляет один байт + fd через `SCM_RIGHTS`;
 *  3. движок отвечает одним байтом ACK (`1`) — [TunFdBridge.sendAndAwaitAck];
 *  4. параметры TUN приходят событием `CONFIG` со строкой `TUNCONF:…`;
 *  5. управление — строками stdin (`PAUSE`, `RESUME`, `STOP`, …);
 *  6. капча — `CAPTCHA_SOLVE|…` в stdout, ответ `CAPTCHA_RESULT|<token>`.
 *
 * Пароль передаётся только через env `CSQTT_PASSWORD`; argv и все строки
 * вывода проходят через [CsqttSecrets], поэтому в logcat и журнале не
 * появляются ни пароль, ни хеши VK, ни токены капчи.
 */
object CsqttEngine {
    private const val TAG = "CsqttEngine"

    /** Имя сессии VpnService для CSQTT (видно в системных настройках VPN). */
    const val VPN_SESSION_NAME = "qWDTT-csqtt"

    /** Сколько ждём ACK от Rust после отправки TUN fd. */
    private const val TUN_ACK_TIMEOUT_MS = 5_000L

    /** Как часто проверяем живость процесса. */
    private const val WATCHDOG_INTERVAL_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stdinLock = Any()

    private val _running = MutableStateFlow(false)
    /** Процесс движка запущен и ещё не завершился. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _tunReady = MutableStateFlow(false)
    /** TUN поднят, fd передан движку и ACK получен. */
    val tunReady: StateFlow<Boolean> = _tunReady.asStateFlow()

    private val _activeWorkers = MutableStateFlow(0)
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val _stats = MutableStateFlow("")
    val stats: StateFlow<String> = _stats.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var listener: CsqttEngineListener? = null
    @Volatile private var screenOn = true

    private var config: CsqttConfig? = null
    private var contextRef: Context? = null
    private var socketName = ""
    private var secrets: List<String> = emptyList()
    private var sessionToken = 0L
    private var stopping = false
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var captchaJob: Job? = null
    private var lastTunConf: CsqttTunConf? = null
    private var lastStatsLogAtMs = 0L

    // ---- Публичный контракт --------------------------------------------------

    /** Есть ли `libcsqtt.so` в nativeLibraryDir текущей сборки. */
    fun isBundled(context: Context): Boolean = binaryFile(context).isFile

    /** Понятное сообщение, если движок не попал в APK (сборка без -PcsqttEngine=true). */
    fun missingBinaryMessage(context: Context): String {
        val file = binaryFile(context)
        return "Движок CSQTT не входит в эту сборку: файл ${file.name} не найден " +
            "в nativeLibraryDir. Пересоберите APK с флагом -PcsqttEngine=true."
    }

    /**
     * Запускает движок и возвращается сразу после старта процесса: TUN
     * поднимается асинхронно, когда движок пришлёт `TUNCONF`.
     */
    suspend fun start(
        context: Context,
        config: CsqttConfig,
        listener: CsqttEngineListener? = null,
    ): Result<Unit> = mutex.withLock {
        val app = context.applicationContext
        // Повторный старт не должен оставлять два движка.
        stopLocked(force = true)

        val normalized = config.normalized()
        val fatalProblems = normalized.problems().filter { it.isFatal }
        if (fatalProblems.isNotEmpty()) {
            return Result.failure(
                CsqttEngineException(fatalProblems.joinToString("; ") { it.message }),
            )
        }

        val binary = binaryFile(app)
        if (!binary.isFile || binary.length() <= 0L) {
            return Result.failure(CsqttEngineException(missingBinaryMessage(app)))
        }
        if (!binary.canExecute()) {
            // extractNativeLibs=true обычно уже выставляет x-бит; на всякий
            // случай пробуем выставить его сами, иначе exec даст EACCES.
            runCatching { binary.setExecutable(true, false) }
            if (!binary.canExecute()) {
                return Result.failure(
                    CsqttEngineException(
                        "Движок CSQTT найден, но недоступен для запуска на этом ABI " +
                            "(${binary.name}). Пересоберите APK для текущей архитектуры.",
                    ),
                )
            }
        }

        this.contextRef = app
        this.config = normalized
        this.listener = listener
        this.secrets = CsqttLogMapper.secrets(normalized)
        this.stopping = false
        this.screenOn = true
        this.lastTunConf = null
        this.lastStatsLogAtMs = 0L
        _tunReady.value = false
        _activeWorkers.value = 0
        _stats.value = "Ожидание данных..."
        socketName = CsqttDefaults.newSocketName(
            android.os.Process.myPid(),
            System.nanoTime(),
        )

        val token = ++sessionToken
        val command = CsqttCommandLine.build(
            config = normalized,
            binaryPath = binary.absolutePath,
            sockName = socketName,
            nativeLibraryDir = app.applicationInfo.nativeLibraryDir,
        )

        // В лог — только маскированная команда; env с паролем маскируется
        // внутри describe().
        emit(
            CsqttLogEntry(
                key = "csqtt_command",
                message = "[CSQTT] Запуск: ${command.describe()}",
                priority = CsqttLogEntry.PRIORITY_INFO,
                verboseOnly = true,
            ),
        )

        val started = try {
            val builder = ProcessBuilder(command.argv)
                .directory(app.filesDir)
                .redirectErrorStream(true)
            builder.environment().putAll(command.env)
            builder.start()
        } catch (e: Exception) {
            val message = "Не удалось запустить движок CSQTT: " +
                (e.message ?: e::class.java.simpleName)
            emit(
                CsqttLogEntry(
                    key = "csqtt_start_failed",
                    message = "[CSQTT] $message",
                    priority = CsqttLogEntry.PRIORITY_ERROR,
                    isError = true,
                ),
            )
            return Result.failure(CsqttEngineException(message, e))
        }

        process = started
        CsqttProcessGuard.attach(started)
        _running.value = true
        listener?.onStateChanged(true)
        emit(
            CsqttLogEntry(
                key = "csqtt_process_started",
                message = "[CSQTT] Процесс запущен",
                priority = CsqttLogEntry.PRIORITY_READY,
            ),
        )
        startReader(app, token, started)
        startWatchdog(app, token, started)
        Result.success(Unit)
    }

    /**
     * Останавливает движок: `STOP` → ожидание → `destroy` → `destroyForcibly`.
     * TUN закрывается, чтобы следующий режим/перезапуск не получил
     * «address already in use».
     */
    suspend fun stop(force: Boolean = false) = mutex.withLock {
        stopLocked(force)
    }

    /** Одна строка управления в stdin движка. Возвращает false, если процесс не жив. */
    fun send(command: String): Boolean {
        val process = process ?: return false
        if (!process.isAlive) return false
        val line = command.trim()
        if (line.isEmpty()) return false
        val safeLine = CsqttSecrets.maskControlLine(line)
        emit(
            CsqttLogEntry(
                key = "csqtt_stdin",
                message = "[CSQTT] → $safeLine",
                priority = CsqttLogEntry.PRIORITY_INFO,
                verboseOnly = true,
            ),
        )
        return synchronized(stdinLock) {
            runCatching {
                process.outputStream.write("$line\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }.isSuccess
        }
    }

    fun onScreenChanged(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        send(if (on) CsqttControl.SCREEN_ON else CsqttControl.SCREEN_OFF)
    }

    fun pause() {
        send(CsqttControl.PAUSE)
    }

    fun resume() {
        send(CsqttControl.RESUME)
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /** Секреты текущей сессии (для дополнительного маскирования в UI). */
    fun currentSecrets(): List<String> = secrets

    // ---- Process lifecycle ---------------------------------------------------

    private fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, CsqttDefaults.BINARY_NAME)

    private suspend fun stopLocked(force: Boolean) {
        stopping = true
        sessionToken++
        captchaJob?.cancel()
        captchaJob = null
        readerJob?.cancel()
        readerJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        val processSnapshot = process
        process = null
        if (processSnapshot != null) {
            // Сначала вежливо: движок сам завершит воркеры и закроет сокет.
            runCatching {
                processSnapshot.outputStream.write(
                    "${CsqttControl.STOP}\n".toByteArray(Charsets.UTF_8),
                )
                processSnapshot.outputStream.flush()
            }
            val grace = if (force) 200L else CsqttDefaults.STOP_GRACE_MS
            runCatching { processSnapshot.waitFor(grace, TimeUnit.MILLISECONDS) }
            CsqttProcessGuard.kill(if (force) "принудительная остановка" else "штатная остановка")
            runCatching { processSnapshot.inputStream.close() }
            runCatching { processSnapshot.outputStream.close() }
            runCatching { processSnapshot.errorStream?.close() }
            CsqttProcessGuard.detach(processSnapshot)
        }

        val hadTun = lastTunConf != null || _tunReady.value || RawTunEngine.running
        lastTunConf = null
        _tunReady.value = false
        _running.value = false
        _activeWorkers.value = 0
        if (hadTun) {
            val ctx = contextRef
            if (ctx != null) {
                runCatching { RawTunEngine.stop(ctx) }
            }
        }
        CsqttCaptchaBridge.cancel()
        listener?.onStateChanged(false)
        Log.i(TAG, "stopped (force=$force, ${CsqttProcessGuard.describe()})")
    }

    private fun startReader(app: Context, token: Long, observed: Process) {
        readerJob = scope.launch {
            val reader = runCatching { observed.inputStream.bufferedReader() }.getOrNull()
                ?: return@launch
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (token != sessionToken) return@launch
                    handleLine(app, token, line)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reader stopped: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { reader.close() }
            }
        }
    }

    private fun startWatchdog(app: Context, token: Long, observed: Process) {
        watchdogJob = scope.launch {
            while (isActive && token == sessionToken) {
                delay(WATCHDOG_INTERVAL_MS)
                if (token != sessionToken) return@launch
                if (observed.isAlive) continue

                val exitCode = runCatching { observed.exitValue() }.getOrDefault(-1)
                val expected = stopping
                process = null
                _running.value = false
                _tunReady.value = false
                _activeWorkers.value = 0
                lastTunConf = null
                runCatching { RawTunEngine.stop(app) }
                CsqttProcessGuard.detach(observed)
                listener?.onStateChanged(false)
                if (expected) {
                    return@launch
                }
                val message = CsqttExitCodes.message(exitCode)
                if (message != null) {
                    emit(
                        CsqttLogEntry(
                            key = "csqtt_exit_$exitCode",
                            message = "[CSQTT] $message",
                            priority = CsqttLogEntry.PRIORITY_ERROR,
                            isError = true,
                        ),
                    )
                }
                listener?.onProcessExited(exitCode)
                return@launch
            }
        }
    }

    // ---- Output handling -----------------------------------------------------

    private suspend fun handleLine(app: Context, token: Long, rawLine: String) {
        if (rawLine.isBlank()) return
        val line = CsqttLogMapper.stripPrefix(rawLine)
        when (val output = CsqttOutputParser.parse(line)) {
            is CsqttOutput.TunConf -> handleTunConf(app, token, output.conf)
            is CsqttOutput.CaptchaRequest -> handleCaptcha(app, token, output)
            is CsqttOutput.Event -> handleEvent(app, token, output.event)
            is CsqttOutput.AuthDenied -> handleAuthDenied(output)
            is CsqttOutput.Log -> emit(CsqttLogMapper.log(output, secrets))
        }
    }

    private suspend fun handleTunConf(app: Context, token: Long, conf: CsqttTunConf) {
        if (token != sessionToken) return
        val previous = lastTunConf
        if (_tunReady.value && previous != null &&
            previous.clientIp == conf.clientIp &&
            previous.dnsCsv == conf.dnsCsv &&
            previous.mtu == conf.mtu
        ) {
            return
        }

        emit(
            CsqttLogEntry(
                key = "csqtt_tunconf",
                message = "[CSQTT] TUNCONF: адрес ${conf.clientIp}, DNS ${conf.dnsCsv}, MTU ${conf.mtu}",
                priority = CsqttLogEntry.PRIORITY_READY,
            ),
        )
        try {
            RawTunEngine.start(
                context = app,
                ip = conf.clientIp,
                dnsCsv = conf.dnsOrDefault.joinToString(","),
                mtu = conf.mtu,
                sockName = socketName,
                sessionName = VPN_SESSION_NAME,
                awaitAck = true,
            )
            if (token != sessionToken) {
                // Пока поднимался TUN, сессию остановили — не оставляем интерфейс.
                runCatching { RawTunEngine.stop(app) }
                return
            }
            lastTunConf = conf
            _tunReady.value = true
            emit(
                CsqttLogEntry(
                    key = "csqtt_tun_ready",
                    message = "[CSQTT] TUN поднят (${conf.clientIp}/32), fd передан движку (ACK)",
                    priority = CsqttLogEntry.PRIORITY_READY,
                ),
            )
            listener?.onTunReady(conf)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "Не удалось поднять TUN-интерфейс CSQTT: " +
                (e.message ?: e::class.java.simpleName)
            emit(
                CsqttLogEntry(
                    key = "csqtt_tun_failed",
                    message = "[CSQTT] $message",
                    priority = CsqttLogEntry.PRIORITY_ERROR,
                    isError = true,
                ),
            )
            listener?.onFatalError(message)
            scope.launch { runCatching { stop(force = true) } }
        }
    }

    private fun handleEvent(app: Context, token: Long, event: CsqttEvent) {
        if (token != sessionToken) return
        when (event) {
            is CsqttEvent.Process -> CsqttProcessGuard.updateReportedPid(event.pid)
            is CsqttEvent.Stats -> {
                _activeWorkers.value = event.active.coerceAtLeast(0)
                val text = CsqttLogMapper.statsText(event)
                _stats.value = text
                listener?.onStats(text, event.active, event.bytesUp, event.bytesDown)
                val now = System.currentTimeMillis()
                if (now - lastStatsLogAtMs >= STATS_LOG_INTERVAL_MS) {
                    lastStatsLogAtMs = now
                    emit(CsqttLogMapper.event(event, secrets))
                }
                return
            }
            is CsqttEvent.TunLost -> {
                emit(CsqttLogMapper.event(event, secrets))
                listener?.onTunLost()
                listener?.onReconnectRequested("TUN-интерфейс потерян")
                return
            }
            is CsqttEvent.ServerRestart -> {
                emit(CsqttLogMapper.event(event, secrets))
                listener?.onReconnectRequested("панель сервера перезапустилась")
                return
            }
            is CsqttEvent.Routing -> {
                listener?.onRouting(event.routing)
                emit(CsqttLogMapper.event(event, secrets))
                return
            }
            is CsqttEvent.Error -> {
                emit(CsqttLogMapper.event(event, secrets))
                if (event.fatal) {
                    val message = CsqttLogMapper.errorMessage(event, secrets)
                    listener?.onFatalError(message)
                    scope.launch { runCatching { stop(force = true) } }
                }
                return
            }
            is CsqttEvent.CaptchaDone -> {
                emit(CsqttLogMapper.event(event, secrets))
                return
            }
            is CsqttEvent.CaptchaRequest -> {
                // В v2.5.0 капча приходит строкой CAPTCHA_SOLVE|…, но событие
                // поддержано на будущее — обрабатываем тем же путём.
                handleCaptcha(
                    app,
                    token,
                    CsqttOutput.CaptchaRequest(
                        mode = event.mode,
                        redirectUri = event.redirectUri,
                        sessionToken = event.sessionToken,
                    ),
                )
                return
            }
            is CsqttEvent.ActiveZero -> {
                emit(CsqttLogMapper.event(event, secrets))
                return
            }
            else -> Unit
        }
        emit(CsqttLogMapper.event(event, secrets))
    }

    private fun handleCaptcha(
        app: Context,
        token: Long,
        request: CsqttOutput.CaptchaRequest,
    ) {
        if (token != sessionToken) return
        emit(
            CsqttLogEntry(
                key = "csqtt_captcha_request",
                message = "[CSQTT] Требуется капча (${request.mode})",
                priority = CsqttLogEntry.PRIORITY_CAPTCHA,
            ),
        )
        listener?.onCaptchaRequested(request)
        captchaJob?.cancel()
        captchaJob = scope.launch {
            val solution = try {
                CsqttCaptchaBridge.solve(
                    app,
                    request.mode,
                    request.redirectUri,
                    request.sessionToken,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "captcha failed: ${e.javaClass.simpleName}")
                ""
            }
            if (token != sessionToken) return@launch
            if (solution.isBlank()) {
                val message = "Капча не решена — подключение остановлено"
                emit(
                    CsqttLogEntry(
                        key = "csqtt_captcha_failed",
                        message = "[CSQTT] $message",
                        priority = CsqttLogEntry.PRIORITY_ERROR,
                        isError = true,
                    ),
                )
                listener?.onFatalError(message)
                runCatching { stop(force = true) }
                return@launch
            }
            emit(
                CsqttLogEntry(
                    key = "csqtt_captcha_solved",
                    message = "[CSQTT] Капча решена, отправляем результат",
                    priority = CsqttLogEntry.PRIORITY_CAPTCHA,
                ),
            )
            send(CsqttControl.captchaResult(solution))
        }
    }

    private fun handleAuthDenied(output: CsqttOutput.AuthDenied) {
        emit(CsqttLogMapper.denied(output, secrets))
        listener?.onAuthDenied(output.denial)
        scope.launch { runCatching { stop(force = true) } }
    }

    private fun emit(entry: CsqttLogEntry?) {
        if (entry == null) return
        if (entry.isError) {
            Log.w(TAG, entry.message)
        } else {
            Log.i(TAG, entry.message)
        }
        listener?.onLog(entry)
    }

    private const val STATS_LOG_INTERVAL_MS = 2_000L
}
