package net.typeblog.shelter.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.toMutablePreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private const val LEGACY_PREFS_NAME = "prefs"
private const val LEGACY_LIST_DIVIDER = ","

/**
 * Type-safe settings backed by DataStore.
 *
 * Replaces the original `LocalStorageManager` (shared prefs) and the storage part of
 * `SettingsManager`. The synchronous facade mirrors the original blocking API so that
 * legacy/BroadcastReceiver handlers (e.g. DummyActivity's SYNCHRONIZE_PREFERENCE) can read
 * and write without introducing coroutines.
 *
 * All DataStore key names deliberately equal the original SharedPreferences literal names so
 * [LegacyPrefsMigration] can copy values verbatim (the auto-freeze list is converted from a
 * comma-joined String to a Set<String>).
 */
class SettingsStore(private val context: Context) {

    /** Durable preference keys. Literal names match the legacy SharedPreferences file. */
    object Keys {
        val IS_SETTING_UP = booleanPreferencesKey("is_setting_up")
        val HAS_SETUP = booleanPreferencesKey("has_setup")
        val CROSS_PROFILE_FILE_CHOOSER = booleanPreferencesKey("cross_profile_file_chooser")
        val AUTO_FREEZE_SERVICE = booleanPreferencesKey("auto_freeze_service")
        val DONT_FREEZE_FOREGROUND = booleanPreferencesKey("dont_freeze_foreground")
        val AUTO_FREEZE_DELAY = longPreferencesKey("auto_freeze_delay")
        val BLOCK_CONTACTS_SEARCHING = booleanPreferencesKey("block_contacts_searching")
        val PAYMENT_STUB = booleanPreferencesKey("payment_stub")
        val AUTO_FREEZE_LIST_WORK_PROFILE = stringSetPreferencesKey("auto_freeze_list_work_profile")
    }

    /** Raw literal key name -> typed [Preferences.Key]. */
    private val nameToKey: Map<String, Preferences.Key<*>> = mapOf(
        "is_setting_up" to Keys.IS_SETTING_UP,
        "has_setup" to Keys.HAS_SETUP,
        "cross_profile_file_chooser" to Keys.CROSS_PROFILE_FILE_CHOOSER,
        "auto_freeze_service" to Keys.AUTO_FREEZE_SERVICE,
        "dont_freeze_foreground" to Keys.DONT_FREEZE_FOREGROUND,
        "auto_freeze_delay" to Keys.AUTO_FREEZE_DELAY,
        "block_contacts_searching" to Keys.BLOCK_CONTACTS_SEARCHING,
        "payment_stub" to Keys.PAYMENT_STUB,
        "auto_freeze_list_work_profile" to Keys.AUTO_FREEZE_LIST_WORK_PROFILE,
    )

    val data: Flow<Preferences> = context.dataStore.data

    // ------------------------------------------------------------------ async API

    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<T> =
        data.map { it[key] ?: default }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun <T> remove(key: Preferences.Key<T>) {
        context.dataStore.edit { it.remove(key) }
    }

    // ------------------------------------------------------------------ sync facade (blocks briefly; for non-coroutine handlers)

    fun syncGetBoolean(key: Preferences.Key<Boolean>): Boolean =
        readPrefs()[key] ?: false

    /** Resolve a legacy literal key name and read its boolean value. */
    fun syncGetBoolean(name: String): Boolean =
        (nameToKey[name] as? Preferences.Key<Boolean>)?.let { syncGetBoolean(it) } ?: false

    fun syncSetBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        writePrefs { it[key] = value }
    }

    fun syncGetLong(key: Preferences.Key<Long>): Long =
        readPrefs()[key] ?: 0L

    fun syncSetLong(key: Preferences.Key<Long>, value: Long) {
        writePrefs { it[key] = value }
    }

    fun syncSetBooleanByName(name: String, value: Boolean) {
        (nameToKey[name] as? Preferences.Key<Boolean>)?.let { syncSetBoolean(it, value) }
    }

    /** SYNCHRONIZE_PREFERENCE int path; the auto-freeze delay is stored as long. */
    fun syncSetIntByName(name: String, value: Int) {
        when (val key = nameToKey[name]) {
            is Preferences.Key<*> -> writePrefs { prefs ->
                when (key) {
                    Keys.AUTO_FREEZE_DELAY -> prefs[Keys.AUTO_FREEZE_DELAY] = value.toLong()
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ auto-freeze work-profile list

    fun autoFreezeList(): Flow<Set<String>> = observe(Keys.AUTO_FREEZE_LIST_WORK_PROFILE, emptySet())

    suspend fun addToAutoFreeze(packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] =
                (prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] ?: emptySet()) + packageName
        }
    }

    suspend fun removeFromAutoFreeze(packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] =
                (prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] ?: emptySet()) - packageName
        }
    }

    suspend fun setAutoFreezeList(set: Set<String>) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] = set }
    }

    fun syncAutoFreezeServiceEnabled(): Boolean = syncGetBoolean(Keys.AUTO_FREEZE_SERVICE)

    fun syncAutoFreezeList(): Set<String> =
        readPrefs()[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] ?: emptySet()

    fun syncAutoFreezeContains(packageName: String): Boolean =
        syncAutoFreezeList().contains(packageName)

    fun syncAppendAutoFreeze(packageName: String) {
        writePrefs { prefs ->
            prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] =
                (prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] ?: emptySet()) + packageName
        }
    }

    fun syncRemoveFromAutoFreeze(packageName: String) {
        writePrefs { prefs ->
            prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] =
                (prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] ?: emptySet()) - packageName
        }
    }

    /** Remove packages from the auto-freeze list that are no longer installed. */
    fun syncPruneAutoFreezeList(installedPackages: Set<String>) {
        val keep = syncAutoFreezeList().intersect(installedPackages)
        if (keep != syncAutoFreezeList()) {
            writePrefs { prefs -> prefs[Keys.AUTO_FREEZE_LIST_WORK_PROFILE] = keep }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun readPrefs(): Preferences = runBlocking { context.dataStore.data.first() }

    private fun writePrefs(transform: (MutablePreferences) -> Unit) {
        runBlocking { context.dataStore.edit(transform) }
    }
}

/** Legacy SharedPreferences "prefs" -> DataStore migration using the original literal names. */
private class LegacyPrefsMigration(private val legacy: SharedPreferences) : DataMigration<Preferences> {

    private val legacyNames = listOf(
        "is_setting_up", "has_setup", "cross_profile_file_chooser", "auto_freeze_service",
        "dont_freeze_foreground", "auto_freeze_delay", "block_contacts_searching",
        "payment_stub", "auto_freeze_list_work_profile",
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacyNames.any { legacy.contains(it) }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val out = currentData.toMutablePreferences()
        // Copy each known value unless the target key is already present (DataStore wins).
        if (!out.contains(SettingsStore.Keys.IS_SETTING_UP)) {
            out[SettingsStore.Keys.IS_SETTING_UP] = legacy.getBoolean("is_setting_up", false)
        }
        if (!out.contains(SettingsStore.Keys.HAS_SETUP)) {
            out[SettingsStore.Keys.HAS_SETUP] = legacy.getBoolean("has_setup", false)
        }
        if (!out.contains(SettingsStore.Keys.CROSS_PROFILE_FILE_CHOOSER)) {
            out[SettingsStore.Keys.CROSS_PROFILE_FILE_CHOOSER] =
                legacy.getBoolean("cross_profile_file_chooser", false)
        }
        if (!out.contains(SettingsStore.Keys.AUTO_FREEZE_SERVICE)) {
            out[SettingsStore.Keys.AUTO_FREEZE_SERVICE] = legacy.getBoolean("auto_freeze_service", false)
        }
        if (!out.contains(SettingsStore.Keys.DONT_FREEZE_FOREGROUND)) {
            out[SettingsStore.Keys.DONT_FREEZE_FOREGROUND] =
                legacy.getBoolean("dont_freeze_foreground", false)
        }
        if (!out.contains(SettingsStore.Keys.BLOCK_CONTACTS_SEARCHING)) {
            out[SettingsStore.Keys.BLOCK_CONTACTS_SEARCHING] =
                legacy.getBoolean("block_contacts_searching", false)
        }
        if (!out.contains(SettingsStore.Keys.PAYMENT_STUB)) {
            out[SettingsStore.Keys.PAYMENT_STUB] = legacy.getBoolean("payment_stub", false)
        }
        if (!out.contains(SettingsStore.Keys.AUTO_FREEZE_DELAY)) {
            out[SettingsStore.Keys.AUTO_FREEZE_DELAY] = legacy.getInt("auto_freeze_delay", 0).toLong()
        }
        if (!out.contains(SettingsStore.Keys.AUTO_FREEZE_LIST_WORK_PROFILE)) {
            val rawList = legacy.getString("auto_freeze_list_work_profile", null)
            if (!rawList.isNullOrBlank()) {
                out[SettingsStore.Keys.AUTO_FREEZE_LIST_WORK_PROFILE] =
                    rawList.split(LEGACY_LIST_DIVIDER).filter { it.isNotBlank() }.toSet()
            }
        }
        return out
    }

    override suspend fun cleanUp() {
        legacy.edit().clear().apply()
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shelter_prefs",
    produceMigrations = {
        listOf(LegacyPrefsMigration(getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)))
    },
)
