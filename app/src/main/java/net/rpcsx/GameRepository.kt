package net.rpcsx

import android.content.res.Resources.NotFoundException
import androidx.annotation.Keep
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.rpcsx.utils.GeneralSettings
import java.io.File
import java.security.InvalidParameterException
import kotlin.concurrent.thread

enum class GameFlag {
    Locked,
    Trial
}

@Serializable
data class GameInfo @Keep @JvmOverloads constructor(
    val path: String,
    var name: String? = null,
    var iconPath: String? = null,
    var gameFlags: Int = 0,
    var version: String? = null,
    var titleId: String? = null,
    // PARAM.SFO CATEGORY ("DG" disc game, "HG" HDD game, "GD" disc-game update,
    // ...). Set by the native scan; drives cross-source de-duplication in add().
    var category: String? = null
)

data class GameInfoStore(
    val path: String,
    val name: MutableState<String?> = mutableStateOf(null),
    val iconPath: MutableState<String?> = mutableStateOf(null),
    val gameFlags: MutableIntState = mutableIntStateOf(0),
    val version: MutableState<String?> = mutableStateOf(null),
    val titleId: MutableState<String?> = mutableStateOf(null),
    val category: MutableState<String?> = mutableStateOf(null)
)

enum class GameProgressType {
    Install,
    Compile,
    Remove,
}

data class GameProgress(val id: Long, val type: GameProgressType)

data class Game(
    val info: GameInfoStore,
    val progressList: SnapshotStateList<GameProgress> = mutableStateListOf()
) {
    fun addProgress(progress: GameProgress) {
        if (findProgress(progress.type) != null) {
            throw InvalidParameterException()
        }

        progressList += progress
    }

    fun findProgress(type: GameProgressType) =
        progressList.filter { elem -> elem.type == type }.ifEmpty { null }

    fun findProgress(types: Array<GameProgressType>) =
        progressList.filter { elem -> types.contains(elem.type) }.ifEmpty { null }

    fun removeProgress(type: GameProgressType) =
        progressList.removeIf { progress -> progress.type == type }

    fun hasFlag(flag: GameFlag) = (info.gameFlags.intValue and (1 shl flag.ordinal)) != 0
}

private fun toStore(info: GameInfo) =
    GameInfoStore(
        info.path,
        mutableStateOf(info.name),
        mutableStateOf(info.iconPath),
        mutableIntStateOf(info.gameFlags),
        mutableStateOf(info.version),
        mutableStateOf(info.titleId),
        mutableStateOf(info.category)
    )

private fun toInfo(store: GameInfoStore) =
    GameInfo(
        store.path,
        store.name.value,
        store.iconPath.value,
        store.gameFlags.intValue,
        store.version.value,
        store.titleId.value,
        store.category.value
    )

// A library entry that plays directly from a raw .iso (games-folder scan), as
// opposed to an installed copy under dev_hdd0/games. The native scan stores the
// bare .iso path for these; installed entries store a directory path.
private fun isIsoPath(path: String) = path.trimEnd('/').endsWith(".iso", ignoreCase = true)

// How strongly an entry should represent its title when the SAME title id shows
// up from more than one source. Higher wins; the loser is dropped from the list
// (never from disk). A full installed game (any category except a bare "GD"
// disc-game update) is best; a directly-playable .iso beats a lone "GD" update
// (which is not standalone-bootable - the ISO boot applies it automatically).
private fun entryRank(path: String, category: String?): Int = when {
    !isIsoPath(path) && category != "GD" -> 3 // installed full game (games/ or dev_hdd0 HG)
    isIsoPath(path) -> 2                       // playable .iso from the games folder
    else -> 1                                  // bare "GD" update sitting in dev_hdd0
}

class GameRepository {
    private val games = mutableStateListOf<Game>()

    companion object {
        private val instance = GameRepository()

        // GeneralSettings key: real filesystem path of the user-chosen games folder,
        // scanned in place (no copying) so ISOs and folder-format games there play
        // directly. Set from Settings; empty/unset = no extra scan.
        const val GAMES_FOLDER_KEY = "games_folder_path"

        // Lenient parser for the games.json cache: tolerate unknown keys so a
        // games.json written by a NEWER build (extra GameInfo fields) still loads
        // on an older one instead of throwing and clearing the cached list.
        private val gamesJson = Json { ignoreUnknownKeys = true }

        // Compose-observable, read-only view of the games list (reading it inside a composable
        // tracks the underlying SnapshotStateList). Used by the install keep-screen-on overlay to
        // detect active Install progress. Read-only: callers must not mutate it.
        val games: List<Game> get() = instance.games

        private var needsRefresh = false
        val isRefreshing = mutableStateOf(false)
        private var refreshThreadRunning = false
        private val refreshLock = Any()

        fun save() {
            try {
                File(RPCSX.rootDirectory + "games.json").writeText(gamesJson.encodeToString(instance.games.map { game ->
                    toInfo(
                        game.info
                    )
                }.filter { info -> info.path != "$" }))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        suspend fun load() {
            withContext(Dispatchers.IO) {
                try {
                    instance.games.clear()
                    instance.games += gamesJson.decodeFromString<Array<GameInfo>>(
                        File(RPCSX.rootDirectory + "games.json").readText()
                    ).map { info -> Game(toStore(info)) }
                } catch (_: NotFoundException) {
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun queueRefresh() {
            // Exactly one refresh worker at a time; extra requests coalesce via
            // needsRefresh. The old code used non-volatile flags and a cooldown
            // window that could spawn a second worker, racing refresh()'s
            // clear()+collectGameInfo on the snapshot list (crash/duplicates).
            // refreshThreadRunning and isRefreshing are always updated together
            // under the lock so the spinner can't get stuck or be flipped by a
            // departing worker after a new one started.
            synchronized(refreshLock) {
                needsRefresh = true
                if (refreshThreadRunning) return
                refreshThreadRunning = true
                isRefreshing.value = true
            }
            thread {
                while (true) {
                    var done = false
                    synchronized(refreshLock) {
                        if (!needsRefresh) {
                            refreshThreadRunning = false
                            isRefreshing.value = false
                            done = true
                        } else {
                            needsRefresh = false
                        }
                    }
                    if (done) break
                    refresh()
                }
            }
        }

        private fun refresh() {
            clear()
            RPCSX.instance.collectGameInfo(
                RPCSX.rootDirectory + "/config/dev_hdd0/game", -1
            )
            RPCSX.instance.collectGameInfo(RPCSX.rootDirectory + "/config/games", -1)

            // User-chosen games folder, scanned in place (no copying). Scanned
            // LAST so add()'s title-id de-dupe lets an already-installed copy win
            // over a raw .iso of the same game (see add()), preventing doubles.
            (GeneralSettings[GAMES_FOLDER_KEY] as? String)
                ?.takeIf { it.isNotBlank() && File(it).isDirectory }
                ?.let { RPCSX.instance.collectGameInfo(it, -1) }
        }
        
        @Keep
        @JvmStatic
        fun add(gameInfos: Array<GameInfo>, progressId: Long) {
            synchronized(instance) {
                if (progressId >= 0) {
                    val progressEntry =
                        instance.games.filter { game -> game.info.path == "$" }.find { game ->
                            val progress = game.findProgress(GameProgressType.Install)
                                ?.find { progress -> progress.id == progressId }
                            progress != null
                        }

                    if (progressEntry != null) {
                        instance.games.remove(progressEntry)
                    }
                }

                gameInfos.forEach { info ->
                    // Cross-source de-duplication by title id: the SAME game must
                    // never appear twice when it exists from more than one source
                    // (installed under dev_hdd0/games AND/OR as a raw .iso in the
                    // user's games folder). Keep only the highest-ranked copy (see
                    // entryRank): a full installed game beats a playable .iso, which
                    // beats a bare "GD" update. Ties keep the copy already listed.
                    // This only prunes the on-screen list - it never touches,
                    // moves, or deletes any file on disk.
                    val incomingTid = info.titleId?.takeIf { it.isNotBlank() }
                    if (incomingTid != null) {
                        val incomingRank = entryRank(info.path, info.category)
                        val twins = instance.games.filter { g ->
                            g.info.path != "$" &&
                                g.info.path != info.path &&
                                g.info.titleId.value?.takeIf { it.isNotBlank() } == incomingTid
                        }
                        if (twins.isNotEmpty()) {
                            val bestTwinRank = twins.maxOf {
                                entryRank(it.info.path, it.info.category.value)
                            }
                            if (incomingRank > bestTwinRank) {
                                // Incoming is the better representation - drop the
                                // weaker twins (all rank below incoming) and add it.
                                twins.forEach { instance.games.remove(it) }
                            } else {
                                // An equal-or-better twin already represents this
                                // title - skip the duplicate entirely.
                                return@forEach
                            }
                        }
                    }

                    val existsGame = instance.games.find { x -> x.info.path == info.path }
                    if (existsGame == null) {
                        val newGame = Game(toStore(info))
                        if (progressId >= 0) {
                            newGame.addProgress(GameProgress(progressId, GameProgressType.Install))
                        }
                        instance.games.add(0, newGame)
                    } else {
                        existsGame.info.name.value = info.name ?: existsGame.info.name.value
                        existsGame.info.iconPath.value =
                            info.iconPath ?: existsGame.info.iconPath.value
                        existsGame.info.gameFlags.intValue = info.gameFlags
                        existsGame.info.version.value =
                            info.version?.takeIf { it.isNotBlank() }
                                ?: existsGame.info.version.value
                        existsGame.info.titleId.value =
                            info.titleId?.takeIf { it.isNotBlank() }
                                ?: existsGame.info.titleId.value
                        existsGame.info.category.value =
                            info.category?.takeIf { it.isNotBlank() }
                                ?: existsGame.info.category.value
                        if (progressId >= 0) {
                            existsGame.addProgress(
                                GameProgress(
                                    progressId,
                                    GameProgressType.Install
                                )
                            )
                        }
                    }
                }
                save()
            }
        }

        fun addPreview(gameInfos: Array<GameInfo>) {
            instance.games += gameInfos.map { info -> Game(toStore(info)) }
        }

        fun onBoot(game: Game) {
            synchronized(instance) {
                if (instance.games.firstOrNull() != game) {
                    instance.games.remove(game)
                    instance.games.add(0, game)
                    save()
                }
            }
        }

        fun createGameInstallEntry(progressId: Long) {
            synchronized(instance) {
                val game = Game(GameInfoStore("$"))
                game.addProgress(GameProgress(progressId, GameProgressType.Install))
                instance.games.add(0, game)
            }
        }

        fun clearProgress(progressId: Long) {
            synchronized(instance) {
                instance.games.forEach { game -> game.progressList.removeIf { progress -> progress.id == progressId } }
                instance.games.removeIf { game -> game.info.path == "$" && game.progressList.isEmpty() }
            }
        }

        fun remove(game: Game) {
            synchronized(instance) {
                instance.games -= game
                save()
            }
        }

        fun find(path: String): Game? {
            synchronized(instance) {
                return instance.games.find { game -> game.info.path == path }
            }
        }

        fun list() = instance.games

        fun clear() {
            synchronized(instance) {
                instance.games.clear()
            }
        }
    }
}
