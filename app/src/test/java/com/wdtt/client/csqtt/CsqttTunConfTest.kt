package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttTunConfTest {

    @Test
    fun parsesMinimalConfig() {
        val conf = CsqttTunConfParser.parse("TUNCONF:10.66.66.2:1.1.1.1")!!
        assertEquals("10.66.66.2", conf.clientIp)
        assertEquals(listOf("1.1.1.1"), conf.dns)
        assertEquals(0, conf.clientPort)
        assertEquals(CsqttDefaults.PROTOCOL_STREAM_V1, conf.protocol)
        assertNull(conf.workers)
        assertNull(conf.routingBase64)
        assertEquals(CsqttDefaults.DEFAULT_MTU, conf.mtu)
    }

    @Test
    fun parsesMultipleDnsAndPort() {
        val conf = CsqttTunConfParser.parse("TUNCONF:10.66.67.42:8.8.8.8,8.8.4.4:9000:stream-v1")!!
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), conf.dns)
        assertEquals(9000, conf.clientPort)
        assertEquals("8.8.8.8,8.8.4.4", conf.dnsCsv)
        assertFalse(conf.isStreamV2)
    }

    @Test
    fun parsesWorkersAndStreamV2() {
        val conf = CsqttTunConfParser.parse(
            "TUNCONF:10.0.0.2:1.1.1.1:0:stream-v2:workers=36",
        )!!
        assertEquals(CsqttDefaults.PROTOCOL_STREAM_V2, conf.protocol)
        assertEquals(36, conf.workers)
        assertTrue(conf.isStreamV2)
    }

    @Test
    fun ignoresPaddingField() {
        val conf = CsqttTunConfParser.parse(
            "TUNCONF:10.0.0.2:1.1.1.1:0:stream-v1:pad=AAAAAAAA",
        )!!
        assertEquals("10.0.0.2", conf.clientIp)
        assertNull(conf.workers)
        assertNull(conf.routingBase64)
    }

    @Test
    fun keepsRoutingPayload() {
        val conf = CsqttTunConfParser.parse(
            "TUNCONF:10.0.0.2:1.1.1.1:0:stream-v1:eyJ3aGl0ZSI6dHJ1ZX0",
        )!!
        assertEquals("eyJ3aGl0ZSI6dHJ1ZX0", conf.routingBase64)
    }

    @Test
    fun outOfRangeWorkersIsIgnoredButConfigIsValid() {
        val conf = CsqttTunConfParser.parse(
            "TUNCONF:10.0.0.2:1.1.1.1:0:stream-v1:workers=101",
        )!!
        assertNull(conf.workers)
        assertNull(CsqttTunConfParser.parse("TUNCONF:10.0.0.2:1.1.1.1:0:stream-v1:workers=abc")!!.workers)
    }

    @Test
    fun parsesFromEventAndLogLine() {
        val event = CsqttEvent.Config("TUNCONF:10.66.66.2:1.1.1.1:0:stream-v1")
        assertEquals("10.66.66.2", CsqttTunConfParser.parse(event)!!.clientIp)
        assertEquals(
            "10.66.66.2",
            CsqttTunConfParser.parse("2026/11/18 12:00:00 TUNCONF:10.66.66.2:1.1.1.1")!!.clientIp,
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(CsqttTunConfParser.parse(""))
        assertNull(CsqttTunConfParser.parse("TUNCONF:"))
        assertNull(CsqttTunConfParser.parse("TUNCONF:10.66.66.2"))
        assertNull(CsqttTunConfParser.parse("TUNCONF:999.66.66.2:1.1.1.1"))
        assertNull(CsqttTunConfParser.parse("TUNCONF:10.66.66.2:not-an-ip"))
        assertNull(CsqttTunConfParser.parse("TUNCONF:10.66.66.2:"))
        assertNull(CsqttTunConfParser.parse("hello world"))
        assertNull(CsqttTunConfParser.parse("NOCONF"))
    }

    @Test
    fun zeroClientPortAndNonNumericPortFallBackToZero() {
        assertEquals(
            0,
            CsqttTunConfParser.parse("TUNCONF:10.0.0.2:1.1.1.1:0")!!.clientPort,
        )
        assertEquals(
            0,
            CsqttTunConfParser.parse("TUNCONF:10.0.0.2:1.1.1.1:abc")!!.clientPort,
        )
    }

    @Test
    fun filtersInvalidDnsEntries() {
        val conf = CsqttTunConfParser.parse(
            "TUNCONF:10.0.0.2:1.1.1.1,bogus,8.8.8.8",
        )!!
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), conf.dns)
    }

    @Test
    fun dnsOrDefaultFallsBackOnlyWhenListIsEmpty() {
        assertEquals(CsqttDefaults.DEFAULT_DNS, CsqttTunConf(clientIp = "10.0.0.2").dnsOrDefault)
        assertEquals(
            listOf("9.9.9.9"),
            CsqttTunConf(clientIp = "10.0.0.2", dns = listOf("9.9.9.9")).dnsOrDefault,
        )
    }

    @Test
    fun validatesIpv4Strictly() {
        assertTrue(CsqttTunConfParser.isIpv4("10.66.66.2"))
        assertTrue(CsqttTunConfParser.isIpv4("0.0.0.0"))
        assertTrue(CsqttTunConfParser.isIpv4("255.255.255.255"))
        assertTrue(CsqttTunConfParser.isIpv4(" 10.66.66.2 "))
        assertFalse(CsqttTunConfParser.isIpv4("256.1.1.1"))
        assertFalse(CsqttTunConfParser.isIpv4("1.2.3"))
        assertFalse(CsqttTunConfParser.isIpv4("1.2.3.4.5"))
        assertFalse(CsqttTunConfParser.isIpv4("1.2.3.a"))
        assertFalse(CsqttTunConfParser.isIpv4(""))
        assertFalse(CsqttTunConfParser.isIpv4("10.66.66.2:46000"))
    }
}
