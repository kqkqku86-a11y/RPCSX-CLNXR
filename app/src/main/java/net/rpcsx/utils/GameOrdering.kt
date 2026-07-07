package net.rpcsx.utils

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.json.Json
import net.rpcsx.utils.GeneralSettings.boolean
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

/**
 * Library visibility filter. Two orthogonal, persisted, Compose-observable axes so a
 * multi-select stays unambiguous:
 *  - SOURCE: [showInstalled] (internal copies under RPCSX.rootDirectory) and
 *    [showGamesFolder] (everything else = the user's games folder: ISOs + folder games).
 *    A game passes if its own source is enabled; both on (default) = every source.
 *  - STATE: [onlyPlayed] further restricts to games that have been booted at least once
 *    (per [GamePlayHistory]) - "readily playable".
 * The rootDirectory-vs-external decision is done by the caller (it holds RPCSX); this
 * object is pure state, mirroring GameSort / GameViewTheme.
 */
object GameFilter {
    private val _installed = mutableStateOf(GeneralSettings["game_filter_installed"].boolean(true))
    private val _folder = mutableStateOf(GeneralSettings["game_filter_folder"].boolean(true))
    private val _onlyPlayed = mutableStateOf(GeneralSettings["game_filter_only_played"].boolean(false))

    var showInstalled: Boolean
        get() = _installed.value
        set(v) { _installed.value = v; GeneralSettings["game_filter_installed"] = v }

    var showGamesFolder: Boolean
        get() = _folder.value
        set(v) { _folder.value = v; GeneralSettings["game_filter_folder"] = v }

    var onlyPlayed: Boolean
        get() = _onlyPlayed.value
        set(v) { _onlyPlayed.value = v; GeneralSettings["game_filter_only_played"] = v }
}
