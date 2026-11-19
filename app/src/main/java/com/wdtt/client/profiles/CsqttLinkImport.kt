package com.wdtt.client

import com.wdtt.client.csqtt.CsqttDefaults
import com.wdtt.client.csqtt.CsqttLink
import java.util.UUID

/**
 * Превращает разобранную ссылку `csqtt://` в профиль qWDTT.
 *
 * Ссылка не несёт UI-настроек (obfs/srtp/transport), поэтому здесь ставятся
 * безопасные значения по умолчанию, а пользователь может поменять их в
 * редакторе профиля. Пароль и VK-хеши сохраняются в профиле штатно; токен
 * `auto_js` дополнительно шифруется в [ProfilesStore].
 */
fun CsqttLink.toConnectionProfile(): ConnectionProfile {
    val hashMode = if (hashes.isEmpty() && accessToken.isNotEmpty()) {
        CsqttDefaults.VK_HASH_MODE_AUTO_JS
    } else {
        CsqttDefaults.VK_HASH_MODE_MANUAL
    }
    return ConnectionProfile(
        id = UUID.randomUUID().toString(),
        name = "CSQTT $host",
        peer = CsqttDefaults.peerHostPort(host, port),
        vkHashes = hashes.joinToString(","),
        workersPerHash = CsqttDefaults.DEFAULT_WORKERS,
        // В TUN-режиме CSQTT локальный порт не используется, но значение
        // должно быть валидным для общих проверок профиля.
        listenPort = 9000,
        password = password,
        useGlobalHashes = false,
        serverProtocol = SettingsStore.SERVER_PROTOCOL_CSQTT,
        csqttObfs = CsqttDefaults.OBFUSCATION_UNIFIED,
        csqttTransport = CsqttDefaults.TURN_TRANSPORT_UDP,
        csqttSrtp = CsqttDefaults.SRTP_MODE_AUTO,
        csqttFingerprint = CsqttDefaults.FINGERPRINT_CHROME,
        csqttHashMode = hashMode,
        csqttAccessToken = accessToken,
    )
}
