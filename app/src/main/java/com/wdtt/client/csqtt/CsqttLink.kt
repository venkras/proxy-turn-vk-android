package com.wdtt.client.csqtt

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Разобранная ссылка `csqtt://`. */
data class CsqttLink(
    val host: String,
    /** Порт сервера (в ссылке лежит в параметре `peer`). */
    val port: Int,
    val password: String = "",
    val hashes: List<String> = emptyList(),
    /** VK access token для `auto_js`; взаимоисключён с [hashes]. */
    val accessToken: String = "",
) {
    /** `host:port` с квадратными скобками для IPv6. */
    val peer: String
        get() = CsqttDefaults.peerHostPort(host, port)

    /** Превращает ссылку в конфигурацию движка. */
    fun toConfig(): CsqttConfig {
        val autoJs = hashes.isEmpty() && accessToken.isNotEmpty()
        return CsqttConfig(
            peerHost = host,
            peerPort = port,
            password = password,
            vkHashes = hashes,
            vkAccessToken = accessToken,
            vkHashMode = if (autoJs) CsqttDefaults.VK_HASH_MODE_AUTO_JS else CsqttDefaults.VK_HASH_MODE_MANUAL,
        )
    }
}

/**
 * Расшифровка и разбор ссылок `csqtt://`.
 *
 * Формат из апстрима (`csqtt-main/.../ui/utils/UiUtils.kt` и веб-панели):
 *  - весь payload — Base64URL без padding: 12 байт IV + AES-GCM(plaintext, tag 128);
 *  - ключ — SHA-256 от строки `"csqtt-chiper-key"`;
 *  - plaintext — `connect?v=2&host=<host>&peer=<port>&password=<pwd>[&hashes=a+b]`;
 *  - IV детерминированный: первые 12 байт HMAC-SHA256(ключ, plaintext), поэтому
 *    одинаковые ссылки кодируются одинаково.
 *
 * Поддерживается только `v=2`; старые незашифрованные ссылки и `v=1`
 * отклоняются. Реализация на `java.util.Base64`/`javax.crypto`, без `android.*`,
 * поэтому полностью покрывается JVM-тестами.
 */
object CsqttLinkParser {
    private const val PREFIX = "csqtt://"
    private const val CIPHER_KEY_SEED = "csqtt-chiper-key"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val MIN_ENCRYPTED_LENGTH = IV_LENGTH + GCM_TAG_BITS / 8
    private const val MAX_HASHES = CsqttDefaults.MAX_VK_HASHES
    private const val MIN_HASH_LENGTH = 16
    private const val MAX_TOKEN_LENGTH = 4096

    private val cipherKey: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        SecretKeySpec(digest.digest(CIPHER_KEY_SEED.toByteArray(Charsets.UTF_8)), "AES")
    }

    /** Разбирает `csqtt://<base64url>`; `null` при любой ошибке формата. */
    fun parse(raw: String): CsqttLink? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val payload = trimmed.substring(PREFIX.length).trim()
        if (payload.isEmpty()) return null
        val decoded = decrypt(payload) ?: return null
        return parseDecrypted(decoded)
    }

    /** Шифрует plaintext в Base64URL без padding (для тестов и экспорта). */
    fun encrypt(plaintext: String): String {
        val iv = deterministicIv(plaintext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(IV_LENGTH + ciphertextWithTag.size)
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH)
        System.arraycopy(ciphertextWithTag, 0, combined, IV_LENGTH, ciphertextWithTag.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    /** Собирает ссылку `csqtt://` из полей профиля. */
    fun format(
        host: String,
        port: Int,
        password: String,
        hashes: List<String> = emptyList(),
        token: String = "",
    ): String {
        val query = mutableListOf("v=2", "host=$host", "peer=$port", "password=$password")
        if (hashes.isNotEmpty()) {
            query += "hashes=" + hashes.joinToString("+")
        } else if (token.isNotEmpty()) {
            query += "token=$token"
        }
        return PREFIX + encrypt("connect?" + query.joinToString("&"))
    }

    internal fun parseDecrypted(decoded: String): CsqttLink? {
        if (!decoded.contains("host=") && !decoded.contains("connect?")) return null
        val candidate = when {
            decoded.startsWith("csqtt://connect?", ignoreCase = true) -> decoded
            decoded.startsWith("connect?", ignoreCase = true) -> "$PREFIX$decoded"
            decoded.startsWith(PREFIX, ignoreCase = true) -> PREFIX + "connect?" + decoded.substring(PREFIX.length)
            else -> "$PREFIX" + "connect?$decoded"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        return parseV2(uri)
    }

    private fun parseV2(uri: URI): CsqttLink? {
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.orEmpty().isNotEmpty()) return null
        val parameters = parseRawQuery(uri.rawQuery ?: return null) ?: return null
        if (decode(parameters["v"]) != "2") return null

        val host = decode(parameters["host"])?.takeIf { it.isNotBlank() } ?: return null
        if (isLoopbackOrAny(host)) return null

        val port = decode(parameters["peer"])?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val password = decode(parameters["password"])?.trim().orEmpty()
        if (host.any(Char::isWhitespace)) return null
        if (password.isNotEmpty() && password.any(Char::isWhitespace)) return null

        val hashes = parameters["hashes"]?.let(::parseHashes) ?: emptyList()
        if (parameters.containsKey("hashes") && hashes.isEmpty()) return null

        val accessToken = parameters["token"]?.let(::parseToken).orEmpty()
        if (parameters.containsKey("token") && accessToken.isEmpty()) return null

        // Один профиль не может одновременно нести хеши и токен: движку нужен
        // ровно один источник креденшелов.
        if (hashes.isNotEmpty() && accessToken.isNotEmpty()) return null

        return CsqttLink(host, port, password, hashes, accessToken)
    }

    private fun parseRawQuery(rawQuery: String): Map<String, String>? {
        if (rawQuery.isEmpty()) return null
        val sanitized = rawQuery.replace("&amp;", "&")
        val result = linkedMapOf<String, String>()
        if (sanitized.contains('&') || sanitized.contains(';')) {
            for (part in sanitized.split(Regex("[&;]"))) {
                if (part.isBlank()) continue
                val separator = part.indexOf('=')
                if (separator <= 0) continue
                val key = decode(part.substring(0, separator)) ?: continue
                val value = part.substring(separator + 1)
                if (key.isNotBlank()) {
                    if (result.containsKey(key)) return null
                    result[key] = value
                }
            }
            if (result.keys.containsAll(listOf("v", "host", "peer"))) {
                return result
            }
        }

        // Веб-панель иногда склеивает параметры без '&':
        // `?v=2host=203.0.113.88peer=46000password=...`.
        val pattern = Regex(
            "(?:^|[&;]|(?<=[a-zA-Z0-9_]))(v|host|peer|password|client|hashes|token|workers)=" +
                "([^&?]*?)(?=(?:v|host|peer|password|client|hashes|token|workers)=|$)",
            RegexOption.IGNORE_CASE,
        )
        val matches = pattern.findAll(sanitized).toList()
        if (matches.isNotEmpty()) {
            val extracted = linkedMapOf<String, String>()
            for (match in matches) {
                val key = decode(match.groupValues[1].lowercase()) ?: continue
                val value = match.groupValues[2]
                if (extracted.containsKey(key)) return null
                extracted[key] = value
            }
            if (extracted.keys.containsAll(listOf("v", "host", "peer"))) {
                return extracted
            }
        }

        return result.ifEmpty { null }
    }

    private fun parseHashes(rawHashes: String): List<String> {
        val parts = rawHashes.split('+')
        if (parts.size !in 1..MAX_HASHES || parts.any { it.isEmpty() }) return emptyList()
        val hashes = parts.map { encoded ->
            decode(encoded)?.let(::stripVkUrlStatic).orEmpty()
        }
        if (hashes.any { it.length < MIN_HASH_LENGTH || it.any(Char::isWhitespace) }) return emptyList()
        return hashes.distinct().takeIf { it.size == hashes.size }.orEmpty()
    }

    private fun parseToken(rawToken: String): String? {
        val token = decode(rawToken)?.trim().orEmpty()
        return token.takeIf {
            it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH && it.none(Char::isWhitespace)
        }
    }

    private fun decode(value: String?): String? {
        if (value == null) return null
        return runCatching {
            // '+' внутри значения — литерал, а не пробел: кодируем его обратно.
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()
    }

    private fun decrypt(rawPayload: String): String? {
        val bytes = decodeBase64(rawPayload) ?: return null
        if (bytes.size < MIN_ENCRYPTED_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                cipherKey,
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_LENGTH),
            )
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeBase64(input: String): ByteArray? {
        val clean = input.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        if (clean.isEmpty()) return null
        return runCatching { Base64.getUrlDecoder().decode(clean) }
            .recoverCatching { Base64.getDecoder().decode(clean) }
            .recoverCatching { Base64.getMimeDecoder().decode(clean) }
            .getOrNull()
    }

    private fun deterministicIv(plaintext: String): ByteArray {
        val fallback = ByteArray(IV_LENGTH).also {
            java.security.SecureRandom().nextBytes(it)
        }
        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(cipherKey)
            val hmac = mac.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ByteArray(IV_LENGTH).also { iv -> System.arraycopy(hmac, 0, iv, 0, IV_LENGTH) }
        }.getOrElse { fallback }
    }

    private fun isLoopbackOrAny(host: String): Boolean {
        val normalized = host.trim()
        return normalized == "0.0.0.0" ||
            normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized.equals("localhost", ignoreCase = true)
    }

    /** Убирает префикс `https://vk.com/call/join/` и хвост `?/#` у хеша. */
    fun stripVkUrlStatic(input: String): String {
        var value = input.trim()
        val lower = value.lowercase()
        val prefixes = listOf(
            "https://vk.com/call/join/",
            "http://vk.com/call/join/",
            "https://m.vk.com/call/join/",
            "http://m.vk.com/call/join/",
            "m.vk.com/call/join/",
            "vk.com/call/join/",
            "https://vk.ru/call/join/",
            "http://vk.ru/call/join/",
            "https://m.vk.ru/call/join/",
            "http://m.vk.ru/call/join/",
            "m.vk.ru/call/join/",
            "vk.ru/call/join/",
        )
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length)
                break
            }
        }
        val queryIndex = value.indexOf('?')
        if (queryIndex != -1) value = value.substring(0, queryIndex)
        val fragmentIndex = value.indexOf('#')
        if (fragmentIndex != -1) value = value.substring(0, fragmentIndex)
        return value.trimEnd('/')
    }
}
