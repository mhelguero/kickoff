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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
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

    // The home screen's state (tab, scroll position, open sections) is kept while a game's page
    // is open, so Back returns to exactly where the user was.
    val saved = rememberSaveableStateHolder()
    val match = openMatch?.let { state.matches[it] }
    if (match != null) {
        BackHandler { onOpenMatch(null) }
        MatchScreen(match, onBack = { onOpenMatch(null) })
    } else {
        saved.SaveableStateProvider("home") {
            HomeScreen(state, refreshing, ::refresh, onOpenMatch, needsSetup, onFixSetup)
        }
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
    var endedOpen by rememberSaveable { mutableStateOf(false) }
    var editingTeams by rememberSaveable { mutableStateOf(false) }

    // Games or News tab. News loads when its tab is shown (again after 15 minutes or a team change).
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // News for one team only (its ESPN id), or every team when null.
    var newsTeam by rememberSaveable { mutableStateOf<String?>(null) }
    // Highlights (videos) or News (articles); highlights first.
    var showHighlights by rememberSaveable { mutableStateOf(true) }
    val news by News.items.collectAsStateWithLifecycle()
    val newsLoading by News.loading.collectAsStateWithLifecycle()
    val missingHighlights by News.missing.collectAsStateWithLifecycle()
    val teamKey = state.teams.map { "${it.id}:${it.fotmobId}" }
    var newsFor by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(tab, teamKey) {
        if (tab == 1 && (newsFor != teamKey || System.currentTimeMillis() - News.lastLoad > 15 * 60_000L)) {
            newsFor = teamKey
            News.load(context, state.teams)
        }
    }

    // App updates from GitHub, checked each time the app comes to the screen and on swipe-to-refresh.
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    // When an update is found, scroll up so its banner (the list's first item) is in view.
    val listState = rememberLazyListState()
    // Once per version (not again on coming back from a game's page).
    var bannerShownFor by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(update) {
        val u = update ?: return@LaunchedEffect
        if (u.versionCode != bannerShownFor) { bannerShownFor = u.versionCode; listState.animateScrollToItem(0) }
    }
    // The teams section opens above the tabs: scroll up so it's in view (the list otherwise keeps
    // its place, leaving the new section just off the top of the screen).
    // Only when Edit teams is tapped, not again on coming back from a game's page.
    var editScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editingTeams) {
        if (editingTeams && !editScrolled) listState.animateScrollToItem(0)
        editScrolled = editingTeams
    }
    var updating by remember { mutableStateOf(false) }
    val autoTried = remember { mutableSetOf<Long>() }   // versions installed automatically on opening
    val scope = rememberCoroutineScope()
    suspend fun checkUpdate() {
        // A failed check keeps any banner already showing.
        withContext(Dispatchers.IO) { runCatching { Updater.checkForUpdate(context) } }
            .onSuccess { update = it }
            .onFailure { Log.w("Kickoff", "update check failed", it) }
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
            // Downloading, then handing the file to Android's installer, which then asks to confirm.
            val file = withContext(Dispatchers.IO) { runCatching { Updater.download(context, release) } }
            file.onSuccess { f ->
                Updater.reopenAfterUpdate(context)
                withContext(Dispatchers.IO) { runCatching { Updater.install(context, f) } }
                    .onFailure { Toast.makeText(context, "Couldn't start the update. Try again.", Toast.LENGTH_LONG).show() }
            }.onFailure { Toast.makeText(context, "Couldn't download the update. Try again.", Toast.LENGTH_LONG).show() }
            updating = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Updater.onScreen = true
            try {
                checkUpdate()
                // Opening the app with an update out installs it right away (once per version per
                // run, so a cancelled confirmation doesn't come back at every return to the app).
                update?.let { release ->
                    if (release.versionCode !in autoTried && !updating && Updater.canInstall(context)) {
                        autoTried += release.versionCode
                        installUpdate(release)
                    }
                }
                awaitCancellation()
            } finally {
                // Left the screen: a new version installs by itself moments later (Android 12+),
                // unless one was already started on screen (Android's confirmation is up, say).
                Updater.onScreen = false
                val pending = update
                if (pending != null && !updating && pending.versionCode !in autoTried) Updater.scheduleAutoUpdate(context)
            }
        }
    }
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val sorted = state.matches.values.sortedBy { it.dateMillis }
    val live = sorted.filter { it.state == "in" }
    // Not started yet, today or later.
    val upcoming = sorted.filter { it.state == "pre" && !it.zoned().toLocalDate().isBefore(today) }
    // Played since the start of last calendar week (Sunday), newest first; postponed games never happened.
    val endedSince = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY)).minusDays(7)
    val ended = sorted.filter { m -> m.state == "post" && m.completed && !m.zoned().toLocalDate().isBefore(endedSince) }.reversed()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kickoff", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Image(painterResource(R.drawable.ball), contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }, actions = {
                // The followed teams (remove, add) show only while editing.
                TextButton(onClick = { editingTeams = !editingTeams }) { Text(if (editingTeams) "Done" else "Edit teams") }
            })
        },
    ) { pad ->
        // Swipe down to refresh.
        PullToRefreshBox(
            isRefreshing = refreshing || (tab == 1 && newsLoading),
            onRefresh = {
                onRefresh()
                if (tab == 1) scope.launch { News.load(context, state.teams) }
                scope.launch { checkUpdate() }
            },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(
                // Phone width even sideways or on a tablet: centered, not stretched across the screen.
                Modifier.fillMaxSize().wrapContentWidth().widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().pointerInput(Unit) {
                    // Swipe left for Feed, right for Games (vertical scrolling is unaffected). A short
                    // swipe is enough: the tab switches as soon as it's dragged far enough, or on
                    // lifting after a quick flick.
                    val distance = 36.dp.toPx()
                    val flickDistance = 12.dp.toPx()
                    val flickSpeed = 600.dp.toPx()   // per second
                    var dragged = 0f
                    var switched = false
                    val velocity = VelocityTracker()
                    fun switchTo(left: Boolean) { tab = if (left) 1 else 0; switched = true }
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f; switched = false; velocity.resetTracking() },
                        onHorizontalDrag = { change, dx ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            dragged += dx
                            if (!switched && kotlin.math.abs(dragged) > distance) switchTo(dragged < 0)
                        },
                        onDragEnd = {
                            val vx = velocity.calculateVelocity().x
                            if (!switched && kotlin.math.abs(dragged) > flickDistance && kotlin.math.abs(vx) > flickSpeed) switchTo(vx < 0)
                        },
                    )
                },
                state = listState,
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
                // With no teams there is nothing else to show, so the list is open.
                if (editingTeams || state.teams.isEmpty()) item {
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
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Feed", fontWeight = FontWeight.Bold) })
                    }
                }
                if (tab == 1) {
                    // A removed team's filter falls back to all teams.
                    val only = newsTeam?.takeIf { id -> state.teams.any { it.id == id } }
                    // Highlights or News on their own row; combines with the team choice below it.
                    item(key = "news-kind") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = showHighlights, onClick = { showHighlights = true }, label = { Text("Highlights") })
                            FilterChip(selected = !showHighlights, onClick = { showHighlights = false }, label = { Text("News") })
                        }
                    }
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
                    val shown = news.filter { n ->
                        state.teams.any { it.id == n.teamId } && (only == null || n.teamId == only) && n.isHighlight == showHighlights
                    }
                    if (shown.isEmpty()) item {
                        Hint(when {
                            newsLoading -> "Loading news…"
                            showHighlights -> "No highlights from the last 2 weeks."
                            else -> "No news from the last week."
                        })
                    }
                    // Recent games without highlights, and why (no video yet, search failed, ...).
                    if (showHighlights) {
                        missingHighlights.filter { n -> state.teams.any { it.id == n.teamId } && (only == null || n.teamId == only) }
                            .forEachIndexed { i, n ->
                                item(key = "missing-$i") {
                                    Text("${n.game}: ${n.reason}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                    }
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
                    // Played since the start of last week (Sunday), newest first, folded away until tapped.
                    if (ended.isNotEmpty()) {
                        item(key = "ended-header") {
                            Column(Modifier.fillMaxWidth().clickable { endedOpen = !endedOpen }.padding(top = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Ended", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    Icon(if (endedOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        if (endedOpen) "Hide ended games" else "Show ended games")
                                }
                                SectionLine()
                            }
                        }
                        if (endedOpen) ended.groupBy { it.zoned().toLocalDate() }.forEach { (day, dayGames) ->
                            item(key = "ended-day-$day") { DayDivider(dividerFmt.format(day)) }
                            items(dayGames, key = { "ended-" + it.id }) { m -> GameCard(m, state.teams) { onOpenMatch(m.id) } }
                        }
                    }
                    item(key = "upcoming-header") { Column { SectionHeader("Upcoming"); SectionLine() } }
                    if (upcoming.isEmpty()) item { Hint(if (refreshing) "Loading…" else "No upcoming games found.") }
                    upcoming.groupBy { it.zoned().toLocalDate() }.forEach { (day, dayGames) ->
                        item(key = "day-$day") { DayDivider(dividerFmt.format(day)) }
                        items(dayGames, key = { it.id }) { m -> GameCard(m, state.teams, state.alerts[m.id]?.muted == true) { onOpenMatch(m.id) } }
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
                // The followed team's crest (the one whose color the card has), then the competition.
                teams.firstOrNull { it.id in m.teamIds }?.let { TeamCrest(it.id, background, size = 40.dp, gap = 12.dp) }
                Text(m.league, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                // Just the minute ("63'") or "HT": the Live section already says it's live.
                if (live) LiveBadge(m.statusLabel.removePrefix("LIVE ").trim())
                // As big as the alerts bell beside it, and bold.
                else Text(m.statusLabel.ifEmpty { m.kickoffTime() }, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
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
            TeamsLine(m)
            // Where to watch only matters before and during the game.
            if (m.state != "post") {
                Spacer(Modifier.height(8.dp))
                Text("📺 " + Streaming.summary(m, Streaming.DOT).ifEmpty { "Broadcaster not listed yet" }, style = MaterialTheme.typography.bodyLarge,
                    color = content.copy(alpha = 0.8f))
            }
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


/** A full-width line under a section's title (white in dark mode). */
@Composable
private fun SectionLine() {
    HorizontalDivider(Modifier.fillMaxWidth().padding(top = 6.dp), thickness = 1.5.dp, color = MaterialTheme.colorScheme.onSurface)
}

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
            TeamCrest(n.teamId, background)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The app a tap opens (e.g. the browser, or ESPN or FotMob for their own links).
                    val appIcon by produceState<ImageBitmap?>(null, n.url) {
                        value = withContext(Dispatchers.IO) { LinkApps.icon(context, n.url) }
                    }
                    appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    // Highlights: the competition next to the icon ("Champions League").
                    if (n.isHighlight && n.writer.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(n.writer, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    // Highlights: the video's length ("9:42"); articles (or a video whose length
                    // isn't known): when it was posted.
                    val corner = n.durationSecs?.let(::videoLength)
                        ?: timeFmt.format(Instant.ofEpochMilli(n.timeMillis).atZone(ZoneId.systemDefault()))
                    Text(corner, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(4.dp))
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // Articles: writer and outlet, e.g. "Sam Marsden • ESPN". Highlights have no writer line.
                if (!n.isHighlight) {
                    Spacer(Modifier.height(4.dp))
                    Text(listOf(n.writer, n.source).filter { it.isNotBlank() }.distinct().joinToString(Streaming.DOT),
                        style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** Content stays phone-width (a large phone in portrait) when the phone is sideways. */
private val MAX_CONTENT_WIDTH = 480.dp

/** A video's length: "9:42", or "1:02:05" past an hour. */
private fun videoLength(secs: Int): String {
    val h = secs / 3600
    val m = secs % 3600 / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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

/**
 * A team's crest from ESPN (whose team ids the app uses), [size] across with [gap] after it;
 * nothing if it can't load. A dark crest that can't be seen on its card ([background]: the team
 * color, or the default card color) sits on a white circle, e.g. Juventus' black crest on its
 * black card. Every other crest is shown as it is.
 */
@Composable
private fun TeamCrest(teamId: String, background: Color?, size: Dp = 48.dp, gap: Dp = 12.dp) {
    val context = LocalContext.current
    val url = "https://a.espncdn.com/i/teamlogos/soccer/500/$teamId.png"
    val crest by produceState<Pair<ImageBitmap, Float>?>(null, url) {
        value = withContext(Dispatchers.IO) { Logos.load(context, url)?.let { it to Logos.tone(url, it) } }
    }
    val cardTone = (background ?: CardDefaults.cardColors().containerColor).luminance()
    crest?.let { (image, tone) ->
        // Contrast ratio (as for text): under 1.6, a crest no lighter than mid-gray is lost on its card.
        val contrast = (maxOf(tone, cardTone) + 0.05f) / (minOf(tone, cardTone) + 0.05f)
        val hidden = tone < 0.2f && contrast < 1.6f
        Image(image, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = if (hidden) Modifier.size(size).background(Color.White, CircleShape).padding(size / 8)
            else Modifier.size(size))
        Spacer(Modifier.width(gap))
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
/**
 * The same layout on every card: home team on the left, the score (or "vs") in the middle, away
 * team on the right, each name wrapping on its own side.
 */
@Composable
private fun TeamsLine(m: Match) {
    val style = MaterialTheme.typography.titleLarge
    val middle = if (m.state != "pre" && m.homeScore != null) "${m.homeScore} – ${m.awayScore}" else "vs"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.home, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Text(middle, style = style, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp).widthIn(min = 56.dp))
        Text(m.away, style = style, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun scoreLine(m: Match) =
    if (m.state != "pre" && m.homeScore != null) "${m.home} ${m.homeScore} – ${m.awayScore} ${m.away}"
    else m.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchScreen(m: Match, onBack: () -> Unit) {
    val context = LocalContext.current
    // ESPN and ESPN+ are one app, so one row here (cards still list both).
    val channels = Streaming.channels(m).map { if (it.name == "ESPN+") it.copy(name = "ESPN") else it }.distinct()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (m.state == "post") "Game" else "Where to watch") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .wrapContentWidth().widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(m.league, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(scoreLine(m), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(listOf(m.statusLabel, m.localTime()).filter { it.isNotBlank() }.joinToString(Streaming.DOT),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Finished games: no channels, just the lineups (and who was out).
            if (m.state != "post") {
                HorizontalDivider()
                if (channels.isEmpty()) Text("No US channel listed for this game yet.")
                else {
                    // Other countries' channels carry their flag; say so when there's no US one.
                    if (channels.none { it.country == Streaming.COUNTRY }) Text("No US channel listed yet. Shown in other countries on:")
                    ChannelCards(channels, m)
                }
                TextButton(onClick = { Streaming.openUrl(context, Streaming.findMoreUrl(m)) }) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search for other ways to watch")
                }
            }
            LineupSection(m)
        }
    }
}

/**
 * Lineups once announced (FotMob gets them about an hour before kickoff), and who's out (injuries,
 * suspensions) as soon as FotMob knows. Nothing at all when FotMob has neither (e.g. Bolivian games).
 * Close to kickoff, checked again every 2 minutes until the lineups are in.
 */
@Composable
private fun LineupSection(m: Match) {
    val details by produceState<FotMob.Details?>(null, m.id, m.fotmobId) {
        // Games found only through ESPN's results (e.g. last week's) don't know their FotMob id yet:
        // it's the game of a followed team's FotMob fixtures that kicks off within 3 hours.
        val fid = m.fotmobId.ifBlank {
            withContext(Dispatchers.IO) {
                Store.state.value.teams.filter { it.id in m.teamIds && it.fotmobId.isNotBlank() }.firstNotNullOfOrNull { t ->
                    runCatching { FotMob.fixtures(t.fotmobId) }.getOrNull()
                        ?.firstOrNull { kotlin.math.abs(it.startMillis - m.dateMillis) <= 3 * 60 * 60 * 1000L }?.id
                }
            }.orEmpty()
        }
        if (fid.isBlank()) return@produceState
        while (true) {
            withContext(Dispatchers.IO) { runCatching { FotMob.details(fid) }.getOrNull() }?.let { value = it }
            val soon = m.state != "post" && System.currentTimeMillis() > m.dateMillis - 3 * 60 * 60 * 1000L
            if (value?.lineups?.announced == true || !soon) break
            delay(2 * 60 * 1000L)
        }
    }
    // The league table (finished games only), loaded after the rest.
    val table by produceState<List<FotMob.TableRow>>(emptyList(), details?.tableLeague) {
        val d = details ?: return@produceState
        if (m.state != "post" || d.tableLeague == null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { FotMob.table(d.tableLeague, d.teamIds) }.getOrDefault(emptyList()) }
    }
    // The competition's knockout bracket, if it has one with known teams (any game in it).
    val knockout by produceState<List<FotMob.Round>>(emptyList(), details?.leagueId) {
        val league = details?.leagueId ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { FotMob.knockout(league) }.getOrDefault(emptyList()) }
    }
    val d = details ?: return
    // The two teams' colors, for their rows in the table and bracket: a followed team's own card
    // color, else FotMob's color for the team.
    val teams = Store.state.value.teams
    val rowColors = d.colors.mapValues { (fid, hex) ->
        teams.firstOrNull { it.fotmobId == fid }?.let { colorOf(it) }
            ?: runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }.filterValues { it != null }.mapValues { it.value!! }
    if (m.state == "post") {
        if (d.goals.isNotEmpty()) GoalsCard(d.goals)
    }
    val l = d.lineups
    if (l == null) {
        if (table.isNotEmpty()) TableCard(table, rowColors)
        if (knockout.isNotEmpty()) KnockoutCard(knockout, rowColors)
        return
    }
    HorizontalDivider()
    if (l.announced) {
        Text("Lineups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // The game's best rating (both teams, subs too) is the one shown in blue.
        val best = listOf(l.home, l.away).flatMap { it.starters + it.bench }.mapNotNull { it.rating }.maxOrNull()
        // Goals (own goals aside) and assists per player, for the ball and boot icons.
        val tally = Tally(
            d.goals.filter { it.note != "OG" && it.scorerId.isNotBlank() }.groupingBy { it.scorerId }.eachCount(),
            d.goals.filter { it.assistId.isNotBlank() }.groupingBy { it.assistId }.eachCount(),
        )
        listOf(l.home, l.away).forEach { side -> LineupCard(side, best, tally) }
    } else if (m.state == "pre") {
        Text("Lineups are announced about an hour before kickoff.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (l.anyOut) {
        Text("Out", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(l.home, l.away).filter { it.out.isNotEmpty() }.forEach { side ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(side.name, fontWeight = FontWeight.Bold)
                    side.out.forEach { a ->
                        Row {
                            Text(a.name, modifier = Modifier.weight(1f))
                            // e.g. "Injury • Late December 2026"
                            Text(listOf(a.reason, a.back).filter { it.isNotBlank() }.joinToString(Streaming.DOT),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (table.isNotEmpty()) TableCard(table, rowColors)
    if (knockout.isNotEmpty()) KnockoutCard(knockout, rowColors)
}

/** The goals in order: home team's on the left, away team's on the right, with assists. */
@Composable
private fun GoalsCard(goals: List<FotMob.Goal>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Goals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            goals.forEach { g ->
                val text = buildString {
                    append(g.scorer)
                    if (g.note.isNotBlank()) append(" (${g.note})")
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (g.home) Alignment.Start else Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ball), contentDescription = "Goal", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${g.minute}  $text", fontWeight = FontWeight.Bold)
                    }
                    if (g.assist.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_cleats), contentDescription = "Assist", modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(g.assist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * A team's starters in formation on a half pitch: goalkeeper at the bottom, attack at the top.
 * Each player is a shirt-number circle with the name under it, and the rating once played.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Pitch(starters: List<FotMob.Player>, best: Double?, tally: Tally) {
    val lines = Color.White.copy(alpha = 0.35f)
    // A player with two or more goals and assists may need a second row of icons under the rating
    // (icons stay full size and wrap rather than shrink), so the pitch makes room for it.
    val extraRow = if (starters.any { (tally.goals[it.id] ?: 0) + (tally.assists[it.id] ?: 0) >= 2 }) 16.dp else 0.dp
    val top = 26.dp                // room for the front line's photos
    val bottom = 78.dp + extraRow  // room for the goalkeeper's name and rating under its photo
    // Each line of the formation (same height on the pitch), from the goalkeeper up. The pitch is
    // tall enough for a photo, a two-line name and a rating per line, so lines never overlap.
    val lineOf = starters.associateWith { Math.round(it.y!! * 20) }
    val lineCount = lineOf.values.distinct().size
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(top + bottom + (96.dp + extraRow) * (lineCount - 1).coerceAtLeast(1))
            .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E7D32)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = Stroke(width = 2.dp.toPx())
            val w = size.width; val h = size.height
            drawRect(lines, style = stroke)
            // Halfway line's centre circle at the top, penalty and goal areas at the bottom.
            drawArc(lines, 0f, 180f, false, Offset(w * 0.35f, -w * 0.15f), Size(w * 0.3f, w * 0.3f), style = stroke)
            drawRect(lines, Offset(w * 0.2f, h * 0.84f), Size(w * 0.6f, h * 0.16f), style = stroke)
            drawRect(lines, Offset(w * 0.36f, h * 0.94f), Size(w * 0.28f, h * 0.06f), style = stroke)
        }
        val ys = starters.mapNotNull { it.y }
        val (lowY, highY) = (ys.minOrNull() ?: 0f) to (ys.maxOrNull() ?: 1f)
        // A name may be as wide as the gap to its nearest neighbour in its line, so names in a
        // crowded line wrap instead of overlapping.
        val widthOf = starters.associateWith { p ->
            val xs = starters.filter { lineOf[it] == lineOf[p] }.map { it.x!! }.sorted()
            val gap = xs.zipWithNext { a, b -> b - a }.minOrNull() ?: 1f
            minOf(maxWidth / 4.3f, maxWidth * gap - 4.dp)
        }
        starters.forEach { p ->
            val markerWidth = widthOf.getValue(p)
            val depth = if (highY > lowY) (p.y!! - lowY) / (highY - lowY) else 0f
            val centerX = (maxWidth * p.x!!).coerceIn(markerWidth / 2, maxWidth - markerWidth / 2)
            val centerY = maxHeight - bottom - (maxHeight - top - bottom) * depth
            Column(
                Modifier.width(markerWidth).offset(x = centerX - markerWidth / 2, y = centerY - 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerPhoto(p)
                Text(p.short, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Icons that don't fit beside the rating go on the next row, at full size.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    p.rating?.let { RatingBadge(it, best, Modifier.align(Alignment.CenterVertically)) }
                    GoalIcons(p, tally, Color.White, Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

/** Goals and assists per FotMob player id. */
private data class Tally(val goals: Map<String, Int>, val assists: Map<String, Int>)

/** A ball per goal and a boot per assist, like the goals list. */
@Composable
private fun GoalIcons(p: FotMob.Player, tally: Tally, bootTint: Color, modifier: Modifier = Modifier) {
    repeat(tally.goals[p.id] ?: 0) {
        Image(painterResource(R.drawable.ball), contentDescription = "Goal", modifier = modifier.size(14.dp))
    }
    repeat(tally.assists[p.id] ?: 0) {
        Icon(painterResource(R.drawable.ic_cleats), contentDescription = "Assist", modifier = modifier.size(14.dp), tint = bootTint)
    }
}

/** The player's photo in a white circle with the shirt number in a corner; just the number until (or unless) it loads. */
@Composable
private fun PlayerPhoto(p: FotMob.Player) {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, p.id) {
        if (p.id.isNotBlank()) value = withContext(Dispatchers.IO) { Logos.load(context, p.photo) }
    }
    Box(Modifier.size(38.dp)) {
        Box(Modifier.size(36.dp).align(Alignment.Center).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            val image = photo
            if (image != null) Image(image, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(p.number, color = Color(0xFF1B5E20), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        if (photo != null && p.number.isNotBlank()) {
            Box(Modifier.size(17.dp).align(Alignment.BottomEnd).background(Color(0xFF1B5E20), CircleShape),
                contentAlignment = Alignment.Center) {
                // No font padding and a line exactly as tall as the digits, so they sit in the middle.
                Text(p.number, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 9.sp, lineHeight = 9.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)))
            }
        }
    }
}

/** A FotMob rating: blue for the game's best ([best]), green for good, orange and red for poor. */
@Composable
private fun RatingBadge(rating: Double, best: Double?, modifier: Modifier = Modifier, big: Boolean = false) {
    val color = when {
        best != null && rating >= best -> Color(0xFF1E88E5)
        rating >= 7.0 -> Color(0xFF2E9E4F)
        rating >= 6.0 -> Color(0xFFF2A600)
        else -> Color(0xFFE5533D)
    }
    Text(
        "%.1f".format(rating),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        modifier = modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The whole table of the teams' league, or of their group/conference when it has several. The
 * game's two teams' rows are filled with their colors.
 */
@Composable
private fun TableCard(rows: List<FotMob.TableRow>, colors: Map<String, Color>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Table", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text("#", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium)
                Text("Team", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                listOf("P", "GD", "Pts").forEach { Text(it, Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium) }
            }
            rows.forEach { r ->
                val fill = colors[r.teamId]
                val ink = if (fill != null) contentOn(fill) else LocalContentColor.current
                val w = if (fill != null) FontWeight.Bold else null
                Row(Modifier.fillMaxWidth().background(fill ?: Color.Transparent).padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Text("${r.pos}", Modifier.width(28.dp), fontWeight = w, color = ink)
                    Text(r.team, Modifier.weight(1f), fontWeight = w, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.played}", Modifier.width(36.dp), fontWeight = w, color = ink)
                    Text(if (r.goalDiff > 0) "+${r.goalDiff}" else "${r.goalDiff}", Modifier.width(36.dp), fontWeight = w, color = ink)
                    Text("${r.points}", Modifier.width(36.dp), fontWeight = FontWeight.Bold, color = ink)
                }
            }
        }
    }
}

/** The knockout rounds: each tie with its score once started (winner in bold), else its first date. */
@Composable
private fun KnockoutCard(rounds: List<FotMob.Round>, colors: Map<String, Color>) {
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Knockout", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp))
            rounds.forEach { round ->
                Text(round.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                round.ties.forEach { t ->
                    // A tie with one of the game's teams is filled with that team's color (the followed
                    // team's when both are in it).
                    val followed = Store.state.value.teams.map { it.fotmobId }
                    val inTie = listOf(t.homeId, t.awayId).filter { it in colors }
                    val fill = (inTie.firstOrNull { it in followed } ?: inTie.firstOrNull())?.let { colors[it] }
                    val ink = if (fill != null) contentOn(fill) else LocalContentColor.current
                    Column(Modifier.fillMaxWidth().background(fill ?: Color.Transparent).padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.home, Modifier.weight(1f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (t.winnerId == t.homeId) FontWeight.Bold else null)
                            Text(if (t.homeScore != null) "${t.homeScore} – ${t.awayScore}" else "vs", color = ink,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(t.away, Modifier.weight(1f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End, fontWeight = if (t.winnerId == t.awayId) FontWeight.Bold else null)
                        }
                        val note = listOfNotNull(
                            if (t.bestOf > 1) "Best of ${t.bestOf}" else null,
                            if (t.homeScore == null) t.firstGameMillis?.let { dateFmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } else null,
                        ).joinToString(Streaming.DOT)
                        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}

/** One team's starting XI with shirt numbers, then its coach and bench. */
@Composable
private fun LineupCard(side: FotMob.Side, best: Double?, tally: Tally) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(side.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (side.formation.isNotBlank()) Text(side.formation, fontWeight = FontWeight.Bold)
            }
            // On a pitch in their formation when FotMob places them, else as a list.
            if (side.starters.all { it.x != null && it.y != null }) Pitch(side.starters, best, tally)
            else side.starters.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.number, modifier = Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The name wraps before the icons shrink.
                    Row(Modifier.weight(1f).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(4.dp))
                        GoalIcons(p, tally, LocalContentColor.current)
                    }
                    // Finished games: each player's FotMob rating.
                    p.rating?.let { RatingBadge(it, best) }
                }
            }
            if (side.coach.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Coach: ${side.coach}", style = MaterialTheme.typography.bodySmall)
            }
            // Subs who came on, in the order they did (a late sub may have no rating), then the
            // rest of the bench.
            val cameOn = side.bench.filter { it.cameOn != null || it.rating != null }.sortedBy { it.cameOn ?: Int.MAX_VALUE }
            cameOn.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.number, modifier = Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.name} (sub${p.cameOn?.let { " $it'" }.orEmpty()})", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(4.dp))
                        GoalIcons(p, tally, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    p.rating?.let { RatingBadge(it, best) }
                }
            }
            val unused = side.bench.filter { it !in cameOn }
            if (unused.isNotEmpty()) {
                Text("Bench: " + unused.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(Streaming.label(Channel(o.name, o.country)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
