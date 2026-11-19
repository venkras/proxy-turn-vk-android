package com.wdtt.client.csqtt

import java.util.Locale

/**
 * Одна строка журнала CSQTT, готовая к показу в UI и logcat.
 *
 * [verboseOnly] = true означает «техническая строка»: её видно только при
 * включённых подробных логах (`detailedLogs`). Ошибки и ключевые этапы
 * (запуск, готовность, TUN, капча) показываются всегда.
 */
data class CsqttLogEntry(
    val key: String,
    val message: String,
    val priority: Int = PRIORITY_INFO,
    val isError: Boolean = false,
    val verboseOnly: Boolean = false,
) {
    companion object {
        const val PRIORITY_READY = 1
        const val PRIORITY_STATS = 3
        const val PRIORITY_CAPTCHA = 5
        const val PRIORITY_INFO = 50
        const val PRIORITY_ERROR = 99
    }
}

/**
 * Преобразование разобранного вывода движка в записи журнала.
 *
 * Класс намеренно не зависит от `android.*`: маскирование секретов и тексты
 * сообщений проверяются обычными JVM unit-тестами, как и остальной слой S2.
 */
object CsqttLogMapper {
    /** Префикс времени в обычных строках движка (`[12:34:56]`, `2026/11/18 …`). */
    private val TIMESTAMP_PREFIX = Regex(
        """^\s*(?:\[\d{2}:\d{2}:\d{2}(?:[.,]\d{1,3})?]|\d{4}[/-]\d{2}[/-]\d{2}\s+\d{2}:\d{2}:\d{2}(?:[.,]\d+)?)\s*""",
    )

    /** Секреты конфигурации, которые нельзя показывать в логах. */
    fun secrets(config: CsqttConfig): List<String> = CsqttSecrets.secretsOf(config)

    /** Отрезает префикс времени, чтобы ключи логов оставались стабильными. */
    fun stripPrefix(line: String): String = line.replaceFirst(TIMESTAMP_PREFIX, "").trim()

    /** Обычная строка stdout/stderr. */
    fun log(output: CsqttOutput.Log, secrets: Iterable<String>): CsqttLogEntry {
        val safe = CsqttSecrets.redactLine(stripPrefix(output.text), secrets)
        val isError = output.isError
        return CsqttLogEntry(
            key = "csqtt_log_${safe.take(48).hashCode()}",
            message = "[CSQTT] $safe",
            priority = if (isError) CsqttLogEntry.PRIORITY_ERROR else CsqttLogEntry.PRIORITY_STATS,
            isError = isError,
            verboseOnly = !isError,
        )
    }

    /** Отказ авторизации сервера (`DENIED:…` / `FATAL_AUTH`). */
    fun denied(output: CsqttOutput.AuthDenied, secrets: Iterable<String>): CsqttLogEntry {
        val safe = CsqttSecrets.redactLine(stripPrefix(output.line), secrets)
        return CsqttLogEntry(
            key = "csqtt_denied_${output.denial.name.lowercase(Locale.US)}",
            message = "[CSQTT] ${output.denial.userMessage}" +
                if (safe.isNotEmpty() && safe.length <= 96) " ($safe)" else "",
            priority = CsqttLogEntry.PRIORITY_ERROR,
            isError = true,
        )
    }

    /**
     * Маппинг события движка. `null` — событие уже обработано отдельно
     * (TUNCONF, капча) и отдельной строки журнала не требует.
     */
    fun event(event: CsqttEvent, secrets: Iterable<String>): CsqttLogEntry? = when (event) {
        is CsqttEvent.Started -> CsqttLogEntry(
            key = "csqtt_started",
            message = "[CSQTT] Движок запущен" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_READY,
        )
        is CsqttEvent.Stopped -> CsqttLogEntry(
            key = "csqtt_stopped",
            message = "[CSQTT] Движок штатно завершил работу" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_INFO,
        )
        is CsqttEvent.Process -> CsqttLogEntry(
            key = "csqtt_pid",
            message = "[CSQTT] Процесс движка: pid=${event.pid}",
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
        is CsqttEvent.Ready -> CsqttLogEntry(
            key = "csqtt_ready_${event.worker}",
            message = "[CSQTT] Поток ${event.worker} готов к передаче данных" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_READY,
        )
        is CsqttEvent.Dtls -> CsqttLogEntry(
            key = "csqtt_dtls_${event.worker}",
            message = "[CSQTT] DTLS (поток ${event.worker})" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_READY,
            verboseOnly = event.message.isBlank(),
        )
        is CsqttEvent.ActiveZero -> CsqttLogEntry(
            key = "csqtt_active_zero",
            message = "[CSQTT] Нет активных потоков" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_ERROR,
            isError = true,
        )
        is CsqttEvent.CallUnavailable -> CsqttLogEntry(
            key = "csqtt_call_unavailable",
            message = "[CSQTT] VK-звонок недоступен (${CsqttSecrets.redact(event.hash, secrets)}, код ${event.code})",
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
        is CsqttEvent.NetworkSuspect -> CsqttLogEntry(
            key = "csqtt_network_suspect",
            message = "[CSQTT] Похоже на проблемы сети" + suffix(event.message),
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
        is CsqttEvent.ServerRestart -> CsqttLogEntry(
            key = "csqtt_server_restart",
            message = "[CSQTT] Сервер перезапустился — нужно переподключение",
            priority = CsqttLogEntry.PRIORITY_ERROR,
            isError = true,
        )
        is CsqttEvent.Config -> CsqttLogEntry(
            key = "csqtt_config",
            message = "[CSQTT] Получена конфигурация TUN",
            priority = CsqttLogEntry.PRIORITY_READY,
        )
        is CsqttEvent.Routing -> CsqttLogEntry(
            key = "csqtt_routing",
            message = "[CSQTT] Сервер прислал правила маршрутизации (${event.routing.length} байт)",
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
        is CsqttEvent.TunLost -> CsqttLogEntry(
            key = "csqtt_tun_lost",
            message = "[CSQTT] TUN-интерфейс потерян",
            priority = CsqttLogEntry.PRIORITY_ERROR,
            isError = true,
        )
        is CsqttEvent.Workers -> CsqttLogEntry(
            key = "csqtt_workers",
            message = "[CSQTT] Сервер сообщил лимит потоков: ${event.workers}",
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
        is CsqttEvent.Stats -> CsqttLogEntry(
            key = "csqtt_stats",
            message = statsText(event),
            priority = CsqttLogEntry.PRIORITY_STATS,
        )
        is CsqttEvent.Error -> CsqttLogEntry(
            key = "csqtt_error_${event.code.take(24).ifEmpty { "unknown" }}",
            message = "[CSQTT] ${errorMessage(event, secrets)}",
            priority = CsqttLogEntry.PRIORITY_ERROR,
            isError = true,
        )
        is CsqttEvent.CaptchaRequest -> CsqttLogEntry(
            key = "csqtt_captcha_request",
            message = "[CSQTT] Требуется капча (${event.mode})",
            priority = CsqttLogEntry.PRIORITY_CAPTCHA,
        )
        is CsqttEvent.CaptchaDone -> CsqttLogEntry(
            key = "csqtt_captcha_done",
            message = if (event.success) {
                "[CSQTT] Капча решена"
            } else {
                "[CSQTT] Капча не решена" + (event.error?.let { " — $it" } ?: "")
            },
            priority = CsqttLogEntry.PRIORITY_CAPTCHA,
            isError = !event.success,
        )
        is CsqttEvent.Progress -> CsqttLogEntry(
            key = "csqtt_progress_${event.kind}",
            message = "[CSQTT] Получение VK-креденшелов…",
            priority = CsqttLogEntry.PRIORITY_INFO,
            verboseOnly = true,
        )
    }

    /** Текст статистики для строки состояния и журнала. */
    fun statsText(event: CsqttEvent.Stats): String = String.format(
        Locale.US,
        "[СТАТИСТИКА] Активных: %d · ↑ %.2f МБ ↓ %.2f МБ",
        event.active,
        event.bytesUp / BYTES_PER_MB,
        event.bytesDown / BYTES_PER_MB,
    )

    /** Человекочитаемое сообщение фатальной ошибки движка. */
    fun errorMessage(event: CsqttEvent.Error, secrets: Iterable<String> = emptyList()): String {
        val denial = CsqttAuthDenial.fromReason(event.code)
        if (denial != CsqttAuthDenial.UNKNOWN) return denial.userMessage
        val safeMessage = CsqttSecrets.redactLine(event.message, secrets).trim()
        if (safeMessage.isNotEmpty()) return safeMessage
        return if (event.code.isBlank()) {
            "Ошибка движка CSQTT"
        } else {
            "Ошибка движка CSQTT (${event.code})"
        }
    }

    private fun suffix(message: String): String {
        val trimmed = message.trim()
        return if (trimmed.isEmpty()) "" else ": $trimmed"
    }

    private const val BYTES_PER_MB = 1024.0 * 1024.0
}
