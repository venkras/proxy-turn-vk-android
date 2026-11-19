package com.wdtt.client.csqtt

/**
 * Маскирование секретов CSQTT для логов.
 *
 * Пароль движок получает только через `CSQTT_PASSWORD`, но хеши VK попадают в
 * argv (`-vk`). Ни то, ни другое не должно оказаться в logcat или в отчёте
 * диагностики, поэтому все строки argv прогоняются через [maskArgv], а
 * свободный текст — через [redact].
 */
object CsqttSecrets {
    const val MASK = "***"

    /** Флаги, значение которых нельзя показывать в логах. */
    private val SENSITIVE_FLAGS: Set<String> = setOf("vk", "salt")

    /** Минимальная длина секрета, которую имеет смысл вырезать из текста. */
    private const val MIN_REDACT_LENGTH = 4

    /** Пустой пароль → пустая строка, иначе маска. */
    fun maskPassword(password: String): String = if (password.isBlank()) "" else MASK

    /** Короткая безопасная сводка конфигурации (без пароля и хешей). */
    fun describeConfig(config: CsqttConfig): String {
        val normalized = config.normalized()
        return buildString {
            append("peer=").append(normalized.peer)
            append(", workers=").append(normalized.workers)
            append(", obfs=").append(normalized.obfs)
            append(", transport=").append(normalized.turnTransport)
            append(", srtp=").append(normalized.srtpMode)
            append(", fingerprint=").append(normalized.fingerprint)
            append(", hashes=").append(normalized.vkHashes.size)
            if (normalized.password.isNotEmpty()) {
                append(", password=").append(MASK)
            }
        }
    }

    /** Копия argv, в которой значения секретных флагов заменены маской. */
    fun maskArgv(argv: List<String>): List<String> {
        val masked = ArrayList<String>(argv.size)
        var hideNext = false
        for (argument in argv) {
            if (hideNext) {
                masked += if (argument.isBlank()) "" else MASK
                hideNext = false
                continue
            }
            masked += argument
            if (isSensitiveFlag(argument)) {
                hideNext = true
            } else if (isSensitiveInlineArgument(argument)) {
                masked[masked.lastIndex] = maskInlineArgument(argument)
            }
        }
        return masked
    }

    /** Безопасная строка для лога: маскированный argv, склеенный пробелами. */
    fun describeArgv(argv: List<String>): String = maskArgv(argv).joinToString(" ") { quote(it) }

    /** Безопасная строка команды вместе с env (пароль в env тоже маскируется). */
    fun describeCommand(command: CsqttCommand): String {
        val env = command.env.entries.joinToString(" ") { (key, value) ->
            val safeValue = if (key == CsqttDefaults.ENV_PASSWORD) maskPassword(value) else value
            "$key=${quote(safeValue)}"
        }
        val envPart = if (env.isEmpty()) "" else "$env "
        return envPart + describeArgv(command.argv)
    }

    /**
     * Вырезает из произвольной строки все известные секреты. Короткие значения
     * (< 4 символов) не трогаем, иначе маска съест обычный текст.
     */
    fun redact(text: String, vararg secrets: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (secret in secrets) {
            if (secret.length < MIN_REDACT_LENGTH) continue
            result = result.replace(secret, MASK)
        }
        return result
    }

    fun redact(text: String, secrets: Iterable<String>): String {
        if (text.isEmpty()) return text
        var result = text
        for (secret in secrets) {
            if (secret.length < MIN_REDACT_LENGTH) continue
            result = result.replace(secret, MASK)
        }
        return result
    }

    /**
     * Все секреты одного подключения: пароль, хеши VK и salt. Runtime-адаптер
     * прогоняет через этот список каждую строку stdout/stderr, чтобы пароль не
     * утёк даже если движок напечатает его в составе диагностического сообщения.
     */
    fun secretsOf(config: CsqttConfig): List<String> {
        val normalized = config.normalized()
        return buildList {
            if (normalized.password.isNotEmpty()) add(normalized.password)
            addAll(normalized.vkHashes)
            if (normalized.salt.isNotEmpty()) add(normalized.salt)
            if (normalized.vkAccessToken.isNotEmpty()) add(normalized.vkAccessToken)
        }
    }

    /**
     * Маскирование строки лога движка: известные секреты + протокольные
     * payload'ы, которые содержат пароль/токен по контракту CSQTT
     * (`GETCONF:<port>|<password>|…`, `CAPTCHA_RESULT|…`,
     * `VK_JS_BOOTSTRAP:…`, `CSQTT_PASSWORD=…`).
     *
     * Одного [redact] недостаточно: пароль может быть коротким (< 4 символов)
     * или прийти в base64-виде, поэтому опасный payload скрывается целиком.
     */
    fun redactLine(text: String, secrets: Iterable<String>): String {
        if (text.isEmpty()) return text
        var result = redact(text, secrets)
        result = maskPrefixedValue(result, "GETCONF:")
        result = maskPrefixedValue(result, "CSQTT_PASSWORD=")
        result = maskPrefixedValue(result, CsqttControl.PREFIX_CAPTCHA_RESULT)
        result = maskPrefixedValue(result, CsqttControl.PREFIX_VK_JS_BOOTSTRAP)
        return result
    }

    /**
     * Заменяет значение после [prefix] (до ближайшего пробела) на `<скрыто>`.
     * Обрабатывает все вхождения в строке, не зацикливаясь на собственной
     * вставке.
     */
    private fun maskPrefixedValue(text: String, prefix: String): String {
        var result = text
        var index = result.indexOf(prefix)
        while (index >= 0) {
            val valueStart = index + prefix.length
            var end = valueStart
            while (end < result.length && !result[end].isWhitespace()) end++
            val replacement = prefix + "<скрыто>"
            result = result.substring(0, index) + replacement + result.substring(end)
            index = result.indexOf(prefix, index + replacement.length)
        }
        return result
    }

    /**
     * Маскирует строку управления stdin для лога. Движок сам скрывает
     * `CAPTCHA_RESULT|` и `VK_JS_BOOTSTRAP:`, но мы не полагаемся на это и
     * дублируем защиту на стороне приложения.
     */
    fun maskControlLine(line: String): String {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith(CsqttControl.PREFIX_CAPTCHA_RESULT) -> "${
                CsqttControl.PREFIX_CAPTCHA_RESULT
            }<скрыто>"
            trimmed.startsWith(CsqttControl.PREFIX_VK_JS_BOOTSTRAP) -> "${
                CsqttControl.PREFIX_VK_JS_BOOTSTRAP
            }<скрыто>"
            else -> trimmed
        }
    }

    private fun isSensitiveFlag(argument: String): Boolean {
        val name = argument.trim().trimStart('-')
        return name in SENSITIVE_FLAGS
    }

    private fun isSensitiveInlineArgument(argument: String): Boolean {
        val trimmed = argument.trim()
        if (!trimmed.startsWith("-")) return false
        val name = trimmed.trimStart('-').substringBefore('=')
        return name in SENSITIVE_FLAGS && trimmed.contains('=')
    }

    private fun maskInlineArgument(argument: String): String {
        val separator = argument.indexOf('=')
        if (separator <= 0) return MASK
        val value = argument.substring(separator + 1)
        return argument.substring(0, separator + 1) + if (value.isBlank()) "" else MASK
    }

    private fun quote(value: String): String = if (value.any { it.isWhitespace() }) "\"$value\"" else value
}
