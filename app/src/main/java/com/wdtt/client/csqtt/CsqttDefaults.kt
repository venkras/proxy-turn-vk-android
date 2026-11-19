package com.wdtt.client.csqtt

/**
 * Константы контракта движка CSQTT (v2.5.0, `rust-client/main.rs`).
 *
 * Пакет `com.wdtt.client.csqtt` намеренно не импортирует `android.*`: вся
 * логика протокола должна прогоняться обычными JVM unit-тестами.
 */
object CsqttDefaults {
    /** Имя ELF-движка внутри APK (`libcsqtt.so`), см. scripts/build-csqtt-engine.sh. */
    const val BINARY_NAME = "libcsqtt.so"

    /** Префикс машинных событий в stdout (`events.rs`). */
    const val EVENT_PREFIX = "__CSQTT_EVENT__|"

    /** Префикс ответа сервера с параметрами TUN (`protocol.rs`). */
    const val TUNCONF_PREFIX = "TUNCONF:"

    /** Строка запроса WebView-капчи (`captcha.rs`): `CAPTCHA_SOLVE|<mode>|<uri>|<token>`. */
    const val CAPTCHA_SOLVE_PREFIX = "CAPTCHA_SOLVE|"

    /** Отказ авторизации в TURN-протоколе (`protocol.rs`). */
    const val DENIED_PREFIX = "DENIED:"

    // ---- Переменные окружения ------------------------------------------------

    /** Пароль передаётся только через env, argv его не содержит. */
    const val ENV_PASSWORD = "CSQTT_PASSWORD"
    const val ENV_EVENTS = "CSQTT_EVENTS"
    const val ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
    const val ENV_RAYON_THREADS = "RAYON_NUM_THREADS"

    const val EVENTS_ENABLED = "1"
    const val RAYON_NUM_THREADS = "2"

    // ---- Сеть ----------------------------------------------------------------

    /** Порт CSQTT-сервера по умолчанию (UDP). */
    const val DEFAULT_PEER_PORT = 46000

    /** Порт веб-панели сервера по умолчанию. */
    const val DEFAULT_WEB_PORT = 46002

    const val DEFAULT_LOCAL_HOST = "127.0.0.1"

    /**
     * В TUN-режиме локальный UDP-порт не открывается, поэтому клиент слушает
     * `127.0.0.1:0` (см. `dispatcher.rs`: при `-tun-uds` `local_port = "0"`).
     */
    const val DEFAULT_LOCAL_PORT = 0

    const val DEFAULT_MTU = 1500
    const val MIN_MTU = 1280
    const val MAX_MTU = 1500

    val DEFAULT_DNS: List<String> = listOf("1.1.1.1", "8.8.8.8")
    const val DEFAULT_DNS_CSV = "1.1.1.1,8.8.8.8"

    /** Код VK «звонок недоступен», который движок шлёт в `CALL_UNAVAILABLE`. */
    const val CALL_NOT_FOUND_CODE = 951

    // ---- Воркеры -------------------------------------------------------------

    const val MIN_WORKERS = 5
    const val MIN_EXACT_WORKERS = 3
    const val WORKERS_PER_GROUP = 10
    const val MAX_WORKERS = 100
    const val DEFAULT_WORKERS = 10

    /** Сколько потоков TURN-стримов обслуживает один поток tokio (`main.rs`). */
    const val STREAMS_PER_RUNTIME_WORKER = 8

    /** Верхний предел хешей VK в движке (`MAX_VK_HASHES` в `main.rs`). */
    const val MAX_VK_HASHES = 6

    // ---- Режимы --------------------------------------------------------------

    const val OBFUSCATION_UNIFIED = "unified"
    const val OBFUSCATION_AUDIO = "audio"
    const val OBFUSCATION_VIDEO = "video"

    const val TURN_TRANSPORT_UDP = "udp"
    const val TURN_TRANSPORT_TCP_TLS = "tcp_tls"

    const val SRTP_MODE_AUTO = "auto"
    const val SRTP_MODE_DTLS = "dtls-srtp"
    const val SRTP_MODE_WRAP = "wrap-srtp"

    const val VK_HASH_MODE_MANUAL = "manual"
    const val VK_HASH_MODE_AUTO_API = "auto_api"
    const val VK_HASH_MODE_AUTO_JS = "auto_js"

    const val VK_AUTH_MODE_VKCALLS = "vkcalls"
    const val VK_AUTH_MODE_LEGACY = "legacy"
    const val VK_AUTH_MODE_AUTO_JS = "auto_js"

    const val CAPTCHA_MODE_AUTO = "auto"
    const val CAPTCHA_MODE_WV = "wv"
    const val CAPTCHA_MODE_RJS = "rjs"

    const val FINGERPRINT_CHROME = "chrome"

    val OBFUSCATION_MODES: Set<String> = setOf(
        OBFUSCATION_UNIFIED,
        OBFUSCATION_AUDIO,
        OBFUSCATION_VIDEO,
    )

    val TURN_TRANSPORTS: Set<String> = setOf(
        TURN_TRANSPORT_UDP,
        TURN_TRANSPORT_TCP_TLS,
    )

    val SRTP_MODES: Set<String> = setOf(
        SRTP_MODE_AUTO,
        SRTP_MODE_DTLS,
        SRTP_MODE_WRAP,
    )

    val FINGERPRINTS: Set<String> = setOf("chrome", "firefox", "safari", "edge", "opera")

    val VK_HASH_MODES: Set<String> = setOf(
        VK_HASH_MODE_MANUAL,
        VK_HASH_MODE_AUTO_API,
        VK_HASH_MODE_AUTO_JS,
    )

    val VK_AUTH_MODES: Set<String> = setOf(
        VK_AUTH_MODE_VKCALLS,
        VK_AUTH_MODE_LEGACY,
        VK_AUTH_MODE_AUTO_JS,
    )

    val CAPTCHA_MODES: Set<String> = setOf(
        CAPTCHA_MODE_AUTO,
        CAPTCHA_MODE_WV,
        CAPTCHA_MODE_RJS,
    )

    // ---- Внутренний протокол -------------------------------------------------

    const val PROTOCOL_STREAM_V1 = "stream-v1"
    const val PROTOCOL_STREAM_V2 = "stream-v2"

    const val WORKERS_FIELD_PREFIX = "workers="
    const val PADDING_FIELD_PREFIX = "pad="

    // ---- Процесс -------------------------------------------------------------

    const val SOCKET_NAME_PREFIX = "wdtt_csqtt_"

    /** Сколько ждём после `STOP` в stdin перед destroy(). */
    const val STOP_GRACE_MS = 1_500L
    const val DESTROY_WAIT_MS = 1_000L
    const val FORCE_DESTROY_WAIT_MS = 500L

    /** Коды выхода движка (`main.rs`). */
    const val EXIT_NORMAL = 0
    const val EXIT_FATAL = 1
    const val EXIT_TUN_LOST = 3

    /**
     * Нормализация числа воркеров ровно как `normalize_worker_count` в `main.rs`:
     * не больше 5 — ровно 5; иначе кратное 10 в диапазоне 10..100.
     */
    fun normalizeWorkers(requested: Int): Int {
        if (requested <= MIN_WORKERS) return MIN_WORKERS
        val clamped = requested.coerceIn(WORKERS_PER_GROUP, MAX_WORKERS)
        return clamped / WORKERS_PER_GROUP * WORKERS_PER_GROUP
    }

    /** `--exact-workers`: движок сам зажимает значение в 3..100 без выравнивания по группам. */
    fun normalizeExactWorkers(requested: Int): Int =
        requested.coerceIn(MIN_EXACT_WORKERS, MAX_WORKERS)

    fun normalizeWorkers(requested: Int, exact: Boolean): Int =
        if (exact) normalizeExactWorkers(requested) else normalizeWorkers(requested)

    fun normalizeObfuscation(mode: String?): String {
        val normalized = mode?.trim()?.lowercase().orEmpty()
        return if (normalized in OBFUSCATION_MODES) normalized else OBFUSCATION_UNIFIED
    }

    fun normalizeTurnTransport(transport: String?): String {
        val normalized = transport?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            TURN_TRANSPORT_TCP_TLS, "tcp", "tls" -> TURN_TRANSPORT_TCP_TLS
            else -> TURN_TRANSPORT_UDP
        }
    }

    fun normalizeSrtpMode(mode: String?): String {
        val normalized = mode?.trim()?.lowercase().orEmpty()
        return if (normalized in SRTP_MODES) normalized else SRTP_MODE_AUTO
    }

    fun normalizeFingerprint(fingerprint: String?): String {
        val normalized = fingerprint?.trim()?.lowercase().orEmpty()
        return if (normalized in FINGERPRINTS) normalized else FINGERPRINT_CHROME
    }

    fun normalizeVkHashMode(mode: String?): String {
        val normalized = mode?.trim()?.lowercase().orEmpty()
        return if (normalized in VK_HASH_MODES) normalized else VK_HASH_MODE_MANUAL
    }

    fun normalizeVkAuthMode(mode: String?): String {
        val normalized = mode?.trim()?.lowercase().orEmpty()
        return if (normalized in VK_AUTH_MODES) normalized else VK_AUTH_MODE_VKCALLS
    }

    fun normalizeCaptchaMode(mode: String?): String {
        val normalized = mode?.trim()?.lowercase().orEmpty()
        return if (normalized in CAPTCHA_MODES) normalized else CAPTCHA_MODE_AUTO
    }

    fun normalizeMtu(mtu: Int): Int = if (mtu in MIN_MTU..MAX_MTU) mtu else DEFAULT_MTU

    /**
     * Имя abstract UDS-сокета, который слушает движок (`-tun-uds`). Сокет
     * создаёт Rust, Android подключается к этому же имени и передаёт fd.
     */
    fun newSocketName(pid: Int, nano: Long): String = "$SOCKET_NAME_PREFIX${pid}_$nano"

    /** `host:port`, IPv6-адрес оборачивается в квадратные скобки. */
    fun peerHostPort(host: String, port: Int): String {
        val trimmedHost = host.trim()
        val effectivePort = if (port in 1..65535) port else DEFAULT_PEER_PORT
        val hostForPeer = if (trimmedHost.contains(':') && !trimmedHost.startsWith("[")) {
            "[$trimmedHost]"
        } else {
            trimmedHost
        }
        return "$hostForPeer:$effectivePort"
    }
}
