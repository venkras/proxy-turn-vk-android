package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttOutputParserTest {

    @Test
    fun parsesTunConfFromRawLine() {
        val result = CsqttOutputParser.parse(
            "TUNCONF:10.66.66.2:1.1.1.1:0:stream-v1",
        ) as CsqttOutput.TunConf

        assertEquals("10.66.66.2", result.conf.clientIp)
        assertEquals(listOf("1.1.1.1"), result.conf.dns)
    }

    @Test
    fun convertsConfigEventIntoTunConf() {
        val result = CsqttOutputParser.parse(
            "__CSQTT_EVENT__|CONFIG|{\"config\":\"TUNCONF:10.0.0.2:8.8.8.8:0:stream-v2\"}",
        ) as CsqttOutput.TunConf

        assertEquals("10.0.0.2", result.conf.clientIp)
        assertTrue(result.conf.isStreamV2)
    }

    @Test
    fun keepsConfigEventWhenTunConfIsInvalid() {
        val result = CsqttOutputParser.parse(
            "__CSQTT_EVENT__|CONFIG|{\"config\":\"garbage\"}",
        )

        assertEquals(CsqttEvent.Config("garbage"), (result as CsqttOutput.Event).event)
    }

    @Test
    fun keepsNonConfigEventsAsEvents() {
        val result = CsqttOutputParser.parse("__CSQTT_EVENT__|STATS|{\"active\":2}")

        val stats = (result as CsqttOutput.Event).event as CsqttEvent.Stats
        assertEquals(2, stats.active)
    }

    @Test
    fun parsesThreePartCaptchaRequest() {
        val result = CsqttOutputParser.parse(
            "CAPTCHA_SOLVE|wv|https://id.vk.ru/captcha?x=1|session-token",
        ) as CsqttOutput.CaptchaRequest

        assertEquals("wv", result.mode)
        assertEquals("https://id.vk.ru/captcha?x=1", result.redirectUri)
        assertEquals("session-token", result.sessionToken)
    }

    @Test
    fun parsesLegacyTwoPartCaptchaRequest() {
        val result = CsqttOutputParser.parse(
            "CAPTCHA_SOLVE|https://id.vk.ru/captcha|session-token",
        ) as CsqttOutput.CaptchaRequest

        assertEquals(CsqttDefaults.CAPTCHA_MODE_AUTO, result.mode)
        assertEquals("https://id.vk.ru/captcha", result.redirectUri)
        assertEquals("session-token", result.sessionToken)
    }

    @Test
    fun keepsPipeCharactersInsideCaptchaToken() {
        val result = CsqttOutputParser.parse(
            "CAPTCHA_SOLVE|rjs|https://id.vk.ru/captcha|part|one",
        ) as CsqttOutput.CaptchaRequest

        assertEquals("rjs", result.mode)
        assertEquals("part|one", result.sessionToken)
    }

    @Test
    fun ignoresIncompleteCaptchaRequest() {
        val result = CsqttOutputParser.parse("CAPTCHA_SOLVE|only-one-field")
        assertTrue(result is CsqttOutput.Log)
    }

    @Test
    fun parsesDeniedAuthReason() {
        val result = CsqttOutputParser.parse("DENIED:expired") as CsqttOutput.AuthDenied

        assertEquals(CsqttAuthDenial.EXPIRED, result.denial)
        assertEquals("DENIED:expired", result.line)
    }

    @Test
    fun parsesFatalAuthAndProtocolLines() {
        val wrongPassword = CsqttOutputParser.parse(
            "FATAL_AUTH: invalid password",
        ) as CsqttOutput.AuthDenied
        assertEquals(CsqttAuthDenial.WRONG_PASSWORD, wrongPassword.denial)

        val protocol = CsqttOutputParser.parse(
            "FATAL_PROTOCOL: different protocol versions",
        ) as CsqttOutput.AuthDenied
        assertEquals(CsqttAuthDenial.PROTOCOL_MISMATCH, protocol.denial)
    }

    @Test
    fun fallsBackToLogForOrdinaryLines() {
        val result = CsqttOutputParser.parse("  worker started  ") as CsqttOutput.Log

        assertEquals("worker started", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun marksStderrAsError() {
        val result = CsqttOutputParser.parse("some diagnostics", fromStderr = true) as CsqttOutput.Log

        assertTrue(result.isError)
        assertEquals("some diagnostics", result.text)
    }

    @Test
    fun marksErrorKeywordsAsError() {
        val result = CsqttOutputParser.parse("tunnel error: handshake timeout") as CsqttOutput.Log

        assertTrue(result.isError)
    }

    @Test
    fun invalidTunConfFallsBackToLog() {
        val result = CsqttOutputParser.parse("TUNCONF:not-an-ip:1.1.1.1") as CsqttOutput.Log

        assertEquals("TUNCONF:not-an-ip:1.1.1.1", result.text)
        assertFalse(result.isError)
    }
}
