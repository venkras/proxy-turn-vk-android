package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttAuthDenialTest {

    @Test
    fun resolvesKnownReasons() {
        assertEquals(CsqttAuthDenial.WRONG_PASSWORD, CsqttAuthDenial.fromReason("wrong_password"))
        assertEquals(CsqttAuthDenial.EXPIRED, CsqttAuthDenial.fromReason("expired"))
        assertEquals(CsqttAuthDenial.DEACTIVATED, CsqttAuthDenial.fromReason("deactivated"))
        assertEquals(
            CsqttAuthDenial.PROTOCOL_MISMATCH,
            CsqttAuthDenial.fromReason("protocol_mismatch"),
        )
        assertEquals(
            CsqttAuthDenial.INVALID_WORKER_COUNT,
            CsqttAuthDenial.fromReason("invalid_worker_count"),
        )
    }

    @Test
    fun normalizesReasonCaseAndWhitespace() {
        assertEquals(CsqttAuthDenial.EXPIRED, CsqttAuthDenial.fromReason("  EXPIRED  "))
    }

    @Test
    fun unknownOrMissingReasonMapsToUnknown() {
        assertEquals(CsqttAuthDenial.UNKNOWN, CsqttAuthDenial.fromReason("something_else"))
        assertEquals(CsqttAuthDenial.UNKNOWN, CsqttAuthDenial.fromReason(""))
        assertEquals(CsqttAuthDenial.UNKNOWN, CsqttAuthDenial.fromReason(null))
    }

    @Test
    fun extractsReasonFromDeniedLine() {
        assertEquals(CsqttAuthDenial.EXPIRED, CsqttAuthDenial.fromLine("DENIED:expired"))
        assertEquals(
            CsqttAuthDenial.DEACTIVATED,
            CsqttAuthDenial.fromLine("[12:00:00] server said DENIED:deactivated."),
        )
        assertEquals(
            CsqttAuthDenial.WRONG_PASSWORD,
            CsqttAuthDenial.fromLine("DENIED:wrong_password (retry later)"),
        )
        assertNull(CsqttAuthDenial.fromLine("DENIED:"))
    }

    @Test
    fun recognizesFatalAuthReasons() {
        assertEquals(
            CsqttAuthDenial.WRONG_PASSWORD,
            CsqttAuthDenial.fromLine("FATAL_AUTH: invalid password"),
        )
        assertEquals(
            CsqttAuthDenial.EXPIRED,
            CsqttAuthDenial.fromLine("FATAL_AUTH: password expired"),
        )
        assertEquals(
            CsqttAuthDenial.DEACTIVATED,
            CsqttAuthDenial.fromLine("FATAL_AUTH: account deactivated"),
        )
        assertEquals(
            CsqttAuthDenial.INVALID_WORKER_COUNT,
            CsqttAuthDenial.fromLine("FATAL_AUTH: invalid_worker_count"),
        )
        assertEquals(
            CsqttAuthDenial.UNKNOWN,
            CsqttAuthDenial.fromLine("FATAL_AUTH: unexpected response"),
        )
    }

    @Test
    fun fatalProtocolAlwaysMeansVersionMismatch() {
        assertEquals(
            CsqttAuthDenial.PROTOCOL_MISMATCH,
            CsqttAuthDenial.fromLine("FATAL_PROTOCOL: server rejected handshake"),
        )
    }

    @Test
    fun ordinaryLinesAreNotDenials() {
        assertNull(CsqttAuthDenial.fromLine("worker 3 ready"))
        assertNull(CsqttAuthDenial.fromLine(""))
        assertNull(CsqttAuthDenial.fromLine("connection established"))
    }

    @Test
    fun exitCodeMessages() {
        assertNull(CsqttExitCodes.message(CsqttExitCodes.NORMAL))
        assertTrue(CsqttExitCodes.message(CsqttExitCodes.FATAL)!!.contains("ошибкой"))
        assertTrue(CsqttExitCodes.message(CsqttExitCodes.TUN_LOST)!!.contains("TUN"))
        assertEquals("Движок CSQTT завершился (код 2)", CsqttExitCodes.message(2))
    }
}
