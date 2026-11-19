package com.wdtt.client.csqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttCommandLineTest {

    private val binary = "/data/app/lib/arm64/libcsqtt.so"
    private val socket = "wdtt_csqtt_123_456"
    private val password = "SuperSecretPassword1"

    private fun build(config: CsqttConfig): CsqttCommand =
        CsqttCommandLine.build(config, binary, socket, nativeLibraryDir = "/data/app/lib/arm64")

    private fun List<String>.valueOf(flag: String): String? {
        val index = indexOf(flag)
        if (index < 0 || index + 1 >= size) return null
        return this[index + 1]
    }

    private fun baseConfig(): CsqttConfig = CsqttConfig(
        peerHost = "vps.example",
        peerPort = 46000,
        password = password,
        vkHashes = listOf("hashOne", "hashTwo"),
    )

    @Test
    fun buildsMinimalContractArgv() {
        val argv = build(baseConfig()).argv

        assertEquals(binary, argv[0])
        assertEquals("vps.example:46000", argv.valueOf("-peer"))
        assertEquals("10", argv.valueOf("-n"))
        assertEquals("127.0.0.1:0", argv.valueOf("-listen"))
        assertEquals(socket, argv.valueOf("-tun-uds"))
        assertEquals("hashOne,hashTwo", argv.valueOf("-vk"))
        assertEquals(CsqttDefaults.VK_HASH_MODE_MANUAL, argv.valueOf("-vk-hash-mode"))
        assertEquals(CsqttDefaults.OBFUSCATION_UNIFIED, argv.valueOf("-obfs"))
        assertEquals(CsqttDefaults.TURN_TRANSPORT_UDP, argv.valueOf("-turn-transport"))
        assertEquals(CsqttDefaults.SRTP_MODE_AUTO, argv.valueOf("-srtp-mode"))
        assertEquals(CsqttDefaults.VK_AUTH_MODE_VKCALLS, argv.valueOf("-vk-auth-mode"))
        assertEquals(CsqttDefaults.CAPTCHA_MODE_AUTO, argv.valueOf("-captcha-mode"))
        assertEquals(CsqttDefaults.FINGERPRINT_CHROME, argv.valueOf("-fingerprint"))
    }

    @Test
    fun passwordIsNeverInArgvButIsInEnvironment() {
        val command = build(baseConfig())

        assertTrue(command.argv.none { it.contains(password) })
        assertEquals(password, command.env[CsqttDefaults.ENV_PASSWORD])
        assertEquals("1", command.env[CsqttDefaults.ENV_EVENTS])
        assertEquals("2", command.env[CsqttDefaults.ENV_RAYON_THREADS])
        assertEquals("/data/app/lib/arm64", command.env[CsqttDefaults.ENV_LD_LIBRARY_PATH])
        assertTrue(command.argv.none { it == "-password" })
    }

    @Test
    fun omitsLdLibraryPathAndPasswordWhenNotProvided() {
        val env = CsqttCommandLine.buildEnv(
            CsqttConfig(peerHost = "example.com", vkHashes = listOf("h"), password = ""),
        )
        assertNull(env[CsqttDefaults.ENV_LD_LIBRARY_PATH])
        assertNull(env[CsqttDefaults.ENV_PASSWORD])
        assertEquals("1", env[CsqttDefaults.ENV_EVENTS])
    }

    @Test
    fun defaultPortIsSubstitutedWhenMissing() {
        val command = build(baseConfig().copy(peerPort = 0))
        assertEquals("vps.example:46000", command.argv.valueOf("-peer"))
    }

    @Test
    fun ipv6HostIsWrappedInBrackets() {
        val argv = build(baseConfig().copy(peerHost = "2001:db8::1")).argv
        assertEquals("[2001:db8::1]:46000", argv.valueOf("-peer"))
    }

    @Test
    fun normalizesWorkersLikeEngine() {
        assertEquals("100", build(baseConfig().copy(workers = 1_000)).argv.valueOf("-n"))
        assertEquals("10", build(baseConfig().copy(workers = 7)).argv.valueOf("-n"))
        assertEquals("5", build(baseConfig().copy(workers = 5)).argv.valueOf("-n"))
        assertEquals("40", build(baseConfig().copy(workers = 48)).argv.valueOf("-n"))
        assertEquals("100", build(baseConfig().copy(workers = 100)).argv.valueOf("-n"))
    }

    @Test
    fun exactWorkersKeepsRawCountAndAddsFlag() {
        val argv = build(baseConfig().copy(workers = 4, exactWorkers = true)).argv
        assertEquals("4", argv.valueOf("-n"))
        assertTrue(argv.contains("--exact-workers"))

        val clamped = build(baseConfig().copy(workers = 1, exactWorkers = true)).argv
        assertEquals("3", clamped.valueOf("-n"))
    }

    @Test
    fun truncatesTooManyHashesToEngineLimit() {
        val hashes = (1..9).map { "hash$it" }
        val argv = build(baseConfig().copy(vkHashes = hashes)).argv
        assertEquals(hashes.take(CsqttDefaults.MAX_VK_HASHES).joinToString(","), argv.valueOf("-vk"))
    }

    @Test
    fun deduplicatesAndSplitsHashesFromSingleString() {
        val argv = build(baseConfig().copy(vkHashes = listOf("a1, a2", "a1", "a3"))).argv
        assertEquals("a1,a2,a3", argv.valueOf("-vk"))
    }

    @Test
    fun autoJsModeOmitsVkAndKeepsAuthMode() {
        val config = baseConfig().copy(
            vkHashes = emptyList(),
            vkHashMode = CsqttDefaults.VK_HASH_MODE_AUTO_JS,
            vkAuthMode = CsqttDefaults.VK_AUTH_MODE_AUTO_JS,
            vkAccessToken = "vk-token",
        )
        val argv = build(config).argv

        assertFalse(argv.contains("-vk"))
        assertEquals(CsqttDefaults.VK_HASH_MODE_AUTO_JS, argv.valueOf("-vk-hash-mode"))
        assertEquals(CsqttDefaults.VK_AUTH_MODE_AUTO_JS, argv.valueOf("-vk-auth-mode"))
    }

    @Test
    fun autoJsAuthIsDowngradedWhenHashesAreManual() {
        val argv = build(
            baseConfig().copy(vkAuthMode = CsqttDefaults.VK_AUTH_MODE_AUTO_JS),
        ).argv
        assertEquals(CsqttDefaults.VK_AUTH_MODE_VKCALLS, argv.valueOf("-vk-auth-mode"))
    }

    @Test
    fun passesGenerationSaltAndCustomTurnIpsOnlyWhenSet() {
        val without = build(baseConfig()).argv
        assertFalse(without.contains("-gen"))
        assertFalse(without.contains("-salt"))
        assertFalse(without.contains("-custom-turn-ips"))

        val with = build(
            baseConfig().copy(
                generation = 42L,
                salt = "salt-value",
                customTurnIps = "1.2.3.4:3478,5.6.7.8",
            ),
        ).argv
        assertEquals("42", with.valueOf("-gen"))
        assertEquals("salt-value", with.valueOf("-salt"))
        assertEquals("1.2.3.4:3478,5.6.7.8", with.valueOf("-custom-turn-ips"))
    }

    @Test
    fun normalizesModeValues() {
        val argv = build(
            baseConfig().copy(
                obfs = "VIDEO",
                turnTransport = "tcp",
                srtpMode = "wrap-srtp",
                fingerprint = "firefox",
                captchaMode = "rjs",
            ),
        ).argv
        assertEquals("video", argv.valueOf("-obfs"))
        assertEquals("tcp_tls", argv.valueOf("-turn-transport"))
        assertEquals("wrap-srtp", argv.valueOf("-srtp-mode"))
        assertEquals("firefox", argv.valueOf("-fingerprint"))
        assertEquals("rjs", argv.valueOf("-captcha-mode"))

        val fallback = build(
            baseConfig().copy(obfs = "bogus", turnTransport = "bogus", srtpMode = "bogus", fingerprint = ""),
        ).argv
        assertEquals("unified", fallback.valueOf("-obfs"))
        assertEquals("udp", fallback.valueOf("-turn-transport"))
        assertEquals("auto", fallback.valueOf("-srtp-mode"))
        assertEquals("chrome", fallback.valueOf("-fingerprint"))
    }

    @Test
    fun buildsDomainRoutingBlock() {
        val argv = build(
            baseConfig().copy(
                domainRouting = true,
                excludeRuZones = true,
                templateDomains = true,
                directDomains = "example.org,example.net",
                domainsFiles = listOf("/data/user/0/app/files/domains.txt"),
            ),
        ).argv

        assertTrue(argv.contains("--domain-routing"))
        assertTrue(argv.contains("--exclude-ru-zones"))
        assertTrue(argv.contains("--template-domains"))
        assertEquals("example.org,example.net", argv.valueOf("--direct-domains"))
        assertEquals("/data/user/0/app/files/domains.txt", argv.valueOf("--domains-file"))
        assertFalse(argv.contains("--domain-whitelist"))
    }

    @Test
    fun whitelistModeSuppressesExcludeAndTemplate() {
        val argv = build(
            baseConfig().copy(
                domainRouting = true,
                domainWhitelist = true,
                excludeRuZones = true,
                templateDomains = true,
            ),
        ).argv

        assertTrue(argv.contains("--domain-routing"))
        assertTrue(argv.contains("--domain-whitelist"))
        assertFalse(argv.contains("--exclude-ru-zones"))
        assertFalse(argv.contains("--template-domains"))
    }

    @Test
    fun directDomainsAloneEnableDomainRouting() {
        val argv = build(baseConfig().copy(directDomains = "example.org")).argv
        assertTrue(argv.contains("--domain-routing"))
        assertEquals("example.org", argv.valueOf("--direct-domains"))
    }

    @Test
    fun buildsIpRoutingBlock() {
        val argv = build(
            baseConfig().copy(directIps = "10.0.0.0/8,192.168.0.0/16", ipWhitelist = true),
        ).argv

        assertTrue(argv.contains("--ip-routing"))
        assertTrue(argv.contains("--ip-whitelist"))
        assertEquals("10.0.0.0/8,192.168.0.0/16", argv.valueOf("--direct-ips"))
        assertFalse(build(baseConfig()).argv.contains("--ip-routing"))
    }

    @Test
    fun passesClientIdsRuntimeThreadsAndLocalPort() {
        val argv = build(
            baseConfig().copy(
                clientIds = listOf("8202606", "6287487"),
                runtimeThreads = 2,
                localPort = 9000,
            ),
        ).argv

        assertEquals("8202606,6287487", argv.valueOf("-client-ids"))
        assertEquals("2", argv.valueOf("-runtime-threads"))
        assertEquals("127.0.0.1:9000", argv.valueOf("-listen"))

        val without = build(baseConfig()).argv
        assertFalse(without.contains("-client-ids"))
        assertFalse(without.contains("-runtime-threads"))
    }

    @Test
    fun emptyFingerprintFallsBackToChrome() {
        val argv = build(baseConfig().copy(fingerprint = "")).argv
        assertEquals("chrome", argv.valueOf("-fingerprint"))
    }

    @Test
    fun reportsConfigurationProblems() {
        val empty = CsqttConfig()
        val problems = empty.problems()
        assertTrue(problems.contains(CsqttConfigProblem.PEER_MISSING))
        assertTrue(problems.contains(CsqttConfigProblem.PASSWORD_MISSING))
        assertTrue(problems.contains(CsqttConfigProblem.HASHES_MISSING))

        val autoJs = CsqttConfig(
            peerHost = "vps.example",
            password = password,
            vkHashMode = CsqttDefaults.VK_HASH_MODE_AUTO_JS,
        )
        assertTrue(autoJs.problems().contains(CsqttConfigProblem.AUTO_JS_TOKEN_MISSING))

        val valid = baseConfig()
        assertTrue(valid.problems().isEmpty())

        val tooMany = baseConfig().copy(vkHashes = (1..7).map { "hash$it" })
        assertTrue(tooMany.problems().contains(CsqttConfigProblem.HASHES_LIMIT))

        val nonFatalDomain = baseConfig().copy(directDomains = "example.org")
        val domainProblems = nonFatalDomain.problems()
        assertEquals(listOf(CsqttConfigProblem.DOMAIN_ROUTING_DISABLED), domainProblems)
        assertFalse(domainProblems.single().isFatal)
    }

    @Test
    fun validatesLaunchArguments() {
        val problems = CsqttCommandLine.problems(baseConfig(), binaryPath = "", sockName = "")
        assertTrue(problems.contains(CsqttConfigProblem.BINARY_MISSING))
        assertTrue(problems.contains(CsqttConfigProblem.SOCKET_MISSING))
        assertFalse(CsqttCommandLine.problems(baseConfig(), binary, socket).any { it.isFatal })
    }

    @Test
    fun rejectsPasswordWithLineBreak() {
        val problems = baseConfig().copy(password = "line1\nline2").problems()
        assertTrue(problems.contains(CsqttConfigProblem.PASSWORD_INVALID))
    }

    @Test
    fun maskedLogDescriptionContainsNoSecrets() {
        val command = build(
            baseConfig().copy(
                vkHashes = listOf("hashSecretValue1", "hashSecretValue2"),
                salt = "saltSecretValue",
                generation = 7L,
            ),
        )
        val described = command.describe()

        assertFalse(described.contains(password))
        assertFalse(described.contains("hashSecretValue1"))
        assertFalse(described.contains("hashSecretValue2"))
        assertFalse(described.contains("saltSecretValue"))
        assertTrue(described.contains(CsqttSecrets.MASK))
    }

    @Test
    fun buildsControlLinesForEngineStdin() {
        assertEquals("CAPTCHA_RESULT|token-1", CsqttControl.captchaResult(" token-1 "))
        assertEquals("VK_JS_BOOTSTRAP:eyJ0IjoxfQ==", CsqttControl.vkJsBootstrap("eyJ0IjoxfQ== "))
        assertEquals("UPDATE_DOMAINS|{\"a\":1}", CsqttControl.updateDomains(" {\"a\":1} "))
        assertEquals("PAUSE", CsqttControl.PAUSE)
        assertEquals("STOP", CsqttControl.STOP)
    }
}
