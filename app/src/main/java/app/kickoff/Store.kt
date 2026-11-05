package app.kickoff

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/** Tiny JSON-file store. All writes go through [update] so UI and background work stay in sync. */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: File
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> get() = _state

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "state.json")
        _state.value = runCatching { json.decodeFromString(AppState.serializer(), file.readText()) }
            .getOrDefault(AppState())
    }

    @Synchronized
    fun update(change: (AppState) -> AppState): AppState {
        val next = change(_state.value)
        if (next != _state.value) {
            _state.value = next
            // A failed save (e.g. storage full) must not crash a background service; the in-memory
            // state stays correct and the next update tries again.
            runCatching {
                val tmp = File(file.parentFile, "state.json.tmp")
                tmp.writeText(json.encodeToString(AppState.serializer(), next))
                check(tmp.renameTo(file)) { "rename failed" }
            }.onFailure { Log.w("Store", "save failed", it) }
        }
        return next
    }

    /** Turns a game's alerts on or off (its card's bell). */
    fun setMuted(matchId: String, muted: Boolean) = update { s ->
        s.copy(alerts = s.alerts + (matchId to (s.alerts[matchId] ?: AlertState()).copy(muted = muted)))
    }
}
