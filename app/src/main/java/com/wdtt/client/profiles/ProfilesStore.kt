package com.wdtt.client

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.wdtt.client.csqtt.CsqttDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

data class ConnectionProfile(
    val id: String,
    val name: String,
    val peer: String,
    val vkHashes: String,
    val workersPerHash: Int,
    val listenPort: Int,
    val password: String,
    val trafficMb: Double = 0.0,
    val groupId: String = "",
    val useGlobalHashes: Boolean = vkHashes.isBlank(),
    /** Ключ диагностики сервера (dk= из ссылки бота), пусто — нет. */
    val diagKey: String = "",
    /** Протокол сервера: wdtt (по умолчанию) или csqtt. */
    val serverProtocol: String = SettingsStore.SERVER_PROTOCOL_WDTT,
    // ═══ Параметры CSQTT (используются только при serverProtocol = csqtt) ═══
    val csqttObfs: String = "unified",
    val csqttTransport: String = "udp",
    val csqttSrtp: String = "auto",
    val csqttFingerprint: String = "chrome",
    val csqttHashMode: String = "manual",
    /** VK access token для auto_js; хранится в защищённом хранилище. */
    val csqttAccessToken: String = "",
    val csqttClientIds: String = "",
    val csqttCustomTurnIps: String = "",
    val csqttDomainRouting: Boolean = false,
    val csqttExcludeRuZones: Boolean = false,
    val csqttDirectDomains: String = "",
    val csqttDirectIps: String = ""
) {
    val isCsqtt: Boolean
        get() = SettingsStore.normalizeServerProtocol(serverProtocol) == SettingsStore.SERVER_PROTOCOL_CSQTT
}

data class ProfileGroup(
    val id: String,
    val name: String
)

data class ProfileSubscription(
    val id: String,
    val name: String,
    val url: String,
    val description: String = "",
    val groupId: String = "",
    val trafficLimitMb: Double = 0.0,
    val remoteTrafficUsedMb: Double = 0.0,
    val remoteUpdatedAt: String = "",
    val lastSyncAt: Long = 0L,
    val lastSyncError: String = ""
)

class ProfilesStore(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)

    companion object {
        private val Context.dataStore by preferencesDataStore("profiles")
        private const val IDS_KEY = "profiles_ids"
        private fun idsKey() = stringPreferencesKey(IDS_KEY)

        private fun nameKey(id: String) = stringPreferencesKey("profile_name_$id")
        private fun peerKey(id: String) = stringPreferencesKey("profile_peer_$id")
        private fun hashesKey(id: String) = stringPreferencesKey("profile_hashes_$id")
        private fun workersKey(id: String) = intPreferencesKey("profile_workers_$id")
        private fun portKey(id: String) = intPreferencesKey("profile_port_$id")
        private fun passKey(id: String) = stringPreferencesKey("profile_pass_enc_$id")
        private fun useGlobalHashesKey(id: String) = booleanPreferencesKey("profile_use_global_hashes_$id")
        private fun trafficKey(id: String) = androidx.datastore.preferences.core.doublePreferencesKey("profile_traffic_$id")
        private fun groupIdKey(id: String) = stringPreferencesKey("profile_group_id_$id")
        private fun diagKeyKey(id: String) = stringPreferencesKey("profile_diag_key_enc_$id")
        private fun protocolKey(id: String) = stringPreferencesKey("profile_protocol_$id")
        private fun csqttObfsKey(id: String) = stringPreferencesKey("profile_csqtt_obfs_$id")
        private fun csqttTransportKey(id: String) = stringPreferencesKey("profile_csqtt_transport_$id")
        private fun csqttSrtpKey(id: String) = stringPreferencesKey("profile_csqtt_srtp_$id")
        private fun csqttFingerprintKey(id: String) = stringPreferencesKey("profile_csqtt_fingerprint_$id")
        private fun csqttHashModeKey(id: String) = stringPreferencesKey("profile_csqtt_hash_mode_$id")
        private fun csqttTokenKey(id: String) = stringPreferencesKey("profile_csqtt_token_enc_$id")
        private fun csqttClientIdsKey(id: String) = stringPreferencesKey("profile_csqtt_client_ids_$id")
        private fun csqttTurnIpsKey(id: String) = stringPreferencesKey("profile_csqtt_turn_ips_$id")
        private fun csqttDomainRoutingKey(id: String) = booleanPreferencesKey("profile_csqtt_domain_routing_$id")
        private fun csqttExcludeRuKey(id: String) = booleanPreferencesKey("profile_csqtt_exclude_ru_$id")
        private fun csqttDirectDomainsKey(id: String) = stringPreferencesKey("profile_csqtt_direct_domains_$id")
        private fun csqttDirectIpsKey(id: String) = stringPreferencesKey("profile_csqtt_direct_ips_$id")

        private const val GROUPS_IDS_KEY = "groups_ids"
        private fun groupsIdsKey() = stringPreferencesKey(GROUPS_IDS_KEY)
        private fun groupNameKey(id: String) = stringPreferencesKey("group_name_$id")

        private const val SUBSCRIPTIONS_IDS_KEY = "subscriptions_ids"
        private fun subscriptionsIdsKey() = stringPreferencesKey(SUBSCRIPTIONS_IDS_KEY)
        private fun subNameKey(id: String) = stringPreferencesKey("sub_name_$id")
        private fun subUrlKey(id: String) = stringPreferencesKey("sub_url_$id")
        private fun subDescKey(id: String) = stringPreferencesKey("sub_desc_$id")
        private fun subGroupIdKey(id: String) = stringPreferencesKey("sub_group_id_$id")
        private fun subTrafficLimitKey(id: String) = androidx.datastore.preferences.core.doublePreferencesKey("sub_traffic_limit_$id")
        private fun subRemoteTrafficKey(id: String) = androidx.datastore.preferences.core.doublePreferencesKey("sub_remote_traffic_$id")
        private fun subRemoteUpdatedKey(id: String) = stringPreferencesKey("sub_remote_updated_$id")
        private fun subLastSyncKey(id: String) = androidx.datastore.preferences.core.longPreferencesKey("sub_last_sync_$id")
        private fun subLastErrorKey(id: String) = stringPreferencesKey("sub_last_error_$id")
    }

    private val dataStore = appContext.dataStore
    private val secureStore = SecureStringStore(appContext)

    val profiles: Flow<List<ConnectionProfile>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val idsRaw = prefs[idsKey()] ?: ""
            if (idsRaw.isBlank()) return@map emptyList()
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val list = mutableListOf<ConnectionProfile>()
            for (id in ids) {
                val name = prefs[nameKey(id)] ?: ""
                val peer = prefs[peerKey(id)] ?: ""
                val hashes = prefs[hashesKey(id)] ?: ""
                val workers = prefs[workersKey(id)] ?: 9
                val port = prefs[portKey(id)] ?: 9000
                val enc = prefs[passKey(id)] ?: ""
                val pass = secureStore.decrypt(enc) ?: ""
                val traffic = prefs[trafficKey(id)] ?: 0.0
                val groupId = prefs[groupIdKey(id)] ?: ""
                val useGlobal = prefs[useGlobalHashesKey(id)] ?: true
                val diagKey = secureStore.decrypt(prefs[diagKeyKey(id)] ?: "") ?: ""
                list.add(
                    ConnectionProfile(
                        id = id,
                        name = name,
                        peer = peer,
                        vkHashes = hashes,
                        workersPerHash = workers,
                        listenPort = port,
                        password = pass,
                        trafficMb = traffic,
                        groupId = groupId,
                        useGlobalHashes = useGlobal,
                        diagKey = diagKey,
                        serverProtocol = SettingsStore.normalizeServerProtocol(prefs[protocolKey(id)]),
                        csqttObfs = prefs[csqttObfsKey(id)] ?: "unified",
                        csqttTransport = prefs[csqttTransportKey(id)] ?: "udp",
                        csqttSrtp = prefs[csqttSrtpKey(id)] ?: "auto",
                        csqttFingerprint = prefs[csqttFingerprintKey(id)] ?: "chrome",
                        csqttHashMode = prefs[csqttHashModeKey(id)] ?: "manual",
                        csqttAccessToken = secureStore.decrypt(prefs[csqttTokenKey(id)] ?: "") ?: "",
                        csqttClientIds = prefs[csqttClientIdsKey(id)] ?: "",
                        csqttCustomTurnIps = prefs[csqttTurnIpsKey(id)] ?: "",
                        csqttDomainRouting = prefs[csqttDomainRoutingKey(id)] ?: false,
                        csqttExcludeRuZones = prefs[csqttExcludeRuKey(id)] ?: false,
                        csqttDirectDomains = prefs[csqttDirectDomainsKey(id)] ?: "",
                        csqttDirectIps = prefs[csqttDirectIpsKey(id)] ?: "",
                    )
                )
            }
            list
        }

    val groups: Flow<List<ProfileGroup>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val idsRaw = prefs[groupsIdsKey()] ?: ""
            if (idsRaw.isBlank()) return@map emptyList()
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val list = mutableListOf<ProfileGroup>()
            for (id in ids) {
                val name = prefs[groupNameKey(id)] ?: ""
                list.add(ProfileGroup(id, name))
            }
            list
        }

    val subscriptions: Flow<List<ProfileSubscription>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val idsRaw = prefs[subscriptionsIdsKey()] ?: ""
            if (idsRaw.isBlank()) return@map emptyList()
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            ids.mapNotNull { id ->
                val name = prefs[subNameKey(id)] ?: return@mapNotNull null
                val url = prefs[subUrlKey(id)] ?: ""
                ProfileSubscription(
                    id = id,
                    name = name,
                    url = url,
                    description = prefs[subDescKey(id)] ?: "",
                    groupId = prefs[subGroupIdKey(id)] ?: "",
                    trafficLimitMb = prefs[subTrafficLimitKey(id)] ?: 0.0,
                    remoteTrafficUsedMb = prefs[subRemoteTrafficKey(id)] ?: 0.0,
                    remoteUpdatedAt = prefs[subRemoteUpdatedKey(id)] ?: "",
                    lastSyncAt = prefs[subLastSyncKey(id)] ?: 0L,
                    lastSyncError = prefs[subLastErrorKey(id)] ?: ""
                )
        }
    }

    suspend fun saveSubscription(sub: ProfileSubscription) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[subscriptionsIdsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!ids.contains(sub.id)) ids.add(sub.id)
            prefs[subscriptionsIdsKey()] = ids.joinToString(",")
            prefs[subNameKey(sub.id)] = sub.name
            prefs[subUrlKey(sub.id)] = sub.url
            prefs[subDescKey(sub.id)] = sub.description
            prefs[subGroupIdKey(sub.id)] = sub.groupId
            prefs[subTrafficLimitKey(sub.id)] = sub.trafficLimitMb
            prefs[subRemoteTrafficKey(sub.id)] = sub.remoteTrafficUsedMb
            prefs[subRemoteUpdatedKey(sub.id)] = sub.remoteUpdatedAt
            prefs[subLastSyncKey(sub.id)] = sub.lastSyncAt
            prefs[subLastErrorKey(sub.id)] = sub.lastSyncError
        }
    }

    suspend fun deleteSubscription(id: String) = withContext(Dispatchers.IO) {
        val sub = getSubscriptionOnce(id)
        if (sub?.groupId?.isNotBlank() == true) {
            deleteProfilesInGroup(sub.groupId)
            dataStore.edit { prefs ->
                val idsRaw = prefs[groupsIdsKey()] ?: ""
                val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                ids.remove(sub.groupId)
                prefs[groupsIdsKey()] = ids.joinToString(",")
                prefs.remove(groupNameKey(sub.groupId))
            }
        }
        clearSubscriptionMetadata(id)
    }

    suspend fun getSubscriptionOnce(id: String): ProfileSubscription? {
        val prefs = dataStore.data.first()
        val name = prefs[subNameKey(id)] ?: return null
        return ProfileSubscription(
            id = id,
            name = name,
            url = prefs[subUrlKey(id)] ?: "",
            description = prefs[subDescKey(id)] ?: "",
            groupId = prefs[subGroupIdKey(id)] ?: "",
            trafficLimitMb = prefs[subTrafficLimitKey(id)] ?: 0.0,
            remoteTrafficUsedMb = prefs[subRemoteTrafficKey(id)] ?: 0.0,
            remoteUpdatedAt = prefs[subRemoteUpdatedKey(id)] ?: "",
            lastSyncAt = prefs[subLastSyncKey(id)] ?: 0L,
            lastSyncError = prefs[subLastErrorKey(id)] ?: ""
        )
    }

    suspend fun addSubscription(url: String): Result<ProfileSubscription> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Укажите адрес подписки"))

        try {
            val parsed = SubscriptionImport.fetch(trimmedUrl).getOrThrow()
            val groupName = parsed.subscriptionName?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withContext Result.failure(IllegalArgumentException("В подписке не указано название"))
            importProfilesToGroup(groupName, parsed.profiles, fromSubscription = true)
            val groupId = findGroupByName(groupName)?.id ?: ""
            val id = UUID.randomUUID().toString()
            val sub = ProfileSubscription(
                id = id,
                name = groupName,
                url = trimmedUrl,
                description = parsed.description,
                groupId = groupId,
                trafficLimitMb = parsed.trafficLimitMb,
                remoteTrafficUsedMb = parsed.trafficUsedMb,
                remoteUpdatedAt = parsed.updatedAt,
                lastSyncAt = System.currentTimeMillis(),
                lastSyncError = ""
            )
            saveSubscription(sub)
            Result.success(sub)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshSubscription(id: String): Result<Int> = withContext(Dispatchers.IO) {
        val sub = getSubscriptionOnce(id) ?: return@withContext Result.failure(IllegalStateException("Подписка не найдена"))
        if (sub.url.isBlank()) return@withContext Result.failure(IllegalStateException("Адрес подписки пуст"))

        try {
            val parsed = SubscriptionImport.fetch(sub.url).getOrThrow()
            val groupName = parsed.subscriptionName?.trim()?.takeIf { it.isNotEmpty() } ?: sub.name
            importProfilesToGroup(groupName, parsed.profiles, fromSubscription = true)
            val groupId = findGroupByName(groupName)?.id ?: sub.groupId
            saveSubscription(
                sub.copy(
                    name = groupName,
                    description = parsed.description.ifBlank { sub.description },
                    groupId = groupId,
                    trafficLimitMb = parsed.trafficLimitMb,
                    remoteTrafficUsedMb = parsed.trafficUsedMb,
                    remoteUpdatedAt = parsed.updatedAt,
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncError = ""
                )
            )
            Result.success(parsed.profiles.size)
        } catch (e: Exception) {
            saveSubscription(
                sub.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastSyncError = e.message ?: "Ошибка"
                )
            )
            Result.failure(e)
        }
    }

    data class SubscriptionAutoRefreshResult(
        val refreshedOk: Int,
        val failed: Int,
        val skippedFresh: Int,
    )

    /**
     * Тихое автообновление: только «устаревшие» по интервалу, либо все при [SettingsStore.SUB_AUTO_REFRESH_EVERY_OPEN].
     * Не трогает свежие подписки. Вызывать когда туннель не запущен.
     */
    suspend fun autoRefreshSubscriptionsIfDue(intervalHours: Int): SubscriptionAutoRefreshResult =
        withContext(Dispatchers.IO) {
            if (intervalHours == SettingsStore.SUB_AUTO_REFRESH_NEVER) {
                return@withContext SubscriptionAutoRefreshResult(0, 0, 0)
            }
            val all = subscriptions.first()
            if (all.isEmpty()) return@withContext SubscriptionAutoRefreshResult(0, 0, 0)

            val now = System.currentTimeMillis()
            val due = if (intervalHours == SettingsStore.SUB_AUTO_REFRESH_EVERY_OPEN) {
                all
            } else {
                val minAgeMs = intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
                all.filter { now - it.lastSyncAt >= minAgeMs }
            }
            val skipped = all.size - due.size
            var ok = 0
            var fail = 0
            due.forEach { sub ->
                if (refreshSubscription(sub.id).isSuccess) ok++ else fail++
            }
            SubscriptionAutoRefreshResult(ok, fail, skipped)
        }

    suspend fun isSubscriptionGroup(groupId: String): Boolean {
        if (groupId.isBlank()) return false
        return subscriptions.first().any { it.groupId == groupId }
    }

    private suspend fun clearSubscriptionMetadata(id: String) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[subscriptionsIdsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            ids.remove(id)
            prefs[subscriptionsIdsKey()] = ids.joinToString(",")
            prefs.remove(subNameKey(id))
            prefs.remove(subUrlKey(id))
            prefs.remove(subDescKey(id))
            prefs.remove(subGroupIdKey(id))
            prefs.remove(subTrafficLimitKey(id))
            prefs.remove(subRemoteTrafficKey(id))
            prefs.remove(subRemoteUpdatedKey(id))
            prefs.remove(subLastSyncKey(id))
            prefs.remove(subLastErrorKey(id))
        }
    }

    suspend fun saveProfile(profile: ConnectionProfile, fromSubscriptionSync: Boolean = false) = withContext(Dispatchers.IO) {
        if (!fromSubscriptionSync && profile.groupId.isNotBlank() && isSubscriptionGroup(profile.groupId)) {
            val existing = getProfileOnce(profile.id)
            if (existing == null || existing.groupId != profile.groupId) {
                throw IllegalArgumentException("В папку подписки нельзя добавлять профили вручную")
            }
        }
        dataStore.edit { prefs ->
            val idsRaw = prefs[idsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!ids.contains(profile.id)) ids.add(profile.id)
            prefs[idsKey()] = ids.joinToString(",")

            prefs[nameKey(profile.id)] = profile.name
            prefs[peerKey(profile.id)] = profile.peer
            prefs[hashesKey(profile.id)] = profile.vkHashes
            prefs[workersKey(profile.id)] = profile.workersPerHash
            prefs[portKey(profile.id)] = profile.listenPort
            prefs[passKey(profile.id)] = secureStore.encrypt(profile.password)
            prefs[trafficKey(profile.id)] = profile.trafficMb
            prefs[groupIdKey(profile.id)] = profile.groupId
            prefs[useGlobalHashesKey(profile.id)] = profile.useGlobalHashes
            if (profile.diagKey.isBlank()) {
                prefs.remove(diagKeyKey(profile.id))
            } else {
                prefs[diagKeyKey(profile.id)] = secureStore.encrypt(profile.diagKey)
            }
            prefs[protocolKey(profile.id)] = SettingsStore.normalizeServerProtocol(profile.serverProtocol)
            prefs[csqttObfsKey(profile.id)] = profile.csqttObfs
            prefs[csqttTransportKey(profile.id)] = profile.csqttTransport
            prefs[csqttSrtpKey(profile.id)] = profile.csqttSrtp
            prefs[csqttFingerprintKey(profile.id)] = profile.csqttFingerprint
            prefs[csqttHashModeKey(profile.id)] = profile.csqttHashMode
            if (profile.csqttAccessToken.isBlank()) {
                prefs.remove(csqttTokenKey(profile.id))
            } else {
                prefs[csqttTokenKey(profile.id)] = secureStore.encrypt(profile.csqttAccessToken)
            }
            prefs[csqttClientIdsKey(profile.id)] = profile.csqttClientIds
            prefs[csqttTurnIpsKey(profile.id)] = profile.csqttCustomTurnIps
            prefs[csqttDomainRoutingKey(profile.id)] = profile.csqttDomainRouting
            prefs[csqttExcludeRuKey(profile.id)] = profile.csqttExcludeRuZones
            prefs[csqttDirectDomainsKey(profile.id)] = profile.csqttDirectDomains
            prefs[csqttDirectIpsKey(profile.id)] = profile.csqttDirectIps
        }
    }

    suspend fun saveGroup(group: ProfileGroup) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[groupsIdsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!ids.contains(group.id)) ids.add(group.id)
            prefs[groupsIdsKey()] = ids.joinToString(",")
            prefs[groupNameKey(group.id)] = group.name
        }
    }

    suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val idsRaw = prefs[idsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            ids.remove(id)
            prefs[idsKey()] = ids.joinToString(",")
            prefs.remove(nameKey(id))
            prefs.remove(peerKey(id))
            prefs.remove(hashesKey(id))
            prefs.remove(workersKey(id))
            prefs.remove(portKey(id))
            prefs.remove(passKey(id))
            prefs.remove(useGlobalHashesKey(id))
            prefs.remove(trafficKey(id))
            prefs.remove(groupIdKey(id))
            prefs.remove(diagKeyKey(id))
            prefs.remove(protocolKey(id))
            prefs.remove(csqttObfsKey(id))
            prefs.remove(csqttTransportKey(id))
            prefs.remove(csqttSrtpKey(id))
            prefs.remove(csqttFingerprintKey(id))
            prefs.remove(csqttHashModeKey(id))
            prefs.remove(csqttTokenKey(id))
            prefs.remove(csqttClientIdsKey(id))
            prefs.remove(csqttTurnIpsKey(id))
            prefs.remove(csqttDomainRoutingKey(id))
            prefs.remove(csqttExcludeRuKey(id))
            prefs.remove(csqttDirectDomainsKey(id))
            prefs.remove(csqttDirectIpsKey(id))
        }
    }

    suspend fun findGroupByName(name: String): ProfileGroup? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return groups.first().find { it.name.equals(trimmed, ignoreCase = true) }
    }

    suspend fun deleteProfilesInGroup(groupId: String) = withContext(Dispatchers.IO) {
        if (groupId.isBlank()) return@withContext
        val toDelete = profiles.first().filter { it.groupId == groupId }
        if (toDelete.isEmpty()) return@withContext
        val currentId = settings.currentProfileId.first()
        toDelete.forEach { deleteProfile(it.id) }
        if (toDelete.any { it.id == currentId }) {
            settings.saveCurrentProfile("", "")
        }
    }

    /** Существующая папка с тем же именем: профили в ней удаляются, затем импортируются новые. */
    suspend fun resolveGroupIdForImport(groupName: String, fromSubscription: Boolean = false): String = withContext(Dispatchers.IO) {
        val trimmed = groupName.trim()
        if (trimmed.isEmpty()) return@withContext ""
        val existing = findGroupByName(trimmed)
        if (existing != null) {
            if (!fromSubscription && isSubscriptionGroup(existing.id)) {
                throw IllegalArgumentException("Папка «$trimmed» — подписка, добавлять профили вручную нельзя")
            }
            deleteProfilesInGroup(existing.id)
            return@withContext existing.id
        }
        val newId = UUID.randomUUID().toString()
        saveGroup(ProfileGroup(newId, trimmed))
        newId
    }

    suspend fun importProfilesToGroup(groupName: String, profiles: List<ConnectionProfile>, fromSubscription: Boolean = false) = withContext(Dispatchers.IO) {
        val groupId = resolveGroupIdForImport(groupName, fromSubscription)
        for (p in profiles) {
            saveProfile(p.copy(id = UUID.randomUUID().toString(), groupId = groupId), fromSubscriptionSync = fromSubscription)
        }
    }

    suspend fun deleteGroup(id: String) = withContext(Dispatchers.IO) {
        subscriptions.first().find { it.groupId == id }?.let { clearSubscriptionMetadata(it.id) }
        deleteProfilesInGroup(id)
        dataStore.edit { prefs ->
            val idsRaw = prefs[groupsIdsKey()] ?: ""
            val ids = idsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            ids.remove(id)
            prefs[groupsIdsKey()] = ids.joinToString(",")
            prefs.remove(groupNameKey(id))
        }
    }

    suspend fun createProfile(
        name: String,
        peer: String,
        vkHashes: String,
        workers: Int,
        listenPort: Int,
        password: String,
        groupId: String = "",
        serverProtocol: String = SettingsStore.SERVER_PROTOCOL_WDTT,
    ): ConnectionProfile {
        val id = UUID.randomUUID().toString()
        val p = ConnectionProfile(
            id = id,
            name = name,
            peer = peer,
            vkHashes = vkHashes,
            workersPerHash = workers,
            listenPort = listenPort,
            password = password,
            groupId = groupId,
            serverProtocol = serverProtocol,
        )
        saveProfile(p)
        return p
    }

    suspend fun getProfileOnce(id: String): ConnectionProfile? {
        val prefs = dataStore.data.first()
        val name = prefs[nameKey(id)] ?: return null
        val peer = prefs[peerKey(id)] ?: ""
        val hashes = prefs[hashesKey(id)] ?: ""
        val workers = prefs[workersKey(id)] ?: 9
        val port = prefs[portKey(id)] ?: 9000
        val enc = prefs[passKey(id)] ?: ""
        val pass = secureStore.decrypt(enc) ?: ""
        val traffic = prefs[trafficKey(id)] ?: 0.0
        val groupId = prefs[groupIdKey(id)] ?: ""
        val useGlobal = prefs[useGlobalHashesKey(id)] ?: hashes.isBlank()
        val diagKey = secureStore.decrypt(prefs[diagKeyKey(id)] ?: "") ?: ""
        return ConnectionProfile(
            id = id,
            name = name,
            peer = peer,
            vkHashes = hashes,
            workersPerHash = workers,
            listenPort = port,
            password = pass,
            trafficMb = traffic,
            groupId = groupId,
            useGlobalHashes = useGlobal,
            diagKey = diagKey,
            serverProtocol = SettingsStore.normalizeServerProtocol(prefs[protocolKey(id)]),
            csqttObfs = prefs[csqttObfsKey(id)] ?: "unified",
            csqttTransport = prefs[csqttTransportKey(id)] ?: "udp",
            csqttSrtp = prefs[csqttSrtpKey(id)] ?: "auto",
            csqttFingerprint = prefs[csqttFingerprintKey(id)] ?: "chrome",
            csqttHashMode = prefs[csqttHashModeKey(id)] ?: "manual",
            csqttAccessToken = secureStore.decrypt(prefs[csqttTokenKey(id)] ?: "") ?: "",
            csqttClientIds = prefs[csqttClientIdsKey(id)] ?: "",
            csqttCustomTurnIps = prefs[csqttTurnIpsKey(id)] ?: "",
            csqttDomainRouting = prefs[csqttDomainRoutingKey(id)] ?: false,
            csqttExcludeRuZones = prefs[csqttExcludeRuKey(id)] ?: false,
            csqttDirectDomains = prefs[csqttDirectDomainsKey(id)] ?: "",
            csqttDirectIps = prefs[csqttDirectIpsKey(id)] ?: "",
        )
    }

    suspend fun incrementProfileTraffic(id: String, additionalTrafficMb: Double) = withContext(Dispatchers.IO) {
        if (id.isBlank() || additionalTrafficMb <= 0.0) return@withContext
        dataStore.edit { prefs ->
            val key = trafficKey(id)
            val current = prefs[key] ?: 0.0
            prefs[key] = current + additionalTrafficMb
        }
    }

    suspend fun reorderProfiles(newOrderForSubset: List<String>) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val actualIdsRaw = prefs[stringPreferencesKey("profiles_ids")] ?: ""
            val actualIds = actualIdsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            
            val subsetIndices = actualIds.mapIndexedNotNull { index, id -> if (newOrderForSubset.contains(id)) index else null }.sorted()
            if (subsetIndices.size == newOrderForSubset.size) {
                for (i in subsetIndices.indices) {
                    actualIds[subsetIndices[i]] = newOrderForSubset[i]
                }
                prefs[stringPreferencesKey("profiles_ids")] = actualIds.joinToString(",")
            } else if (actualIds.isEmpty() || newOrderForSubset.containsAll(actualIds)) {
                prefs[stringPreferencesKey("profiles_ids")] = newOrderForSubset.joinToString(",")
            }
        }
    }

    // Apply profile: save to SettingsStore
    suspend fun applyProfile(context: Context, id: String) {
        val p = getProfileOnce(id) ?: return
        val isCsqtt = p.isCsqtt
        // save to settings
        val finalHashes = if (isCsqtt) {
            // Для CSQTT общие WDTT-хеши не подставляем: у профиля свой набор
            // либо VK access token для auto_js.
            p.vkHashes
        } else if (p.useGlobalHashes) {
            val global = settings.globalVkHashes.first()
            global.ifEmpty { p.vkHashes }
        } else {
            p.vkHashes
        }
        val manualPorts = settings.manualPortsEnabled.first()
        val serverDtlsPort = if (manualPorts) settings.serverDtlsPort.first() else 56000
        val csqttPort = PeerAddress.port(p.peer)
            ?.takeIf { it in 1..65535 }
            ?: CsqttDefaults.DEFAULT_PEER_PORT
        val peerWithPort = if (isCsqtt) {
            PeerAddress.ensurePort(p.peer, csqttPort)
        } else {
            PeerAddress.ensurePort(p.peer, serverDtlsPort)
        }
        settings.save(peerWithPort, finalHashes, "", p.workersPerHash, "udp", p.listenPort)
        settings.saveConnectionPassword(p.password)
        settings.saveDiagKey(p.diagKey)
        settings.saveServerProtocol(p.serverProtocol)
        val currentMode = settings.connectionMode.first()
        if (isCsqtt) {
            // CSQTT-профиль всегда означает CSQTT-режим (чип в настройках
            // подключения должен отражать реальное состояние).
            settings.saveConnectionMode(SettingsStore.CONNECTION_MODE_CSQTT)
        } else if (currentMode == SettingsStore.CONNECTION_MODE_CSQTT) {
            // Иначе после CSQTT-профиля WDTT-профиль остался бы в CSQTT-режиме.
            settings.saveConnectionMode(SettingsStore.CONNECTION_MODE_VPN)
        }
        if (isCsqtt) {
            settings.saveCsqtt(
                peerPort = csqttPort,
                obfs = p.csqttObfs,
                transport = p.csqttTransport,
                srtpMode = p.csqttSrtp,
                fingerprint = p.csqttFingerprint,
                vkHashMode = p.csqttHashMode,
                accessToken = p.csqttAccessToken,
                clientIds = p.csqttClientIds,
                customTurnIps = p.csqttCustomTurnIps,
                domainRouting = p.csqttDomainRouting,
                excludeRuZones = p.csqttExcludeRuZones,
                directDomains = p.csqttDirectDomains,
                directIps = p.csqttDirectIps,
            )
        }
        settings.saveCurrentProfile(p.id, p.name)
    }
}
