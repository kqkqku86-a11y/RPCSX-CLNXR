package net.rpcsx.utils

import net.rpcsx.RPCSX
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Result of trying to apply the official RPCS3 community config for a game. */
sealed class CommunityConfigResult {
    /** A community config was found and saved as this game's custom config. */
    object Applied : CommunityConfigResult()

    /** The database has no recommended config for this game. */
    object NotFound : CommunityConfigResult()

    data class Error(val message: String) : CommunityConfigResult()
}

/** Result of fetching (but not yet applying) the community config, for preview. */
sealed class CommunityConfigFetch {
    /** The recommended config YAML, so the UI can preview it before applying. */
    data class Found(val yaml: String) : CommunityConfigFetch()
    object NotFound : CommunityConfigFetch()
    data class Error(val message: String) : CommunityConfigFetch()
}

/**
 * App-side glue for per-game custom configs. The core owns the actual YAML
 * (config/custom_configs/config_<serial>.yml) and applies it at boot via
 * cfg_mode::custom; this object just maps a game to its serial and proxies the
 * core's custom-config calls, plus the one-tap community download.
 */
object PerGameConfigRepository {
    private val client = OkHttpClient()

    // The official RPCS3 config database (same source the desktop app uses).
    private const val CONFIG_DB_URL = "https://api.rpcs3.net/config/?api=v1"

    /**
     * A game's serial / title id. For installed games the folder name is the
     * title id (the core builds those paths as .../game/<TITLE_ID>), which is
     * exactly what boot uses to look up the custom config.
     */
    fun serialOf(gamePath: String): String =
        gamePath.trimEnd('/').substringAfterLast('/')

    fun hasCustomConfig(serial: String): Boolean = runCatching {
        RPCSX.instance.customConfigExists(serial)
    }.getOrDefault(false)

    fun createCustomConfig(serial: String): Boolean = runCatching {
        RPCSX.instance.customConfigCreate(serial)
    }.getOrDefault(false)

    fun deleteCustomConfig(serial: String): Boolean = runCatching {
        RPCSX.instance.customConfigDelete(serial)
    }.getOrDefault(false)

    /** Whole-tree JSON for the game's effective config (global + custom overlay). */
    fun get(serial: String, path: String = ""): JSONObject? = runCatching {
        val raw = RPCSX.instance.customConfigGet(serial, path)
        if (raw.isEmpty()) null else JSONObject(raw)
    }.getOrNull()

    fun set(serial: String, path: String, value: String): Boolean = runCatching {
        RPCSX.instance.customConfigSet(serial, path, value)
    }.getOrDefault(false)

    /**
     * Download the official RPCS3 config database and return this game's
     * recommended config YAML (without applying it) so the UI can preview the
     * changes before the user commits.
     */
    fun fetchCommunityConfig(serial: String): CommunityConfigFetch {
        if (serial.isEmpty()) return CommunityConfigFetch.Error("Unknown game serial")
        return try {
            val request = Request.Builder().url(CONFIG_DB_URL)
                .header("User-Agent", "rpcsx").build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return CommunityConfigFetch.Error("HTTP ${resp.code}")

                val root = JSONObject(resp.body?.string().orEmpty())
                when (val rc = root.optInt("return_code", -255)) {
                    in 0..Int.MAX_VALUE -> Unit
                    -2 -> return CommunityConfigFetch.Error("Server in maintenance mode")
                    else -> return CommunityConfigFetch.Error("Server error (code $rc)")
                }

                val games = root.optJSONObject("games")
                    ?: return CommunityConfigFetch.Error("Malformed database")
                val game = games.optJSONObject(serial) ?: return CommunityConfigFetch.NotFound
                val yaml = game.optString("config")
                if (yaml.isEmpty()) return CommunityConfigFetch.NotFound
                CommunityConfigFetch.Found(yaml)
            }
        } catch (e: Throwable) {
            CommunityConfigFetch.Error(e.message ?: "Download failed")
        }
    }

    /** Save a config YAML as the game's custom config (core validates the schema). */
    fun importConfig(serial: String, yaml: String): Boolean = runCatching {
        RPCSX.instance.customConfigImport(serial, yaml)
    }.getOrDefault(false)

    /** Fetch + apply in one step (kept for callers that don't preview first). */
    fun applyCommunityConfig(serial: String): CommunityConfigResult =
        when (val f = fetchCommunityConfig(serial)) {
            is CommunityConfigFetch.Found ->
                if (importConfig(serial, f.yaml)) CommunityConfigResult.Applied
                else CommunityConfigResult.Error("Config rejected by emulator")
            is CommunityConfigFetch.NotFound -> CommunityConfigResult.NotFound
            is CommunityConfigFetch.Error -> CommunityConfigResult.Error(f.message)
        }
}
