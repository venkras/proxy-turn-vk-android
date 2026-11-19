package com.wdtt.client.csqtt

/**
 * Разбор stdout/stderr движка CSQTT.
 *
 * Особенности контракта:
 *  - события идут как `__CSQTT_EVENT__|<TYPE>|<json>`;
 *  - `CONFIG` несёт `{"config":"TUNCONF:..."}`;
 *  - `CAPTCHA_SOLVE|<mode>|<redirect_uri>|<session_token>` — не событие, а
 *    отдельная строка (`captcha.rs`);
 *  - обычные строки лога могут иметь префикс времени (`[12:34:56]`,
 *    `2026/11/18 12:34:56`), его нужно отбросить.
 *
 * Парсер обязан быть толерантным: битый JSON, обрезанная строка, неизвестный
 * тип события и мусор дают `null`, а не исключение.
 */
object CsqttEventParser {
    /** Минимальный лимит серверных воркеров, который принимает UI (`WORKERS`). */
    const val MIN_SERVER_WORKERS = 3

    private const val MAX_EVENT_PAYLOAD_BYTES = 64 * 1024

    /**
     * Допустимый префикс перед `__CSQTT_EVENT__|`: скобки, цифры, дата/время и
     * разделители. Всё остальное считаем обычным логом.
     */
    private val TIMESTAMP_PREFIX = Regex("""^[\[\]()\d:.,/\-+ TtZz]{1,48}$""")

    /** Разбирает строку события; `null` — это не событие CSQTT. */
    fun parse(line: String): CsqttEvent? {
        val eventLine = extractEventLine(line) ?: return null
        val withoutPrefix = eventLine.substring(CsqttDefaults.EVENT_PREFIX.length)
        val typeEnd = withoutPrefix.indexOf('|')
        if (typeEnd <= 0) return null
        val type = withoutPrefix.substring(0, typeEnd)
        val json = withoutPrefix.substring(typeEnd + 1)
        if (json.length > MAX_EVENT_PAYLOAD_BYTES) return null
        val payload = CsqttJson.parseObject(json) ?: return null
        return when (type) {
            CsqttEventType.STARTED -> CsqttEvent.Started(payload.text("message"))
            CsqttEventType.STOPPED -> CsqttEvent.Stopped(payload.text("message"))
            CsqttEventType.PROCESS -> payload.int("pid")
                ?.takeIf { it > 0 }
                ?.let { CsqttEvent.Process(it) }
            CsqttEventType.READY -> CsqttEvent.Ready(
                worker = workerIndex(payload.int("worker") ?: 0),
                message = payload.text("message"),
            )
            CsqttEventType.DTLS -> CsqttEvent.Dtls(
                worker = workerIndex(payload.int("worker") ?: 0),
                message = payload.text("message"),
            )
            CsqttEventType.ACTIVE_ZERO -> CsqttEvent.ActiveZero(payload.text("message"))
            CsqttEventType.CALL_UNAVAILABLE -> {
                val hash = payload.text("hash").trim()
                if (hash.isEmpty()) {
                    null
                } else {
                    CsqttEvent.CallUnavailable(
                        hash = hash,
                        code = (payload.int("code") ?: CsqttDefaults.CALL_NOT_FOUND_CODE)
                            .coerceAtLeast(0),
                    )
                }
            }
            CsqttEventType.NETWORK_SUSPECT -> CsqttEvent.NetworkSuspect(payload.text("message"))
            CsqttEventType.SERVER_RESTART -> {
                val source = payload.text("source")
                if (source == "panel") CsqttEvent.ServerRestart(source) else null
            }
            CsqttEventType.CONFIG -> payload.text("config")
                .takeIf { it.isNotBlank() }
                ?.let { CsqttEvent.Config(it) }
            CsqttEventType.ROUTING -> payload.text("routing")
                .takeIf { it.isNotBlank() }
                ?.let { CsqttEvent.Routing(it) }
            CsqttEventType.TUN_LOST -> CsqttEvent.TunLost
            CsqttEventType.WORKERS -> payload.int("workers")
                ?.takeIf { it == 0 || it in MIN_SERVER_WORKERS..CsqttDefaults.MAX_WORKERS }
                ?.let { CsqttEvent.Workers(it) }
            CsqttEventType.STATS -> CsqttEvent.Stats(
                active = (payload.int("active") ?: 0).coerceAtLeast(0),
                bytesUp = (payload.long("bytes_up") ?: 0L).coerceAtLeast(0L),
                bytesDown = (payload.long("bytes_down") ?: 0L).coerceAtLeast(0L),
            )
            CsqttEventType.ERROR -> CsqttEvent.Error(
                code = payload.text("code"),
                message = payload.text("message"),
                fatal = payload.boolean("fatal"),
            )
            CsqttEventType.CAPTCHA_REQUEST -> CsqttEvent.CaptchaRequest(
                mode = payload.text("mode", CsqttDefaults.CAPTCHA_MODE_AUTO),
                redirectUri = payload.text("redirect_uri"),
                sessionToken = payload.text("session_token"),
            )
            CsqttEventType.CAPTCHA_DONE -> CsqttEvent.CaptchaDone(
                success = payload.boolean("success"),
                error = payload.text("error").takeIf { it.isNotBlank() },
            )
            CsqttEventType.PROGRESS -> payload.text("kind")
                .takeIf { it == "credentials" }
                ?.let { CsqttEvent.Progress(it) }
            else -> null
        }
    }

    /**
     * Возвращает строку начиная с `__CSQTT_EVENT__|`, отбросив допустимый
     * префикс времени. `null`, если события в строке нет.
     */
    internal fun extractEventLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith(CsqttDefaults.EVENT_PREFIX)) return trimmed
        val index = trimmed.indexOf(CsqttDefaults.EVENT_PREFIX)
        if (index <= 0) return null
        val prefix = trimmed.substring(0, index)
        return if (TIMESTAMP_PREFIX.matches(prefix)) trimmed.substring(index) else null
    }

    private fun workerIndex(value: Int): Int = value.coerceIn(0, CsqttDefaults.MAX_WORKERS)

    private fun Map<String, Any?>.text(key: String, default: String = ""): String =
        (this[key] as? String) ?: default

    private fun Map<String, Any?>.int(key: String): Int? =
        (this[key] as? Number)?.toLong()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: false
}
