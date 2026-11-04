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

    /** Called when the user opens a game; only counts once a notification for it has been sent. */
    fun markRead(matchId: String) = update { s ->
        val a = s.alerts[matchId]
        if (a == null || !a.notified || a.read) s
        else s.copy(alerts = s.alerts + (matchId to a.copy(read = true)))
    }
}
