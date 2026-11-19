package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttSecretsTest {

    @Test
    fun masksOnlyNonEmptyPasswords() {
        assertEquals("", CsqttSecrets.maskPassword(""))
        assertEquals("", CsqttSecrets.maskPassword("   "))
        assertEquals(CsqttSecrets.MASK, CsqttSecrets.maskPassword("a"))
        assertEquals(CsqttSecrets.MASK, CsqttSecrets.maskPassword("SuperSecret"))
    }

    @Test
    fun masksVkAndSaltValuesInArgv() {
        val argv = listOf(
            "/lib/libcsqtt.so",
            "-peer", "vps.example:46000",
            "-vk", "hashOne,hashTwo",
            "-salt", "session-salt",
            "-obfs", "unified",
        )
        val masked = CsqttSecrets.maskArgv(argv)

        assertEquals("hashOne,hashTwo", argv[4])
        assertEquals(CsqttSecrets.MASK, masked[4])
        assertEquals("session-salt", argv[6])
        assertEquals(CsqttSecrets.MASK, masked[6])
        assertEquals("vps.example:46000", masked[2])
        assertEquals("unified", masked[8])
        assertFalse(masked.any { it.contains("hashOne") })
        assertFalse(masked.any { it.contains("session-salt") })
    }

    @Test
    fun masksLongAndShortFlagSpellings() {
        val masked = CsqttSecrets.maskArgv(
            listOf("bin", "--vk", "abc,def", "--salt", "s", "-vk=inlineHash"),
        )
        assertEquals("bin", masked[0])
        assertEquals(CsqttSecrets.MASK, masked[2])
        assertEquals(CsqttSecrets.MASK, masked[4])
        assertEquals("-vk=***", masked[5])
    }

    @Test
    fun leavesNonSensitiveFlagsUntouched() {
        val argv = listOf(
            "bin", "-peer", "vps.example:46000", "-n", "10",
            "-client-ids", "8202606,6287487", "-obfs", "unified",
        )
        assertEquals(argv, CsqttSecrets.maskArgv(argv))
    }

    @Test
    fun describeArgvContainsNoHashes() {
        val description = CsqttSecrets.describeArgv(
            listOf("bin", "-peer", "vps.example:46000", "-vk", "verySecretHashValue"),
        )
        assertFalse(description.contains("verySecretHashValue"))
        assertTrue(description.contains(CsqttSecrets.MASK))
        assertTrue(description.contains("vps.example:46000"))
    }

    @Test
    fun describeCommandMasksPasswordFromEnvironment() {
        val command = CsqttCommand(
            argv = listOf("bin", "-peer", "vps.example:46000", "-vk", "hashValue12345"),
            env = linkedMapOf(
                CsqttDefaults.ENV_EVENTS to "1",
                CsqttDefaults.ENV_PASSWORD to "TopSecretPassword",
            ),
        )
        val description = CsqttSecrets.describeCommand(command)

        assertFalse(description.contains("TopSecretPassword"))
        assertFalse(description.contains("hashValue12345"))
        assertTrue(description.contains("CSQTT_PASSWORD=***"))
        assertTrue(description.contains("CSQTT_EVENTS=1"))
    }

    @Test
    fun describeConfigNeverPrintsPasswordOrHashes() {
        val description = CsqttSecrets.describeConfig(
            CsqttConfig(
                peerHost = "vps.example",
                password = "TopSecretPassword",
                vkHashes = listOf("hashSecretValue1", "hashSecretValue2"),
            ),
        )
        assertFalse(description.contains("TopSecretPassword"))
        assertFalse(description.contains("hashSecretValue1"))
        assertTrue(description.contains("peer=vps.example:46000"))
        assertTrue(description.contains("hashes=2"))
        assertTrue(description.contains("password=***"))
    }

    @Test
    fun redactsLongSecretsOnly() {
        val text = "connect failed: password=TopSecretPassword hash=abc"
        val redacted = CsqttSecrets.redact(text, "TopSecretPassword", "abc")
        assertEquals("connect failed: password=*** hash=abc", redacted)

        assertEquals("", CsqttSecrets.redact("", "TopSecretPassword"))
    }

    @Test
    fun redactsSecretsFromCollections() {
        val redacted = CsqttSecrets.redact(
            "argv: -vk hashOne,hashTwo -salt session-salt",
            listOf("hashOne,hashTwo", "session-salt"),
        )
        assertFalse(redacted.contains("hashOne"))
        assertFalse(redacted.contains("session-salt"))
    }

    @Test
    fun masksCaptchaAndBootstrapControlLines() {
        assertEquals(
            "CAPTCHA_RESULT|<скрыто>",
            CsqttSecrets.maskControlLine("CAPTCHA_RESULT|secret-token"),
        )
        assertEquals(
            "VK_JS_BOOTSTRAP:<скрыто>",
            CsqttSecrets.maskControlLine("VK_JS_BOOTSTRAP:eyJ0IjoxfQ=="),
        )
        assertEquals("PAUSE", CsqttSecrets.maskControlLine("  PAUSE  "))
        assertEquals(
            "CAPTCHA_RESULT|<скрыто>",
            CsqttControl.loggable("CAPTCHA_RESULT|token"),
        )
    }
}
