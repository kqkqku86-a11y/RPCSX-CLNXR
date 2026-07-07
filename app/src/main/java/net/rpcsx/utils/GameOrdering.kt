package net.rpcsx.utils

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.json.Json
import net.rpcsx.utils.GeneralSettings.string

/** How the library grid is ordered. Persisted; the grid observes [GameSort.mode]. */
enum class GameSortMode { LAST_PLAYED, NAME }

/**
 * User-chosen library sort order. Backed by Compose snapshot state (like ThemeState /
 * GameViewTheme) so the grid re-sorts live, and persisted in GeneralSettings so the
 * choice survives an app restart or core update.
 */
object GameSort {
    private val _mode = mutableStateOf(
        runCatching { GameSortMode.valueOf(GeneralSettings["game_sort_mode"].string(GameSortMode.LAST_PLAYED.name)) }
            .getOrDefault(GameSortMode.LAST_PLAYED)
    )

    var mode: GameSortMode
        get() = _mode.value
        set(v) { _mode.value = v; GeneralSettings["game_sort_mode"] = v.name }
}

/**
 * Persistent "last played" timestamps, keyed by the game's library path. Kept SEPARATE
 * from the scanned game list on purpose: GameRepository.refresh() clears and rebuilds
 * that list from the native scan (which has no timestamp) on every start / games-folder
 * scan, so a per-entry field would be wiped each time - which is exactly the ordering
 * complaint. This store is app-owned and survives both the rescan and an app restart.
 *
 * Backed by a snapshot map so reading [lastPlayed] inside the grid's derived sort
 * subscribes to updates; a boot re-sorts the grid immediately.
 */
object GamePlayHistory {
    private const val KEY = "game_play_history"

    private val history: SnapshotStateMap<String, Long> = mutableStateMapOf()

    init {
        runCatching {
            val json = GeneralSettings[KEY].string("")
            if (json.isNotBlank()) {
                Json.decodeFromString<Map<String, Long>>(json).forEach { (k, v) -> history[k] = v }
            }
        }
    }

    /** Milliseconds since epoch of the game's last boot, or 0 if never played. */
    fun lastPlayed(path: String): Long = history[path] ?: 0L

    /** Record that the game at [path] was just booted, and persist. */
    fun record(path: String) {
        if (path.isBlank() || path == "$") return
        history[path] = System.currentTimeMillis()
        runCatching { GeneralSettings[KEY] = Json.encodeToString(history.toMap()) }
    }
}
