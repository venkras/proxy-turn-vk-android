package com.wdtt.client.csqtt

/** Готовая команда запуска движка: argv и переменные окружения. */
data class CsqttCommand(
    val argv: List<String>,
    val env: Map<String, String>,
) {
    /** argv с маскированными секретами — безопасно писать в лог. */
    fun redactedArgv(): List<String> = CsqttSecrets.maskArgv(argv)

    /** Однострочное безопасное описание команды для логов. */
    fun describe(): String = CsqttSecrets.describeCommand(this)

    /** Путь к бинарнику (первый элемент argv). */
    val binaryPath: String get() = argv.firstOrNull().orEmpty()
}

/**
 * Сборка argv/env для `libcsqtt.so` строго по контракту `rust-client/main.rs`.
 *
 * Движок принимает и одно-, и двух-дефисные длинные флаги: `normalize_cli_argument`
 * переписывает `-peer` в `--peer`. Здесь используется тот же набор флагов и
 * порядок, что и в референсном Android-клиенте CSQTT, поэтому контракт
 * проверяется побайтово в unit-тестах.
 *
 * Пароль никогда не попадает в argv — только в env `CSQTT_PASSWORD`.
 */
object CsqttCommandLine {

    /**
     * Собирает команду запуска. Конфигурация нормализуется; вызывающая сторона
     * должна заранее проверить [problems] и не запускать движок при фатальных
     * ошибках.
     *
     * @param binaryPath абсолютный путь к `libcsqtt.so` в `nativeLibraryDir`.
     * @param sockName голое имя abstract UDS (без `@`), которое слушает движок.
     * @param nativeLibraryDir каталог с нативными библиотеками для `LD_LIBRARY_PATH`.
     */
    fun build(
        config: CsqttConfig,
        binaryPath: String,
        sockName: String,
        nativeLibraryDir: String = "",
    ): CsqttCommand {
        val normalized = config.normalized()
        return CsqttCommand(
            argv = buildArgv(normalized, binaryPath, sockName),
            env = buildEnv(normalized, nativeLibraryDir),
        )
    }

    /** Проверка конфигурации вместе с параметрами запуска. */
    fun problems(
        config: CsqttConfig,
        binaryPath: String,
        sockName: String,
    ): List<CsqttConfigProblem> {
        val result = config.problems().toMutableList()
        if (binaryPath.isBlank()) result += CsqttConfigProblem.BINARY_MISSING
        if (sockName.isBlank()) result += CsqttConfigProblem.SOCKET_MISSING
        return result
    }

    /** Только argv (без env). Порядок флагов фиксирован и покрыт тестами. */
    fun buildArgv(config: CsqttConfig, binaryPath: String, sockName: String): List<String> {
        val normalized = config.normalized()
        val argv = mutableListOf<String>()
        argv += binaryPath
        argv += listOf("-peer", normalized.peer)
        argv += listOf("-n", normalized.workers.toString())
        argv += listOf(
            "-listen",
            "${CsqttDefaults.DEFAULT_LOCAL_HOST}:${normalized.localPort}",
        )
        argv += listOf("-tun-uds", sockName)

        if (normalized.vkHashes.isNotEmpty()) {
            argv += listOf("-vk", normalized.hashesCsv)
        }
        argv += listOf("-vk-hash-mode", normalized.vkHashMode)
        if (normalized.fingerprint.isNotEmpty()) {
            argv += listOf("-fingerprint", normalized.fingerprint)
        }
        if (normalized.clientIdsCsv.isNotEmpty()) {
            argv += listOf("-client-ids", normalized.clientIdsCsv)
        }
        argv += listOf("-obfs", normalized.obfs)
        argv += listOf("-turn-transport", normalized.turnTransport)
        argv += listOf("-srtp-mode", normalized.srtpMode)
        if (normalized.customTurnIps.isNotEmpty()) {
            argv += listOf("-custom-turn-ips", normalized.customTurnIps)
        }
        argv += listOf("-vk-auth-mode", normalized.effectiveVkAuthMode)
        if (normalized.generation > 0L) {
            argv += listOf("-gen", normalized.generation.toString())
        }
        if (normalized.salt.isNotEmpty()) {
            argv += listOf("-salt", normalized.salt)
        }
        argv += listOf("-captcha-mode", normalized.captchaMode)

        if (isDomainRoutingActive(normalized)) {
            argv += "--domain-routing"
            if (normalized.domainWhitelist) {
                argv += "--domain-whitelist"
            }
            if (normalized.excludeRuZones && !normalized.domainWhitelist) {
                argv += "--exclude-ru-zones"
            }
            if (normalized.templateDomains && !normalized.domainWhitelist) {
                argv += "--template-domains"
            }
            for (file in normalized.domainsFiles) {
                argv += listOf("--domains-file", file)
            }
            if (normalized.directDomains.isNotEmpty()) {
                argv += listOf("--direct-domains", normalized.directDomains)
            }
        }

        if (normalized.ipRouting || normalized.directIps.isNotEmpty()) {
            argv += "--ip-routing"
            if (normalized.ipWhitelist) {
                argv += "--ip-whitelist"
            }
            if (normalized.directIps.isNotEmpty()) {
                argv += listOf("--direct-ips", normalized.directIps)
            }
        }

        if (normalized.exactWorkers) {
            argv += "--exact-workers"
        }
        if (normalized.runtimeThreads > 0) {
            argv += listOf("-runtime-threads", normalized.runtimeThreads.toString())
        }
        return argv
    }

    /**
     * Переменные окружения движка. `LD_LIBRARY_PATH` добавляется только когда
     * известен каталог нативных библиотек (в unit-тестах его нет).
     */
    fun buildEnv(config: CsqttConfig, nativeLibraryDir: String = ""): Map<String, String> {
        val normalized = config.normalized()
        val env = LinkedHashMap<String, String>()
        if (nativeLibraryDir.isNotBlank()) {
            env[CsqttDefaults.ENV_LD_LIBRARY_PATH] = nativeLibraryDir
        }
        env[CsqttDefaults.ENV_EVENTS] = CsqttDefaults.EVENTS_ENABLED
        env[CsqttDefaults.ENV_RAYON_THREADS] = CsqttDefaults.RAYON_NUM_THREADS
        if (normalized.password.isNotEmpty()) {
            env[CsqttDefaults.ENV_PASSWORD] = normalized.password
        }
        return env
    }

    private fun isDomainRoutingActive(config: CsqttConfig): Boolean =
        config.domainRouting ||
            config.excludeRuZones ||
            config.templateDomains ||
            config.directDomains.isNotEmpty() ||
            config.domainsFiles.isNotEmpty()
}

/**
 * Строки управления, которые движок читает из stdin (`start_control_input`
 * в `main.rs`). Формат команд фиксирован контрактом.
 */
object CsqttControl {
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val STOP = "STOP"
    const val SCREEN_OFF = "SCREEN_OFF"
    const val SCREEN_ON = "SCREEN_ON"
    const val FINISH_VK_CALLS = "FINISH_VK_CALLS"

    const val PREFIX_CAPTCHA_RESULT = "CAPTCHA_RESULT|"
    const val PREFIX_VK_JS_BOOTSTRAP = "VK_JS_BOOTSTRAP:"
    const val PREFIX_UPDATE_DOMAINS = "UPDATE_DOMAINS|"

    /** Результат решения капчи; пустой токен отменяет ожидание движка. */
    fun captchaResult(token: String): String = PREFIX_CAPTCHA_RESULT + token.trim()

    /** Bootstrap Auto JS: JSON, закодированный в Base64 (стандартный алфавит). */
    fun vkJsBootstrap(base64Json: String): String = PREFIX_VK_JS_BOOTSTRAP + base64Json.trim()

    /** Горячее обновление доменных правил JSON-строкой. */
    fun updateDomains(payload: String): String = PREFIX_UPDATE_DOMAINS + payload.trim()

    /** Строка для лога без секретов (токен капчи и bootstrap скрываются). */
    fun loggable(line: String): String = CsqttSecrets.maskControlLine(line)
}
