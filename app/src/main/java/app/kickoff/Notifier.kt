package app.kickoff

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_GAMES = "games"
    const val EXTRA_MATCH_ID = "matchId"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_GAMES, "Game alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Kickoff, second half and full time" }
        )
        // Older versions showed a permanent "Tracking live" notification on this channel.
        nm.deleteNotificationChannel("watching")
    }

    fun gameAlert(context: Context, match: Match, phase: Int, showScores: Boolean) {
        val score = if (showScores && match.homeScore != null) " (${match.homeScore}–${match.awayScore})" else ""
        val title = when (phase) {
            Phase.FIRST_HALF -> "${match.title} has started!"
            Phase.SECOND_HALF -> "${match.title}$score: 2nd half has started!"
            else -> "${match.title}$score has ended"
        }
        val watch = Streaming.summary(match).let { if (it.isEmpty()) "Tap to see where to watch" else "On $it" }
        val text = listOf(match.league, watch).filter { it.isNotBlank() }.joinToString(" · ")
        post(context, match.id.hashCode(), title, text, openMatchIntent(context, match.id))
    }

    private fun post(context: Context, id: Int, title: String, text: String, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CH_GAMES)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        // One notification per game: a newer alert replaces the older one.
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun openMatchIntent(context: Context, matchId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (matchId != null) i.putExtra(EXTRA_MATCH_ID, matchId)
        return PendingIntent.getActivity(
            context, matchId?.hashCode() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
