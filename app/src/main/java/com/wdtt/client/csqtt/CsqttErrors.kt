package com.wdtt.client.csqtt

/**
 * Отказы авторизации сервера CSQTT и их человекочитаемые сообщения.
 *
 * Сервер отвечает на `GETCONF` строкой `DENIED:<reason>`
 * (`rust-client/protocol.rs`), а движок превращает её в `FATAL_AUTH: ...` или
 * `FATAL_PROTOCOL: ...` в stderr. Приложение должно показать понятный текст,
 * а не сырой ответ протокола.
 */
enum class CsqttAuthDenial(val reason: String, val userMessage: String) {
    WRONG_PASSWORD("wrong_password", "Неверный пароль подключения"),
    EXPIRED("expired", "Срок действия пароля истёк"),
    DEACTIVATED("deactivated", "Аккаунт деактивирован"),
    PROTOCOL_MISMATCH(
        "protocol_mismatch",
        "Версия движка CSQTT не совпадает с сервером; обновите приложение",
    ),
    INVALID_WORKER_COUNT("invalid_worker_count", "Превышен лимит потоков"),
    UNKNOWN("", "Доступ отклонён сервером");

    companion object {
        /** Разбирает причину из `DENIED:<reason>`. */
        fun fromReason(reason: String?): CsqttAuthDenial {
            val normalized = reason?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.reason == normalized } ?: UNKNOWN
        }

        /**
         * Ищет отказ авторизации в строке лога: `DENIED:<reason>`,
         * `FATAL_AUTH: ...`, `FATAL_PROTOCOL: ...`. `null` — обычная строка.
         */
        fun fromLine(line: String): CsqttAuthDenial? {
            val denied = extractDeniedReason(line)
            if (denied != null) return fromReason(denied)
            val lower = line.lowercase()
            if (!lower.contains("fatal_auth") && !lower.contains("fatal_protocol")) return null
            return when {
                lower.contains("protocol_mismatch") ||
                    lower.contains("fatal_protocol") ||
                    lower.contains("разные версии протокола") -> PROTOCOL_MISMATCH
                lower.contains("expired") || lower.contains("срок действия") -> EXPIRED
                lower.contains("deactivated") || lower.contains("деактивирован") -> DEACTIVATED
                lower.contains("invalid_worker_count") || lower.contains("лимит потоков") ->
                    INVALID_WORKER_COUNT
                lower.contains("wrong_password") || lower.contains("неверный пароль") ||
                    lower.contains("invalid password") -> WRONG_PASSWORD
                else -> UNKNOWN
            }
        }

        private fun extractDeniedReason(line: String): String? {
            val marker = CsqttDefaults.DENIED_PREFIX
            val index = line.indexOf(marker, ignoreCase = true)
            if (index < 0) return null
            val tail = line.substring(index + marker.length).trimStart()
            if (tail.isEmpty()) return null
            return tail.takeWhile { !it.isWhitespace() && it != '|' && it != ',' && it != ';' && it != ')' }
                .trim()
                .trimEnd('.')
                .takeIf { it.isNotEmpty() }
        }
    }
}

/** Коды выхода движка и сообщения для UI. */
object CsqttExitCodes {
    const val NORMAL = CsqttDefaults.EXIT_NORMAL
    const val FATAL = CsqttDefaults.EXIT_FATAL
    const val TUN_LOST = CsqttDefaults.EXIT_TUN_LOST

    /** Человекочитаемое сообщение для кода выхода или `null` для штатного завершения. */
    fun message(exitCode: Int): String? = when (exitCode) {
        NORMAL -> null
        TUN_LOST -> "TUN-интерфейс потерян, переподключаемся"
        FATAL -> "Движок CSQTT завершился с ошибкой"
        else -> "Движок CSQTT завершился (код $exitCode)"
    }
}

/**
 * Результат разбора одной строки stdout/stderr движка.
 *
 * Помимо событий и TUNCONF, парсер вынимает строки запроса капчи и отказы
 * авторизации — именно они нужны runtime-адаптеру (этап S3).
 */
sealed class CsqttOutput {
    data class Event(val event: CsqttEvent) : CsqttOutput()
    data class TunConf(val conf: CsqttTunConf) : CsqttOutput()
    data class CaptchaRequest(
        val mode: String,
        val redirectUri: String,
        val sessionToken: String,
    ) : CsqttOutput()
    data class AuthDenied(val denial: CsqttAuthDenial, val line: String) : CsqttOutput()

    /** Обычная строка лога; `isError` — пришла из stderr/содержит ошибку. */
    data class Log(val text: String, val isError: Boolean = false) : CsqttOutput()
}

/** Единая точка разбора строк движка: события, TUNCONF, капча, отказы, лог. */
object CsqttOutputParser {
    fun parse(line: String, fromStderr: Boolean = false): CsqttOutput {
        val event = CsqttEventParser.parse(line)
        if (event != null) {
            if (event is CsqttEvent.Config) {
                val tunConf = CsqttTunConfParser.parse(event)
                if (tunConf != null) return CsqttOutput.TunConf(tunConf)
            }
            return CsqttOutput.Event(event)
        }
        CsqttCaptchaRequestParser.parse(line)?.let { return it }
        CsqttTunConfParser.parse(line)?.let { return CsqttOutput.TunConf(it) }
        CsqttAuthDenial.fromLine(line)?.let { return CsqttOutput.AuthDenied(it, line.trim()) }
        return CsqttOutput.Log(
            text = line.trim(),
            isError = fromStderr || looksLikeError(line),
        )
    }

    private fun looksLikeError(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("fatal") ||
            lower.contains("ошибка") ||
            lower.contains("error") ||
            lower.contains("panic")
    }
}

/** Парсер строки запроса WebView-капчи `CAPTCHA_SOLVE|<mode>|<uri>|<token>`. */
object CsqttCaptchaRequestParser {
    fun parse(line: String): CsqttOutput.CaptchaRequest? {
        val trimmed = line.trim()
        val index = trimmed.indexOf(CsqttDefaults.CAPTCHA_SOLVE_PREFIX)
        if (index < 0) return null
        val payload = trimmed.substring(index + CsqttDefaults.CAPTCHA_SOLVE_PREFIX.length)
        val parts = payload.split('|')
        return when {
            parts.size >= 3 -> CsqttOutput.CaptchaRequest(
                mode = parts[0].trim().ifEmpty { CsqttDefaults.CAPTCHA_MODE_AUTO },
                redirectUri = parts[1].trim(),
                sessionToken = parts.drop(2).joinToString("|").trim(),
            )
            parts.size == 2 -> {
                // Совместимость со старым форматом: redirect_uri|session_token.
                CsqttOutput.CaptchaRequest(
                    mode = CsqttDefaults.CAPTCHA_MODE_AUTO,
                    redirectUri = parts[0].trim(),
                    sessionToken = parts[1].trim(),
                )
            }
            else -> null
        }
    }
}
