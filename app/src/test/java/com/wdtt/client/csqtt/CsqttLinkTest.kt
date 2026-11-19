package com.wdtt.client.csqtt

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttLinkTest {

    private fun encryptedLink(plain: String): String = "csqtt://" + CsqttLinkParser.encrypt(plain)

    @Test
    fun parsesV2WithoutHashes() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=203.0.113.7&peer=46000&password=p%40ss"),
        )
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("p@ss", link.password)
        assertEquals(emptyList<String>(), link.hashes)
        assertEquals("", link.accessToken)
    }

    @Test
    fun parsesConcatenatedParametersFromWebPanel() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2host=203.0.113.88peer=46000password=dummy_secret_pass"),
        )
        requireNotNull(link)
        assertEquals("203.0.113.88", link.host)
        assertEquals(46000, link.port)
        assertEquals("dummy_secret_pass", link.password)
    }

    @Test
    fun parsesSixPlusSeparatedHashes() {
        val hashes = (1..6).map { "abcdefghijklmnop$it" }
        val link = CsqttLinkParser.parse(
            encryptedLink(
                "connect?v=2&host=vps.example&peer=46000&password=secret&hashes=" +
                    hashes.joinToString("+"),
            ),
        )
        requireNotNull(link)
        assertEquals(hashes, link.hashes)
    }

    @Test
    fun parsesVkAccessToken() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc-_%2B9"),
        )
        requireNotNull(link)
        assertEquals("vk1.aBc-_+9", link.accessToken)
        assertEquals(emptyList<String>(), link.hashes)
    }

    @Test
    fun preservesEncodedPlusInsideHash() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=vps.example&peer=46000&password=secret&hashes=abcdefghijklmno%2Bp"),
        )
        requireNotNull(link)
        assertEquals(listOf("abcdefghijklmno+p"), link.hashes)
    }

    @Test
    fun formatsAndParsesLinkWithHashes() {
        val formatted = CsqttLinkParser.format(
            host = "203.0.113.7",
            port = 46000,
            password = "SecretPass123456",
            hashes = listOf("abcdefghijklmnop1"),
        )
        val link = CsqttLinkParser.parse(formatted)
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("SecretPass123456", link.password)
        assertEquals(listOf("abcdefghijklmnop1"), link.hashes)
    }

    @Test
    fun formatsAndParsesLinkWithToken() {
        val formatted = CsqttLinkParser.format(
            host = "vps.example",
            port = 46000,
            password = "",
            token = "vk1.token-123",
        )
        val link = CsqttLinkParser.parse(formatted)
        requireNotNull(link)
        assertEquals("vk1.token-123", link.accessToken)
        assertTrue(link.hashes.isEmpty())
    }

    @Test
    fun deterministicEncryption() {
        val plain = "connect?v=2&host=203.0.113.7&peer=46000"
        assertEquals(CsqttLinkParser.encrypt(plain), CsqttLinkParser.encrypt(plain))
    }

    @Test
    fun parsesRustEncryptedLink() {
        val rustLink = "csqtt://GNANVH0fhD6MisDFiWP_P0Bm_FHr-frawgfwur3Zx4ipjJx8gk-M6ZZbnDr_2clwPmzO-QS8AkiHLaMXyYO1RHyPeehXPqTc6jJi_iw72fRQJ9AR918ne5XsBRg"
        val link = CsqttLinkParser.parse(rustLink)
        requireNotNull(link)
        assertEquals("1.2.3.4", link.host)
        assertEquals(46000, link.port)
    }

    @Test
    fun formatsIpv6PeerWithBrackets() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=2001%3Adb8%3A%3A1&peer=46000&password=secret"),
        )
        requireNotNull(link)
        assertEquals("[2001:db8::1]:46000", link.peer)
    }

    @Test
    fun exposesPeerWithDefaultPort() {
        val link = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=203.0.113.7&peer=46000"),
        )
        requireNotNull(link)
        assertEquals("203.0.113.7:46000", link.peer)
    }

    @Test
    fun convertsLinkToConfig() {
        val hashesLink = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=203.0.113.7&peer=46000&password=secret&hashes=abcdefghijklmnop1"),
        )!!
        val hashesConfig = hashesLink.toConfig()
        assertEquals("203.0.113.7", hashesConfig.peerHost)
        assertEquals(46000, hashesConfig.peerPort)
        assertEquals("secret", hashesConfig.password)
        assertEquals(listOf("abcdefghijklmnop1"), hashesConfig.vkHashes)
        assertEquals(CsqttDefaults.VK_HASH_MODE_MANUAL, hashesConfig.vkHashMode)

        val tokenLink = CsqttLinkParser.parse(
            encryptedLink("connect?v=2&host=203.0.113.7&peer=46000&token=vk1.token"),
        )!!
        val tokenConfig = tokenLink.toConfig()
        assertEquals(CsqttDefaults.VK_HASH_MODE_AUTO_JS, tokenConfig.vkHashMode)
        assertEquals("vk1.token", tokenConfig.vkAccessToken)
    }

    @Test
    fun rejectsLegacyUnencryptedLinks() {
        assertNull(CsqttLinkParser.parse("csqtt://secret@203.0.113.7:46000"))
        assertNull(CsqttLinkParser.parse("csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=secret"))

        val rawPayload = "connect?v=2&host=203.0.113.7&peer=46000&password=secret"
        val base64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPayload.toByteArray(Charsets.UTF_8))
        assertNull(CsqttLinkParser.parse("csqtt://$base64"))
    }

    @Test
    fun rejectsMalformedV2Links() {
        assertNull(CsqttLinkParser.parse(""))
        assertNull(CsqttLinkParser.parse("   "))
        assertNull(CsqttLinkParser.parse("csqtt://"))
        assertNull(CsqttLinkParser.parse("https://example.com"))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=3&host=1.2.3.4&peer=46000&password=secret")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=1&host=1.2.3.4&peer=46000&password=secret")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=2&host=1.2.3.4&peer=0&password=secret")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=2&host=1.2.3.4&peer=70000&password=secret")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=2&host=1.2.3.4&peer=46000&password=bad%20pass")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=2&host=1.2.3.4&peer=46000&hashes=")))
        assertNull(CsqttLinkParser.parse(encryptedLink("connect?v=2&host=1.2.3.4&peer=46000&hashes=short")))
        assertNull(
            CsqttLinkParser.parse(
                encryptedLink("connect?v=2&host=1.2.3.4&peer=46000&token=bad%20token"),
            ),
        )
        assertNull(
            CsqttLinkParser.parse(
                encryptedLink("connect?v=2&host=1.2.3.4&peer=46000&token=one&token=two"),
            ),
        )
        assertNull(
            CsqttLinkParser.parse(
                encryptedLink(
                    "connect?v=2&host=1.2.3.4&peer=46000&password=secret" +
                        "&hashes=abcdefghijklmnop&token=token",
                ),
            ),
        )
        assertNull(
            CsqttLinkParser.parse(
                encryptedLink(
                    "connect?v=2&host=1.2.3.4&peer=46000&password=secret&hashes=" +
                        (1..7).joinToString("+") { "abcdefghijklmnop$it" },
                ),
            ),
        )
    }

    @Test
    fun rejectsLoopbackAndAnyAddressHosts() {
        for (host in listOf("0.0.0.0", "127.0.0.1", "localhost", "LOCALHOST", "::1")) {
            assertNull(
                "host $host must be rejected",
                CsqttLinkParser.parse(
                    encryptedLink("connect?v=2&host=$host&peer=46000&password=secret"),
                ),
            )
        }
    }

    @Test
    fun rejectsTamperedAndTruncatedPayloads() {
        val valid = CsqttLinkParser.encrypt("connect?v=2&host=203.0.113.7&peer=46000")
        val bytes = Base64.getUrlDecoder().decode(valid)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertNull(CsqttLinkParser.parse("csqtt://$tampered"))

        val truncated = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))
        assertNull(CsqttLinkParser.parse("csqtt://$truncated"))
    }

    @Test
    fun acceptsPaddedBase64Payload() {
        val valid = CsqttLinkParser.encrypt("connect?v=2&host=203.0.113.7&peer=46000&password=secret")
        val bytes = Base64.getUrlDecoder().decode(valid)
        val padded = Base64.getEncoder().encodeToString(bytes)
        val link = CsqttLinkParser.parse("csqtt://$padded")
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
    }

    @Test
    fun stripsVkCallPrefixFromHashes() {
        assertEquals(
            "abcdefghijklmnop",
            CsqttLinkParser.stripVkUrlStatic("https://vk.com/call/join/abcdefghijklmnop?x=1#y"),
        )
        assertEquals("abcdefghijklmnop", CsqttLinkParser.stripVkUrlStatic("abcdefghijklmnop/"))
        assertEquals("abcdefghijklmnop", CsqttLinkParser.stripVkUrlStatic("https://m.vk.ru/call/join/abcdefghijklmnop"))
    }
}
