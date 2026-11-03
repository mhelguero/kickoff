package app.kickoff

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    /** Game to show, set when the app is opened from a notification. */
    private val openMatch = mutableStateOf<String?>(null)
    private val needsSetup = mutableStateOf(false)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                Root(
                    openMatch = openMatch.value,
                    onOpenMatch = { id -> id?.let(Store::markRead); openMatch.value = id },
                    needsSetup = needsSetup.value,
                    onFixSetup = ::fixSetup,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkSetup()
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(Notifier.EXTRA_MATCH_ID) ?: return
        Store.markRead(id)
        openMatch.value = id
        intent.removeExtra(Notifier.EXTRA_MATCH_ID)
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun exactAlarms() = Build.VERSION.SDK_INT < 31 ||
        getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun checkSetup() {
        needsSetup.value = !hasNotificationPermission() || !ignoringBattery() || !exactAlarms()
    }

    @SuppressLint("BatteryLife")
    private fun fixSetup() {
        val intent = when {
            !hasNotificationPermission() -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            !exactAlarms() && Build.VERSION.SDK_INT >= 31 -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            else -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val green = Color(0xFF1B7F3B)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF6FD08C))
    else lightColorScheme(primary = green)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(openMatch: String?, onOpenMatch: (String?) -> Unit, needsSetup: Boolean, onFixSetup: () -> Unit) {
    val context = LocalContext.current
    val state by Store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) { Tracker.refresh(context) }
            if (Tracker.gamesToWatch().isNotEmpty()) Tracker.startWatching(context)
            refreshing = false
        }
    }

    LaunchedEffect(state.teams) { refresh() }
    // Keep the list fresh while the app is open and a game is live.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            if (Tracker.gamesToWatch().isNotEmpty()) withContext(Dispatchers.IO) { Tracker.pollOnce(context) }
        }
    }

    val match = openMatch?.let { state.matches[it] }
    if (match != null) {
        BackHandler { onOpenMatch(null) }
        MatchScreen(match, onBack = { onOpenMatch(null) })
    } else {
        HomeScreen(state, refreshing, ::refresh, onOpenMatch, needsSetup, onFixSetup)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
private fun Match.localTime() = dayFmt.format(Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    state: AppState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenMatch: (String) -> Unit,
    needsSetup: Boolean,
    onFixSetup: () -> Unit,
) {
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val games = state.matches.values
        .filter { it.dateMillis + WATCH_MAX_MS > now || it.state == "in" }
        .sortedBy { it.dateMillis }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kickoff") },
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (needsSetup) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Allow alerts to arrive on time", fontWeight = FontWeight.Bold)
                        Text("Notifications, exact alarms and unrestricted battery use are needed so kickoff alerts aren't delayed.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onFixSetup) { Text("Fix") }
                    }
                }
            }
            item {
                Text("Teams", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.teams.forEach { team ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(team.name) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp).clickable {
                                    Store.update { s -> s.copy(teams = s.teams - team) }
                                })
                            },
                        )
                    }
                    AssistChip(onClick = { showSearch = true }, label = { Text("Add team") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
                }
            }
            item {
                Text("Upcoming & live", style = MaterialTheme.typography.titleMedium)
                if (games.isEmpty()) Text(
                    if (refreshing) "Loading…" else "No upcoming games found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(games, key = { it.id }) { m -> GameCard(m, state.showScores) { onOpenMatch(m.id) } }
            item {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show scores")
                        Text("In the app and in alerts. Off avoids spoilers.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.showScores, onCheckedChange = { v -> Store.update { it.copy(showScores = v) } })
                }
                OutlinedButton(onClick = {
                    val sample = games.firstOrNull() ?: Match("test", now, "Real Madrid", "Valencia", league = "LALIGA",
                        broadcasts = listOf(Broadcast("ESPN+", "STREAMING")))
                    Notifier.gameAlert(context, sample, Phase.FIRST_HALF, false)
                }) { Text("Send a test notification") }
                Text(
                    "Alerts: kickoff always; 2nd half and full time only until you open one of that game's alerts. " +
                        "Broadcaster data is for the US.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (showSearch) TeamSearchDialog(onDismiss = { showSearch = false })
}

@Composable
private fun GameCard(m: Match, showScores: Boolean, onClick: () -> Unit) {
    val live = m.state == "in"
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (live) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(m.league, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(m.statusLabel.ifEmpty { m.localTime() }, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (live) FontWeight.Bold else null)
            }
            Spacer(Modifier.height(4.dp))
            Text(scoreLine(m, showScores), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("📺 " + Streaming.summary(m).ifEmpty { "Broadcaster not listed yet" }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun scoreLine(m: Match, showScores: Boolean) =
    if (showScores && m.state != "pre" && m.homeScore != null) "${m.home} ${m.homeScore} – ${m.awayScore} ${m.away}"
    else m.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchScreen(m: Match, onBack: () -> Unit) {
    val context = LocalContext.current
    val showScores = Store.state.value.showScores
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Where to watch") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(m.league, style = MaterialTheme.typography.labelLarge)
            Text(scoreLine(m, showScores), style = MaterialTheme.typography.headlineSmall)
            Text(listOf(m.statusLabel, m.localTime()).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            // Re-checked every time the screen opens, so a newly installed app gets its button.
            val options = remember(m.id, m.broadcasts) { Streaming.optionsFor(context, m) }
            if (options.isEmpty()) Text("No broadcaster listed for this game yet.")
            else Text("Being shown on", style = MaterialTheme.typography.titleSmall)
            options.forEach { o ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = MaterialTheme.typography.titleMedium)
                            if (o.kind.isNotBlank()) Text(o.kind, style = MaterialTheme.typography.bodySmall)
                        }
                        // Only apps installed on this phone get a button; the rest are listed for info.
                        o.launch?.let { launch ->
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { Streaming.open(context, launch) }) { Text("Open ${o.name}") }
                        }
                    }
                }
            }
            TextButton(onClick = { Streaming.openUrl(context, Streaming.findMoreUrl(m)) }) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search for other ways to watch")
            }
        }
    }
}

@Composable
private fun TeamSearchDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Team>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        loading = true
        results = withContext(Dispatchers.IO) { runCatching { Espn.searchTeams(query) }.getOrDefault(emptyList()) }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a team") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Team name") }, singleLine = true)
                if (loading) Text("Searching…", Modifier.padding(top = 8.dp))
                results.forEach { t ->
                    Text(t.name, Modifier.fillMaxWidth().clickable {
                        Store.update { s -> if (s.teams.any { it.id == t.id }) s else s.copy(teams = s.teams + t) }
                        onDismiss()
                    }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
