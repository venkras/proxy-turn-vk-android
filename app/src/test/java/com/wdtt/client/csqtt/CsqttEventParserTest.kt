package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttEventParserTest {

    @Test
    fun parsesStats() {
        val event = CsqttEventParser.parse(
            "__CSQTT_EVENT__|STATS|{\"active\":3,\"bytes_up\":120,\"bytes_down\":450}",
        ) as CsqttEvent.Stats

        assertEquals(3, event.active)
        assertEquals(120L, event.bytesUp)
        assertEquals(450L, event.bytesDown)
    }

    @Test
    fun clampsNegativeStatsToZero() {
        val event = CsqttEventParser.parse(
            "__CSQTT_EVENT__|STATS|{\"active\":-9,\"bytes_up\":-1,\"bytes_down\":-2}",
        ) as CsqttEvent.Stats

        assertEquals(0, event.active)
        assertEquals(0L, event.bytesUp)
        assertEquals(0L, event.bytesDown)
    }

    @Test
    fun parsesFatalError() {
        val event = CsqttEventParser.parse(
            "__CSQTT_EVENT__|ERROR|{\"code\":\"authentication_rejected\"," +
                "\"message\":\"FATAL_AUTH: invalid password\",\"fatal\":true}",
        ) as CsqttEvent.Error

        assertEquals("authentication_rejected", event.code)
        assertEquals("FATAL_AUTH: invalid password", event.message)
        assertTrue(event.fatal)
    }

    @Test
    fun parsesProcessAndLifecycle() {
        val process = CsqttEventParser.parse("__CSQTT_EVENT__|PROCESS|{\"pid\":4242}")
        assertEquals(CsqttEvent.Process(4242), process)

        assertEquals(CsqttEvent.Started(""), CsqttEventParser.parse("__CSQTT_EVENT__|STARTED|{}"))
        assertEquals(CsqttEvent.Stopped(""), CsqttEventParser.parse("__CSQTT_EVENT__|STOPPED|{}"))
        assertEquals(CsqttEvent.TunLost, CsqttEventParser.parse("__CSQTT_EVENT__|TUN_LOST|{}"))
        assertEquals(
            CsqttEvent.ActiveZero("idle"),
            CsqttEventParser.parse("__CSQTT_EVENT__|ACTIVE_ZERO|{\"message\":\"idle\"}"),
        )
    }

    @Test
    fun rejectsProcessWithoutPid() {
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|PROCESS|{\"pid\":0}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|PROCESS|{}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|PROCESS|{\"pid\":\"42\"}"))
    }

    @Test
    fun parsesReadyAndDtlsWithBoundedWorkerIndex() {
        val ready = CsqttEventParser.parse("__CSQTT_EVENT__|READY|{\"worker\":7}") as CsqttEvent.Ready
        assertEquals(7, ready.worker)
        assertEquals(0, ready.message.length)

        val clamped = CsqttEventParser.parse("__CSQTT_EVENT__|READY|{\"worker\":900}") as CsqttEvent.Ready
        assertEquals(CsqttDefaults.MAX_WORKERS, clamped.worker)

        val dtls = CsqttEventParser.parse(
            "__CSQTT_EVENT__|DTLS|{\"worker\":2,\"message\":\"handshake ok\"}",
        ) as CsqttEvent.Dtls
        assertEquals(2, dtls.worker)
        assertEquals("handshake ok", dtls.message)
    }

    @Test
    fun parsesWorkersWithinEngineLimits() {
        assertEquals(36, (CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":36}") as CsqttEvent.Workers).workers)
        assertEquals(4, (CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":4}") as CsqttEvent.Workers).workers)
        assertEquals(0, (CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":0}") as CsqttEvent.Workers).workers)
        assertEquals(100, (CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":100}") as CsqttEvent.Workers).workers)

        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":2}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":101}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|WORKERS|{\"workers\":-1}"))
    }

    @Test
    fun parsesCallUnavailableWithoutEmptyHash() {
        val event = CsqttEventParser.parse(
            "__CSQTT_EVENT__|CALL_UNAVAILABLE|{\"hash\":\"dead-hash\",\"code\":951}",
        ) as CsqttEvent.CallUnavailable

        assertEquals("dead-hash", event.hash)
        assertEquals(CsqttDefaults.CALL_NOT_FOUND_CODE, event.code)

        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|CALL_UNAVAILABLE|{\"code\":951}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|CALL_UNAVAILABLE|{\"hash\":\"  \"}"))
    }

    @Test
    fun serverRestartRequiresPanelSource() {
        assertTrue(
            CsqttEventParser.parse(
                "__CSQTT_EVENT__|SERVER_RESTART|{\"source\":\"panel\"}",
            ) is CsqttEvent.ServerRestart,
        )
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|SERVER_RESTART|{\"source\":\"network\"}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|SERVER_RESTART|{}"))
    }

    @Test
    fun parsesConfigAndRouting() {
        assertEquals(
            CsqttEvent.Config("TUNCONF:10.66.66.2:1.1.1.1"),
            CsqttEventParser.parse(
                "__CSQTT_EVENT__|CONFIG|{\"config\":\"TUNCONF:10.66.66.2:1.1.1.1\"}",
            ),
        )
        assertTrue(
            CsqttEventParser.parse(
                "__CSQTT_EVENT__|ROUTING|{\"routing\":\"{\\\"white\\\":true}\"}",
            ) is CsqttEvent.Routing,
        )
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|CONFIG|{\"config\":\"\"}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|ROUTING|{\"routing\":\"  \"}"))
    }

    @Test
    fun parsesCaptchaEvents() {
        val request = CsqttEventParser.parse(
            "__CSQTT_EVENT__|CAPTCHA_REQUEST|{\"mode\":\"wv\"," +
                "\"redirect_uri\":\"https://id.vk.ru/captcha?x=1\",\"session_token\":\"sess\"}",
        ) as CsqttEvent.CaptchaRequest
        assertEquals("wv", request.mode)
        assertEquals("https://id.vk.ru/captcha?x=1", request.redirectUri)
        assertEquals("sess", request.sessionToken)

        val done = CsqttEventParser.parse(
            "__CSQTT_EVENT__|CAPTCHA_DONE|{\"success\":false,\"error\":\"timeout\"}",
        ) as CsqttEvent.CaptchaDone
        assertEquals(false, done.success)
        assertEquals("timeout", done.error)

        val doneOk = CsqttEventParser.parse(
            "__CSQTT_EVENT__|CAPTCHA_DONE|{\"success\":true}",
        ) as CsqttEvent.CaptchaDone
        assertEquals(true, doneOk.success)
        assertNull(doneOk.error)
    }

    @Test
    fun parsesCredentialProgressOnly() {
        assertEquals(
            CsqttEvent.Progress("credentials"),
            CsqttEventParser.parse("__CSQTT_EVENT__|PROGRESS|{\"kind\":\"credentials\"}"),
        )
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|PROGRESS|{\"kind\":\"unknown\"}"))
    }

    @Test
    fun toleratesTimestampPrefixes() {
        val stats = CsqttEventParser.parse(
            "[12:34:56.789] __CSQTT_EVENT__|STATS|{\"active\":1,\"bytes_up\":5,\"bytes_down\":6}",
        ) as CsqttEvent.Stats
        assertEquals(1, stats.active)

        val ready = CsqttEventParser.parse(
            "2026/11/18 12:34:56 __CSQTT_EVENT__|READY|{\"worker\":1}",
        )
        assertEquals(CsqttEvent.Ready(1), ready)
    }

    @Test
    fun rejectsForeignPrefixesBeforeEvent() {
        assertNull(CsqttEventParser.parse("some log text __CSQTT_EVENT__|READY|{}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|READY|{} trailing garbage"))
    }

    @Test
    fun handlesMalformedAndUnknownEvents() {
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|CAPTCHA_DONE|invalid"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|READY|"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|READY|null"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|READY|[]"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|READY|{"))
        assertNull(CsqttEventParser.parse("regular log line"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|UNKNOWN|{}"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|"))
        assertNull(CsqttEventParser.parse("__CSQTT_EVENT__||{}"))
    }

    @Test
    fun truncatedPayloadsNeverThrow() {
        val payloads = listOf(
            "{\"active\":3,\"bytes_up\":",
            "{\"code\":\"AUTH\"",
            "{\"message\":\"unterminated",
            "{\"message\":\"bad\\",
            "{\"message\":\"\\u00",
            "{}}}}",
        )
        for (payload in payloads) {
            for (type in listOf("STATS", "ERROR", "CONFIG", "READY")) {
                assertNull(CsqttEventParser.parse("__CSQTT_EVENT__|$type|$payload"))
            }
        }
    }

    @Test
    fun wrongJsonTypesDoNotInjectState() {
        val stats = CsqttEventParser.parse(
            "__CSQTT_EVENT__|STATS|{\"active\":\"3\",\"bytes_up\":null,\"bytes_down\":true}",
        ) as CsqttEvent.Stats
        assertEquals(0, stats.active)
        assertEquals(0L, stats.bytesUp)
        assertEquals(0L, stats.bytesDown)

        val error = CsqttEventParser.parse(
            "__CSQTT_EVENT__|ERROR|{\"code\":1,\"message\":false,\"fatal\":\"true\"}",
        ) as CsqttEvent.Error
        assertEquals("", error.code)
        assertEquals("", error.message)
        assertEquals(false, error.fatal)
    }

    @Test
    fun decodesJsonEscapes() {
        val event = CsqttEventParser.parse(
            "__CSQTT_EVENT__|ERROR|{\"message\":\"\\u041e\\u0448\\u0438\\u0431\\u043a\\u0430\\n\"}",
        ) as CsqttEvent.Error
        assertEquals("Ошибка\n", event.message)
    }
}
