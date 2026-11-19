package com.wdtt.client.csqtt

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Единая точка контроля процесса `libcsqtt.so`.
 *
 * Движок CSQTT — отдельный OS-процесс, поэтому Android-сторона обязана
 * гарантированно его убивать при `stop()`, отзыве VPN системой, `onDestroy`
 * сервиса и смене режима подключения. Все пути остановки проходят через
 * [kill], чтобы не оставалось «висящих» `libcsqtt` после переключения
 * WG → CSQTT → Raw.
 *
 * Guard хранит именно [Process], а не голый PID: `Process.destroyForcibly()`
 * не может задеть чужой процесс, даже если Android успел переиспользовать
 * числовой PID. PID из события `PROCESS` используется только для диагностики.
 */
object CsqttProcessGuard {
    private const val TAG = "CsqttProcessGuard"

    private val lock = Any()
    private var tracked: Process? = null
    private var reportedPid: Int = -1

    /** Запоминает только что запущенный процесс движка. */
    fun attach(process: Process) {
        synchronized(lock) {
            tracked = process
            reportedPid = -1
        }
    }

    /** PID, который движок сам сообщил событием `PROCESS` (для логов). */
    fun updateReportedPid(pid: Int) {
        if (pid <= 0) return
        synchronized(lock) { reportedPid = pid }
    }

    fun current(): Process? = synchronized(lock) { tracked }

    fun reportedPid(): Int = synchronized(lock) { reportedPid }

    fun isAlive(): Boolean = synchronized(lock) { tracked?.isAlive == true }

    /** Забывает процесс, не убивая его (штатное завершение reader-цикла). */
    fun detach(process: Process?) {
        synchronized(lock) {
            if (process == null || tracked === process) {
                tracked = null
                reportedPid = -1
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            tracked = null
            reportedPid = -1
        }
    }

    /**
     * Останавливает движок: `destroy()` → ожидание → `destroyForcibly()`.
     * Возвращает true, если был живой процесс, который мы попытались погасить.
     *
     * @param reason короткая причина без секретов (идёт только в logcat).
     */
    fun kill(reason: String): Boolean {
        val process = synchronized(lock) { tracked } ?: return false
        val pid = synchronized(lock) { reportedPid }
        Log.w(TAG, "kill: reason=$reason reportedPid=$reportedPid")

        runCatching { process.destroy() }
        val exited = runCatching {
            process.waitFor(CsqttDefaults.DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!exited) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(CsqttDefaults.FORCE_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS) }
        }

        synchronized(lock) {
            if (tracked === process) {
                tracked = null
                reportedPid = -1
            }
        }
        return true
    }

    /** Безопасная строка для лога: PID и признак жизни. */
    fun describe(): String {
        val process = synchronized(lock) { tracked }
        val pid = synchronized(lock) { reportedPid }
        val alive = process?.isAlive == true
        return "pid=${pid.coerceAtLeast(-1)}, attached=${process != null}, alive=$alive"
    }
}
