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
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import java.time.LocalDate
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
                withContext(Dispatchers.IO) {
                    Tracker.refresh(context)
                    // Live scores right away too, not on the next 30-second poll.
                    if (Tracker.gamesToWatch().isNotEmpty()) runCatching { Tracker.pollOnce(context) }
                }
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
        MatchScreen(match, onBack = { onOpenMatch(null) })
    } else {
        HomeScreen(state, refreshing, ::refresh, onOpenMatch, needsSetup, onFixSetup)
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a")
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
    var testStep by rememberSaveable { mutableIntStateOf(0) }

    // Games or News tab. News loads when its tab is shown (again after 15 minutes or a team change).
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // News for one team only (its ESPN id), or every team when null.
    var newsTeam by rememberSaveable { mutableStateOf<String?>(null) }
    val news by News.items.collectAsStateWithLifecycle()
    val newsLoading by News.loading.collectAsStateWithLifecycle()
    val teamKey = state.teams.map { "${it.id}:${it.fotmobId}" }
    var newsFor by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(tab, teamKey) {
        if (tab == 1 && (newsFor != teamKey || System.currentTimeMillis() - News.lastLoad > 15 * 60_000L)) {
            newsFor = teamKey
            News.load(state.teams)
        }
    }

    // App updates from GitHub, checked each time the app comes to the screen and on swipe-to-refresh.
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var updating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun checkUpdate() {
        // A failed check keeps any banner already showing.
        withContext(Dispatchers.IO) { runCatching { Updater.checkForUpdate(context) } }
            .onSuccess { update = it }
            .onFailure { Log.w("Kickoff", "update check failed", it) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { checkUpdate() }
    }
    fun installUpdate(release: Updater.Release) {
        // Android asks once to let Kickoff install apps; the user comes back and taps Update again.
        if (!Updater.canInstall(context)) {
            Toast.makeText(context, "Allow Kickoff to install updates, then tap Update again.", Toast.LENGTH_LONG).show()
            runCatching { Updater.openInstallPermission(context) }
            return
        }
        updating = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { Updater.download(context, release) } }
            updating = false
            file.onSuccess { runCatching { Updater.install(context, it) } }
                .onFailure { Toast.makeText(context, "Couldn't download the update. Try again.", Toast.LENGTH_LONG).show() }
        }
    }
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val sorted = state.matches.values.sortedBy { it.dateMillis }
    val live = sorted.filter { it.state == "in" }
    // Played to the end today (postponed/canceled games aren't "ended"); earlier days drop off.
    val ended = sorted.filter { it.state == "post" && it.completed && it.zoned().toLocalDate() == today }
    // Not started yet, today or later.
    val upcoming = sorted.filter { it.state == "pre" && !it.zoned().toLocalDate().isBefore(today) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kickoff", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Image(painterResource(R.drawable.ball), contentDescription = null, modifier = Modifier.size(28.dp))
                }
            })
        },
    ) { pad ->
        // Swipe down to refresh.
        PullToRefreshBox(
            isRefreshing = refreshing || (tab == 1 && newsLoading),
            onRefresh = {
                onRefresh()
                if (tab == 1) scope.launch { News.load(state.teams) }
                scope.launch { checkUpdate() }
            },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                update?.let { release ->
                    item(key = "update") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Kickoff ${release.versionName} is available", fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                if (updating) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Button(onClick = { installUpdate(release) }) { Text("Update") }
                            }
                        }
                    }
                }
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
                item(key = "tabs") {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Games", fontWeight = FontWeight.Bold) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("News", fontWeight = FontWeight.Bold) })
                    }
                }
                if (tab == 1) {
                    // A removed team's filter falls back to all teams.
                    val only = newsTeam?.takeIf { id -> state.teams.any { it.id == id } }
                    if (state.teams.size > 1) item(key = "news-teams") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = only == null, onClick = { newsTeam = null }, label = { Text("All") })
                            state.teams.forEach { team ->
                                // Tapping the selected team again shows all teams.
                                FilterChip(selected = only == team.id,
                                    onClick = { newsTeam = if (only == team.id) null else team.id },
                                    label = { Text(team.name) })
                            }
                        }
                    }
                    // A removed team's headlines go right away, before the next load.
                    val shown = news.filter { n -> state.teams.any { it.id == n.teamId } && (only == null || n.teamId == only) }
                    if (shown.isEmpty()) item { Hint(if (newsLoading) "Loading news…" else "No news from the last week.") }
                    shown.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
                        .forEach { (day, dayNews) ->
                            item(key = "news-day-$day") { DayDivider(dividerFmt.format(day)) }
                            itemsIndexed(dayNews, key = { i, _ -> "news-$day-$i" }) { _, n -> NewsCard(n, state.teams) }
                        }
                } else {
                    if (live.isNotEmpty()) {
                        item(key = "live-header") { SectionHeader("Live") }
                        items(live, key = { it.id }) { m -> GameCard(m, state.teams, state.alerts[m.id]?.muted == true) { onOpenMatch(m.id) } }
                    }
                    if (ended.isNotEmpty()) {
                        item(key = "ended-header") { SectionHeader("Ended") }
                        items(ended, key = { it.id }) { m -> GameCard(m, state.teams) { onOpenMatch(m.id) } }
                    }
                    item(key = "upcoming-header") { SectionHeader("Upcoming") }
                    if (upcoming.isEmpty()) item { Hint(if (refreshing) "Loading…" else "No upcoming games found.") }
                    upcoming.groupBy { it.zoned().toLocalDate() }.forEach { (day, dayGames) ->
                        item(key = "day-$day") { DayDivider(dividerFmt.format(day)) }
                        items(dayGames, key = { it.id }) { m -> GameCard(m, state.teams, state.alerts[m.id]?.muted == true) { onOpenMatch(m.id) } }
                    }
                    item {
                        OutlinedButton(onClick = {
                            // Separate id, so it never replaces a real game's alert or marks that game read.
                            val base = (live + upcoming).firstOrNull() ?: Match("", now, "Real Madrid", "Valencia", league = "LALIGA",
                                tv = mapOf(Streaming.COUNTRY to listOf("ESPN+")))
                            // Each tap shows the next alert of a game: kickoff, 2nd half, full time, then kickoff again.
                            val phase = testPhases[testStep % testPhases.size]
                            val score = when (phase) { Phase.SECOND_HALF -> "1" to "0"; Phase.ENDED -> "2" to "1"; else -> null }
                            val sample = base.copy(id = "test", homeScore = score?.first, awayScore = score?.second)
                            if (notificationsOn(context)) {
                                Notifier.gameAlert(context, sample, phase)
                                testStep++
                            } else {
                                Toast.makeText(context, "Notifications are off for Kickoff. Tap Fix above.", Toast.LENGTH_LONG).show()
                            }
                        }, Modifier.padding(top = 8.dp)) { Text("Send a test notification") }
                    }
                }
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
private fun GameCard(m: Match, teams: List<Team>, muted: Boolean? = null, onClick: () -> Unit) {
    val live = m.state == "in"
    val background = teamColor(m, teams)
    val content = contentOn(background)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (background != null) CardDefaults.cardColors(containerColor = background, contentColor = content)
        else CardDefaults.cardColors(),
        // The outline keeps white cards visible on a light screen.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.league, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                // Just the minute ("63'") or "HT": the Live section already says it's live.
                if (live) LiveBadge(m.statusLabel.removePrefix("LIVE ").trim())
                else Text(m.statusLabel.ifEmpty { m.kickoffTime() }, style = MaterialTheme.typography.labelLarge)
                // This game's alerts on or off; finished games (muted = null) have no more alerts.
                muted?.let { off ->
                    val context = LocalContext.current
                    IconButton(onClick = {
                        Store.setMuted(m.id, !off)
                        Toast.makeText(context, if (off) "Alerts on for ${m.title}" else "Alerts off for ${m.title}",
                            Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(36.dp)) {
                        if (off) Icon(painterResource(R.drawable.ic_bell_off), "Turn alerts on", tint = content.copy(alpha = 0.7f))
                        else Icon(Icons.Default.Notifications, "Turn alerts off", tint = content)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(scoreLine(m), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("📺 " + Streaming.summary(m, Streaming.DOT).ifEmpty { "Broadcaster not listed yet" }, style = MaterialTheme.typography.bodyLarge,
                color = content.copy(alpha = 0.8f))
        }
    }
}

/** White text on a red rounded rectangle, e.g. "63'" or "HT". */
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

private val testPhases = listOf(Phase.FIRST_HALF, Phase.SECOND_HALF, Phase.ENDED)

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A headline, in its team's color; tapping opens the article. */
@Composable
private fun NewsCard(n: NewsItem, teams: List<Team>) {
    val context = LocalContext.current
    val background = colorOf(teams.firstOrNull { it.id == n.teamId })
    val content = contentOn(background)
    Card(
        Modifier.fillMaxWidth().clickable { Streaming.openUrl(context, n.url) },
        colors = if (background != null) CardDefaults.cardColors(containerColor = background, contentColor = content)
        else CardDefaults.cardColors(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Highlight videos show their thumbnail across the top.
        n.image?.let { Thumbnail(it) }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Which team the story is about.
            TeamCrest(n.teamId)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The app a tap opens (e.g. the browser, or ESPN or FotMob for their own links).
                    val appIcon by produceState<ImageBitmap?>(null, n.url) {
                        value = withContext(Dispatchers.IO) { LinkApps.icon(context, n.url) }
                    }
                    appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.weight(1f))
                    Text(timeFmt.format(Instant.ofEpochMilli(n.timeMillis).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(4.dp))
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                // Writer and outlet, e.g. "Sam Marsden • ESPN", or just the outlet.
                Text(listOf(n.writer, n.source).filter { it.isNotBlank() }.distinct().joinToString(Streaming.DOT),
                    style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f))
            }
        }
    }
}

/**
 * A video thumbnail, full width at 16:9; nothing until (or unless) it loads. YouTube's largest
 * size doesn't exist for every video, so its standard size is tried next.
 */
@Composable
private fun Thumbnail(url: String) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            Logos.load(context, url) ?: url.takeIf { "maxresdefault" in it }
                ?.let { Logos.load(context, it.replace("maxresdefault", "hqdefault")) }
        }
    }
    image?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    }
}

/** A team's crest from ESPN (whose team ids the app uses), with room after it; nothing if it can't load. */
@Composable
private fun TeamCrest(teamId: String) {
    val context = LocalContext.current
    val url = "https://a.espncdn.com/i/teamlogos/soccer/500/$teamId.png"
    val crest by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { Logos.load(context, url) }
    }
    crest?.let {
        Image(it, contentDescription = null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(12.dp))
    }
}

/** A team's card color, or null for the default look. */
private fun colorOf(team: Team?): Color? =
    runCatching { Color(android.graphics.Color.parseColor("#${team?.color.orEmpty()}")) }.getOrNull()

/** Dark text on light team colors, white on dark ones (0.18 is where the two contrast equally). */
@Composable
private fun contentOn(background: Color?): Color = when {
    background == null -> MaterialTheme.colorScheme.onSurface
    background.luminance() > 0.18f -> Color(0xFF1A1A1A)
    else -> Color.White
}

/** Color of the first followed team in this game (the order they appear in the Teams list). */
private fun teamColor(m: Match, teams: List<Team>): Color? = colorOf(teams.firstOrNull { it.id in m.teamIds })

/** Live and finished games show the score. */
private fun scoreLine(m: Match) =
    if (m.state != "pre" && m.homeScore != null) "${m.home} ${m.homeScore} – ${m.awayScore} ${m.away}"
    else m.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchScreen(m: Match, onBack: () -> Unit) {
    val context = LocalContext.current
    // ESPN and ESPN+ are one app, so one row here (cards still list both).
    val channels = Streaming.channels(m).map { if (it.name == "ESPN+") it.copy(name = "ESPN") else it }.distinctBy { it.name }
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
            Text(scoreLine(m), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(listOf(m.statusLabel, m.localTime()).filter { it.isNotBlank() }.joinToString(Streaming.DOT),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (channels.isEmpty()) Text("No US channel listed for this game yet.")
            else ChannelCards(channels, m)
            TextButton(onClick = { Streaming.openUrl(context, Streaming.findMoreUrl(m)) }) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search for other ways to watch")
            }
        }
    }
}

@Composable
private fun ChannelCards(channels: List<Channel>, match: Match) {
    val context = LocalContext.current
    // Names show right away; buttons appear once the (slow) installed-app check finishes off the main
    // thread. Re-checked each time the screen opens, so a newly installed app gets its button.
    val options by produceState(channels.map { WatchOption(it.name, it.country, null) }, channels) {
        value = withContext(Dispatchers.IO) { Streaming.options(context, channels, match) }
    }
    options.forEach { o ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Only apps installed on this phone get a button; the rest are listed for info.
                if (o.launch != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { Streaming.open(context, o) }) { Text("Open ${o.name}") }
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
                            listOf(t.league, if (already) "Following" else "").filter { it.isNotBlank() }.joinToString(Streaming.DOT),
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
