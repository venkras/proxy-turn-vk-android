package com.wdtt.client.csqtt

/**
 * Все параметры одного CSQTT-подключения.
 *
 * Модель описывает ровно то, что принимает движок `libcsqtt.so`
 * (`rust-client/main.rs`, структура `Arguments`), плюс данные, которые нужны
 * Android-стороне для передачи TUN fd. Она не содержит `android.*` типов и
 * спокойно создаётся в unit-тестах.
 *
 * Порт сервера необязателен: если он не задан (0 или вне диапазона 1..65535),
 * используется [CsqttDefaults.DEFAULT_PEER_PORT] (46000). Это позволяет
 * хранить в профиле «просто хост» и подставлять дефолт на этапе сборки argv.
 */
data class CsqttConfig(
    val peerHost: String = "",
    val peerPort: Int = CsqttDefaults.DEFAULT_PEER_PORT,
    val password: String = "",
    /** Хеши VK-звонков; движок принимает не больше [CsqttDefaults.MAX_VK_HASHES]. */
    val vkHashes: List<String> = emptyList(),
    /** Access token для `-vk-hash-mode auto_js` (передаётся через stdin, не в argv). */
    val vkAccessToken: String = "",
    val vkHashMode: String = CsqttDefaults.VK_HASH_MODE_MANUAL,
    val vkAuthMode: String = CsqttDefaults.VK_AUTH_MODE_VKCALLS,
    val captchaMode: String = CsqttDefaults.CAPTCHA_MODE_AUTO,
    val workers: Int = CsqttDefaults.DEFAULT_WORKERS,
    val exactWorkers: Boolean = false,
    val obfs: String = CsqttDefaults.OBFUSCATION_UNIFIED,
    val turnTransport: String = CsqttDefaults.TURN_TRANSPORT_UDP,
    val srtpMode: String = CsqttDefaults.SRTP_MODE_AUTO,
    val fingerprint: String = CsqttDefaults.FINGERPRINT_CHROME,
    val clientIds: List<String> = emptyList(),
    /** Свои TURN-адреса (`ip` или `ip:port` через запятую); пусто — адреса VK. */
    val customTurnIps: String = "",
    val generation: Long = 0L,
    val salt: String = "",
    /** Локальный порт из `-listen`; в TUN-режиме не открывается. */
    val localPort: Int = CsqttDefaults.DEFAULT_LOCAL_PORT,
    /** `-runtime-threads`; 0 — движок выбирает сам. */
    val runtimeThreads: Int = 0,
    val domainRouting: Boolean = false,
    val excludeRuZones: Boolean = false,
    val templateDomains: Boolean = false,
    val domainWhitelist: Boolean = false,
    val directDomains: String = "",
    val domainsFiles: List<String> = emptyList(),
    val ipRouting: Boolean = false,
    val ipWhitelist: Boolean = false,
    val directIps: String = "",
) {
    /** Режим авто-получения хешей через VK JS. */
    val isAutoJsHashes: Boolean
        get() = CsqttDefaults.normalizeVkHashMode(vkHashMode) == CsqttDefaults.VK_HASH_MODE_AUTO_JS

    /**
     * `-vk-auth-mode auto_js` без `-vk-hash-mode auto_js` движок сам понижает
     * до `vkcalls` (см. `run()` в main.rs). Повторяем это здесь, чтобы argv и
     * лог совпадали с фактическим поведением.
     */
    val effectiveVkAuthMode: String
        get() {
            val authMode = CsqttDefaults.normalizeVkAuthMode(vkAuthMode)
            return if (authMode == CsqttDefaults.VK_AUTH_MODE_AUTO_JS && !isAutoJsHashes) {
                CsqttDefaults.VK_AUTH_MODE_VKCALLS
            } else {
                authMode
            }
        }

    /** `host:port` для `-peer`; порт подставляется дефолтный, если не задан. */
    val peer: String
        get() = CsqttDefaults.peerHostPort(peerHost, peerPort)

    val hashesCsv: String
        get() = vkHashes.joinToString(",")

    val clientIdsCsv: String
        get() = clientIds.joinToString(",")

    /** Есть ли чем авторизоваться: хеши либо Auto JS-токен. */
    val hasCredentials: Boolean
        get() = vkHashes.isNotEmpty() || (isAutoJsHashes && vkAccessToken.isNotBlank())

    /**
     * Копия с безопасными значениями: обрезанные строки, дедуплицированные
     * хеши/клиентские id, валидные enum-режимы и нормализованное число воркеров.
     */
    fun normalized(): CsqttConfig {
        val normalizedHashes = vkHashes
            .flatMap { it.split(',', '\n', '\r', '\t', ' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(CsqttDefaults.MAX_VK_HASHES)
        val normalizedClientIds = clientIds
            .flatMap { it.split(',', '\n', '\r', '\t', ' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return copy(
            peerHost = peerHost.trim(),
            // Пароль передаётся в env как есть: движок не тримит значения.
            password = password,
            vkHashes = normalizedHashes,
            vkAccessToken = vkAccessToken.trim(),
            vkHashMode = CsqttDefaults.normalizeVkHashMode(vkHashMode),
            vkAuthMode = CsqttDefaults.normalizeVkAuthMode(vkAuthMode),
            captchaMode = CsqttDefaults.normalizeCaptchaMode(captchaMode),
            workers = CsqttDefaults.normalizeWorkers(workers, exactWorkers),
            obfs = CsqttDefaults.normalizeObfuscation(obfs),
            turnTransport = CsqttDefaults.normalizeTurnTransport(turnTransport),
            srtpMode = CsqttDefaults.normalizeSrtpMode(srtpMode),
            fingerprint = CsqttDefaults.normalizeFingerprint(fingerprint),
            clientIds = normalizedClientIds,
            customTurnIps = customTurnIps.trim().trim(','),
            salt = salt.trim(),
            localPort = localPort.coerceIn(0, 65535),
            runtimeThreads = runtimeThreads.coerceIn(0, 256),
            directDomains = directDomains.trim().trim(','),
            domainsFiles = domainsFiles.map { it.trim() }.filter { it.isNotEmpty() },
            directIps = directIps.trim().trim(','),
        )
    }

    /**
     * Проверка перед запуском процесса. Возвращает список проблем в
     * человекочитаемом виде; пустой список — всё готово.
     */
    fun problems(): List<CsqttConfigProblem> {
        val config = normalized()
        val result = mutableListOf<CsqttConfigProblem>()
        if (config.peerHost.isEmpty()) {
            result += CsqttConfigProblem.PEER_MISSING
        } else if (config.peerHost.any { it.isWhitespace() }) {
            result += CsqttConfigProblem.PEER_INVALID
        }
        if (config.peerPort != 0 && config.peerPort !in 1..65535) {
            result += CsqttConfigProblem.PEER_PORT_INVALID
        }
        if (config.password.isEmpty()) {
            result += CsqttConfigProblem.PASSWORD_MISSING
        } else if (config.password.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            result += CsqttConfigProblem.PASSWORD_INVALID
        }
        if (vkHashes.count { it.isNotBlank() } > CsqttDefaults.MAX_VK_HASHES) {
            result += CsqttConfigProblem.HASHES_LIMIT
        }
        if (!config.hasCredentials) {
            if (config.isAutoJsHashes) {
                result += CsqttConfigProblem.AUTO_JS_TOKEN_MISSING
            } else {
                result += CsqttConfigProblem.HASHES_MISSING
            }
        }
        if (config.directDomains.isNotEmpty() || config.domainsFiles.isNotEmpty()) {
            if (!config.domainRouting && !config.excludeRuZones && !config.templateDomains) {
                // Движок включает доменную маршрутизацию только по --domain-routing;
                // сама по себе подсказка не ошибка, но предупреждаем через проблему.
                result += CsqttConfigProblem.DOMAIN_ROUTING_DISABLED
            }
        }
        return result
    }

    companion object {
        /** Удобный конструктор для профиля «хост + пароль». */
        fun fromPeer(peerHost: String, password: String, peerPort: Int = CsqttDefaults.DEFAULT_PEER_PORT): CsqttConfig =
            CsqttConfig(peerHost = peerHost, peerPort = peerPort, password = password)
    }
}

/** Ошибки конфигурации CSQTT, которые блокируют запуск движка. */
enum class CsqttConfigProblem(val message: String, val isFatal: Boolean = true) {
    PEER_MISSING("Не указан сервер CSQTT"),
    PEER_INVALID("Некорректный адрес сервера CSQTT"),
    PEER_PORT_INVALID("Некорректный порт сервера CSQTT"),
    PASSWORD_MISSING("Не указан пароль подключения"),
    PASSWORD_INVALID("Пароль не должен содержать перевод строки"),
    HASHES_MISSING("Не указан хеш VK"),
    HASHES_LIMIT("Слишком много хешей VK (максимум ${CsqttDefaults.MAX_VK_HASHES})"),
    AUTO_JS_TOKEN_MISSING("Не указан VK access token для режима Auto JS"),
    SOCKET_MISSING("Не задано имя UDS-сокета для TUN", isFatal = true),
    BINARY_MISSING("Не задан путь к движку CSQTT", isFatal = true),
    DOMAIN_ROUTING_DISABLED("Доменные правила заданы, но доменная маршрутизация выключена", isFatal = false),
}
