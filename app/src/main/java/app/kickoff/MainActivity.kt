package app.kickoff

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val KEY_OPEN_MATCH = "openMatch"

/** App notifications on, and the game-alerts channel not switched off (any Android version). */
private fun notificationsOn(context: Context): Boolean {
    val nm = NotificationManagerCompat.from(context)
    return nm.areNotificationsEnabled() &&
        nm.getNotificationChannel(Notifier.CH_GAMES)?.importance != NotificationManager.IMPORTANCE_NONE
}

class MainActivity : ComponentActivity() {
    /** Game to show, set when the app is opened from a notification. */
    private val openMatch = mutableStateOf<String?>(null)
    private val needsSetup = mutableStateOf(false)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On recreation (rotation, process restart) restore the open game instead of re-reading the
        // launch intent, which would reopen an old notification's game.
        if (savedInstanceState != null) openMatch.value = savedInstanceState.getString(KEY_OPEN_MATCH)
        else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                Root(
                    openMatch = openMatch.value,
                    // Only tapping a notification counts as reading it (handleIntent); browsing the list doesn't.
                    onOpenMatch = { id -> openMatch.value = id },
                    needsSetup = needsSetup.value,
                    onFixSetup = ::fixSetup,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_OPEN_MATCH, openMatch.value)
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

    private fun exactAlarms() = Build.VERSION.SDK_INT < 31 ||
        getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun checkSetup() {
        needsSetup.value = !notificationsOn(this) || !exactAlarms()
    }

    private fun fixSetup() {
        val pkg = Uri.parse("package:$packageName")
        val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        // Each step lists screens to try in order; some phone makers remove or block the first one.
        val tries = when {
            !hasNotificationPermission() || !NotificationManagerCompat.from(this).areNotificationsEnabled() ->
                listOf(appNotifications)
            !notificationsOn(this) -> listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_GAMES),
                appNotifications,
            )
            Build.VERSION.SDK_INT >= 31 -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            else -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        tries.firstOrNull { runCatching { startActivity(it) }.isSuccess }
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
            // Run again if teams changed mid-refresh (e.g. two teams added quickly).
            do {
                val teams = Store.state.value.teams.map { it.id }
                withContext(Dispatchers.IO) { Tracker.refresh(context) }
            } while (Store.state.value.teams.map { it.id } != teams)
            refreshing = false
        }
    }

    LaunchedEffect(state.teams.map { it.id }) { refresh() }

    // While the app is on screen, keep live scores fresh. In the background, Tracker's per-minute
    // alarm does the polling (for alerts), so no service or permanent notification is needed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (Tracker.gamesToWatch().isNotEmpty()) withContext(Dispatchers.IO) { runCatching { Tracker.pollOnce(context) } }
                delay(30_000)
            }
        }
    }

    val match = openMatch?.let { state.matches[it] }
    if (match != null) {
        BackHandler { onOpenMatch(null) }
        MatchScreen(match, state.teams, state.showScores, state.channelNames, onBack = { onOpenMatch(null) })
    } else {
        HomeScreen(state, refreshing, ::refresh, onOpenMatch, needsSetup, onFixSetup)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dividerFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private fun Match.zoned() = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault())
private fun Match.localTime() = dayFmt.format(zoned())
/** Kickoff time only: cards sit under a divider that already names the day. */
private fun Match.kickoffTime() = if (timeValid) timeFmt.format(zoned()) else "Time TBD"

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
    var removing by remember { mutableStateOf<Team?>(null) }
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
                        Text("Notifications and exact alarms are needed so kickoff alerts aren't delayed.")
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
                                Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp).clickable { removing = team })
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
            games.groupBy { it.zoned().toLocalDate() }.forEach { (day, dayGames) ->
                item(key = "day-$day") { DayDivider(dividerFmt.format(day)) }
                items(dayGames, key = { it.id }) { m -> GameCard(m, state.teams, state.showScores, state.channelNames) { onOpenMatch(m.id) } }
            }
            item {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show scores")
                        Text("Final scores and scores in alerts. Off avoids spoilers. Live games always show the score.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.showScores, onCheckedChange = { v -> Store.update { it.copy(showScores = v) } })
                }
                OutlinedButton(onClick = {
                    // Separate id, so it never replaces a real game's alert or marks that game read.
                    val sample = (games.firstOrNull() ?: Match("", now, "Real Madrid", "Valencia", league = "LALIGA",
                        broadcasts = listOf(Broadcast("ESPN+", "STREAMING")))).copy(id = "test")
                    if (notificationsOn(context)) {
                        Notifier.gameAlert(context, sample, Phase.FIRST_HALF, false)
                    } else {
                        Toast.makeText(context, "Notifications are off for Kickoff. Tap Fix above.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Send a test notification") }
                Text(
                    "Alerts: kickoff always; 2nd half and full time only until you open one of that game's alerts. " +
                        "Channels come from SofaScore (every country) and ESPN (US)." +
                        state.tvStatus.ifBlank { null }?.let { "\nSofaScore check: $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (showSearch) TeamSearchDialog(onDismiss = { showSearch = false })
    removing?.let { team ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop following ${team.name}?") },
            text = { Text("You won't get alerts for its games anymore.") },
            confirmButton = {
                TextButton(onClick = {
                    // Drop its games right away too, so a game being tracked live stops alerting.
                    Store.update { s ->
                        val teams = s.teams.filter { it.id != team.id }
                        val ids = teams.map { it.id }.toSet()
                        val matches = s.matches.filterValues { m -> m.teamIds.any { it in ids } }
                        s.copy(teams = teams, matches = matches, alerts = s.alerts.filterKeys { it in matches })
                    }
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GameCard(m: Match, teams: List<Team>, showScores: Boolean, names: Map<Int, String>, onClick: () -> Unit) {
    val live = m.state == "in"
    val background = teamColor(m, teams)
    // Dark text on light team colors, white on dark ones (0.18 is where the two contrast equally).
    val content = when {
        background == null -> MaterialTheme.colorScheme.onSurface
        background.luminance() > 0.18f -> Color(0xFF1A1A1A)
        else -> Color.White
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (background != null) CardDefaults.cardColors(containerColor = background, contentColor = content)
        else CardDefaults.cardColors(),
        // The outline keeps white cards visible on a light screen; a thick one marks a live game.
        border = if (live) BorderStroke(3.dp, content)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.league, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (live) LiveBadge(m.statusLabel)
                else Text(m.statusLabel.ifEmpty { m.kickoffTime() }, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(scoreLine(m, showScores), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("📺 " + Streaming.summary(m, names).ifEmpty { "Broadcaster not listed yet" }, style = MaterialTheme.typography.bodyLarge,
                color = content.copy(alpha = 0.8f))
        }
    }
}

/** White text on a red rounded rectangle, e.g. "LIVE 63'" or "HT". */
@Composable
private fun LiveBadge(label: String) {
    Text(
        label,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            // The white edge keeps the badge visible on red team colors.
            .border(1.dp, Color.White, RoundedCornerShape(6.dp))
            .background(Color(0xFFD32F2F), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A line across the list with the day of the games below it, e.g. "Thu, Nov 5". */
@Composable
private fun DayDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** Color of the first followed team in this game (the order they appear in the Teams list). */
private fun teamColor(m: Match, teams: List<Team>): Color? {
    val hex = teams.firstOrNull { it.id in m.teamIds }?.color.orEmpty()
    return runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
}

/** Live games always show the score; finished ones only with "Show scores" on. */
private fun scoreLine(m: Match, showScores: Boolean) =
    if ((m.state == "in" || (showScores && m.state == "post")) && m.homeScore != null) "${m.home} ${m.homeScore} – ${m.awayScore} ${m.away}"
    else m.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchScreen(m: Match, teams: List<Team>, showScores: Boolean, names: Map<Int, String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val groups = Streaming.byCountry(m, names)
    val mine = groups.firstOrNull { it.first == Streaming.myCountryCode }?.second.orEmpty()
    val others = groups.filter { it.first != Streaming.myCountryCode }
        .sortedBy { Streaming.countryName(it.first) }
    // Big games list dozens of countries; only open that list by default when it's short. Decided
    // from what's listed now (names keep loading), until the user taps the toggle.
    var showOthersChoice by remember(m.id) { mutableStateOf<Boolean?>(null) }
    val showOthers = showOthersChoice ?: (mine.isEmpty() || others.sumOf { it.second.size } <= 8)
    // Only this phone's country's channel names are fetched in the background; load the rest now.
    var loadingNames by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id) {
        val known = Store.state.value.channelNames
        val countries = m.tvIds.filterValues { ids -> ids.any { it !in known } }.keys
        if (countries.isEmpty()) return@LaunchedEffect
        loadingNames = true
        val learned = Sofa.namesFor(countries)
        Store.update { s ->
            val used = s.matches.values.flatMap { it.tvIds.values.flatten() }.toSet()
            s.copy(channelNames = s.channelNames + learned.filterKeys { it in used })
        }
        loadingNames = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Where to watch") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(m.league, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(scoreLine(m, showScores), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(listOf(m.statusLabel, m.localTime()).filter { it.isNotBlank() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (groups.isEmpty() && !loadingNames) Text("No channel listed for this game yet.")
            if (loadingNames) Text("Loading channels…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mine.isNotEmpty()) {
                Text("In ${Streaming.myCountry}", style = MaterialTheme.typography.titleSmall)
                ChannelCards(mine)
            }
            if (others.isNotEmpty()) {
                if (mine.isNotEmpty()) TextButton(onClick = { showOthersChoice = !showOthers }) {
                    Text(if (showOthers) "Hide other countries" else "Show other countries (${others.size})")
                }
                if (showOthers) others.forEach { (country, list) ->
                    Text(Streaming.countryName(country), style = MaterialTheme.typography.titleSmall)
                    ChannelCards(list)
                }
            }
            // Opens in the browser: a second opinion when SofaScore is missing a channel.
            TextButton(onClick = { Streaming.openUrl(context, Streaming.liveSoccerTvUrl(m, teams)) }) {
                Text("See channels in every country on Live Soccer TV")
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
private fun ChannelCards(channels: List<Channel>) {
    val context = LocalContext.current
    // Names show right away; buttons appear once the (slow) installed-app check finishes off the main
    // thread. Re-checked each time the screen opens, so a newly installed app gets its button.
    val options by produceState(channels.map { WatchOption(it.name, it.country, null) }, channels) {
        value = withContext(Dispatchers.IO) { Streaming.options(context, channels) }
    }
    options.forEach { o ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Only apps installed on this phone get a button; the rest are listed for info.
                o.launch?.let { launch ->
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { Streaming.open(context, launch) }) { Text("Open ${o.name}") }
                }
            }
        }
    }
}

@Composable
private fun TeamSearchDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Team>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val followed = Store.state.collectAsStateWithLifecycle().value.teams.map { it.id }.toSet()
    LaunchedEffect(query) {
        failed = false
        if (query.trim().length < 3) { results = emptyList(); loading = false; return@LaunchedEffect }
        delay(400)
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching { Espn.searchTeams(query.trim()) } }
        results = r.getOrDefault(emptyList())
        failed = r.isFailure
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a team") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(query, { query = it }, label = { Text("Team name") }, singleLine = true)
                val hint = when {
                    loading -> "Searching…"
                    failed -> "Couldn't search. Check your connection."
                    query.trim().length >= 3 && results.isEmpty() -> "No soccer teams found."
                    else -> null
                }
                hint?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                results.forEach { t ->
                    val already = t.id in followed
                    Column(Modifier.fillMaxWidth().clickable(enabled = !already) {
                        Store.update { s -> if (s.teams.any { it.id == t.id }) s else s.copy(teams = s.teams + t) }
                        onDismiss()
                    }.padding(vertical = 10.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(t.league, if (already) "Following" else "").filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
