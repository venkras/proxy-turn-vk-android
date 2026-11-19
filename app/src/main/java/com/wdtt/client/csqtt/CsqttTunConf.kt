package com.wdtt.client.csqtt

/**
 * Разобранный ответ `TUNCONF` — параметры TUN-интерфейса на стороне Android.
 *
 * Формат сервера (`rust-server/protocol.rs`):
 * ```
 * TUNCONF:<ip>:<dns[,dns]>:<client_port>:<stream-v1|stream-v2>[:workers=<N>]
 * ```
 * Пятое поле может быть Base64-правилом маршрутизации (`ROUTING`) — движок
 * пересылает его как есть, поэтому парсер сохраняет его в [routingBase64].
 */
data class CsqttTunConf(
    val clientIp: String,
    /** Список DNS-серверов; пустой список заменяется на [dnsOrDefault]. */
    val dns: List<String> = emptyList(),
    /** Локальный порт из конфига; в TUN-режиме всегда 0. */
    val clientPort: Int = 0,
    val protocol: String = CsqttDefaults.PROTOCOL_STREAM_V1,
    /** Лимит воркеров от сервера, если он его прислал. */
    val workers: Int? = null,
    /** Base64-правило маршрутизации (может быть gzip), если сервер его прислал. */
    val routingBase64: String? = null,
    /** MTU для VpnService.Builder; контракт TUNCONF его не содержит. */
    val mtu: Int = CsqttDefaults.DEFAULT_MTU,
) {
    val dnsCsv: String get() = dns.joinToString(",")

    /** DNS для VpnService: если сервер не прислал валидных адресов — безопасный дефолт. */
    val dnsOrDefault: List<String> get() = dns.ifEmpty { CsqttDefaults.DEFAULT_DNS }

    val isStreamV2: Boolean get() = protocol == CsqttDefaults.PROTOCOL_STREAM_V2
}

/**
 * Парсер строки `TUNCONF:<ip>:<dns>:<port>:<proto>[:workers=N]`.
 *
 * Значения по умолчанию: порт 0, протокол `stream-v1`, MTU 1500. Строка
 * считается валидной только при корректном IPv4 и хотя бы одном корректном
 * DNS-адресе; мусорные DNS отбрасываются, невалидный IP обнуляет результат.
 */
object CsqttTunConfParser {
    private const val MAX_DNS_SERVERS = 4

    /** Разбирает строку или `CONFIG`-событие с префиксом. */
    fun parse(line: String): CsqttTunConf? {
        val trimmed = extractTunConf(line) ?: return null
        val payload = trimmed.substring(CsqttDefaults.TUNCONF_PREFIX.length)
        if (payload.isEmpty()) return null
        val parts = payload.split(':', limit = 5)
        if (parts.size < 2) return null

        val clientIp = parts[0].trim()
        if (!isIpv4(clientIp)) return null

        val dns = parts[1]
            .split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isIpv4(it) }
            .distinct()
            .take(MAX_DNS_SERVERS)
        if (dns.isEmpty()) return null

        var clientPort = 0
        var protocol = CsqttDefaults.PROTOCOL_STREAM_V1
        var workers: Int? = null
        var routingBase64: String? = null

        for (index in 2 until parts.size) {
            val part = parts[index].trim()
            if (part.isEmpty()) continue
            when {
                part.startsWith(CsqttDefaults.WORKERS_FIELD_PREFIX) -> {
                    workers = part.removePrefix(CsqttDefaults.WORKERS_FIELD_PREFIX)
                        .trim()
                        .toIntOrNull()
                        ?.takeIf { it in 0..CsqttDefaults.MAX_WORKERS }
                }
                part.startsWith(CsqttDefaults.PADDING_FIELD_PREFIX) -> Unit
                part == CsqttDefaults.PROTOCOL_STREAM_V1 || part == CsqttDefaults.PROTOCOL_STREAM_V2 -> {
                    protocol = part
                }
                index == 2 -> {
                    clientPort = part.toIntOrNull()
                        ?.takeIf { it in 0..65535 }
                        ?: 0
                }
                index == 4 -> routingBase64 = part
            }
        }

        return CsqttTunConf(
            clientIp = clientIp,
            dns = dns,
            clientPort = clientPort,
            protocol = protocol,
            workers = workers,
            routingBase64 = routingBase64,
        )
    }

    /** `TUNCONF:...` из строки лога или из `CsqttEvent.Config`. */
    fun parse(event: CsqttEvent.Config): CsqttTunConf? = parse(event.config)

    internal fun extractTunConf(line: String): String? {
        val trimmed = line.trim()
        val index = trimmed.indexOf(CsqttDefaults.TUNCONF_PREFIX)
        if (index < 0) return null
        val candidate = trimmed.substring(index)
        return candidate.takeIf { it.length > CsqttDefaults.TUNCONF_PREFIX.length }
    }

    /** Строгая проверка IPv4: ровно 4 октета 0..255 без лишних символов. */
    fun isIpv4(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 15) return false
        val octets = trimmed.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotEmpty() &&
                octet.length <= 3 &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
