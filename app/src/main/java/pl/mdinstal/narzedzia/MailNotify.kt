package pl.mdinstal.narzedzia

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

const val EXTRA_OPEN_MAIL = "open_mail"
private const val MAIL_CHANNEL = "mail_new"
private const val WORK_NAME = "mail_poll"

fun canNotifyMail(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

object MailNotify {
    /** Sprawdzanie skrzynki co ok. 15 minut (najkrótszy okres WorkManagera) + sprawdzanie przy włączeniu ekranu ([MailFast]). */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        MailFast.ensure(ctx)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        MailFast.stop(ctx)
    }

    private val checkLock = Mutex()

    /** Jedno sprawdzenie skrzynki; wywoływane przez WorkManagera i przez usługę szybkiego sprawdzania. */
    suspend fun checkOnce(c: Context): Unit = checkLock.withLock {
        if (!Prefs.mailNotify(c)) return@withLock
        val account = Prefs.mailAccount(c)
        if (account.isBlank()) return@withLock
        try {
            val auth = GmailApi.authorize(c, account)
            // Bez zgody w tle nie da się nic zrobić — czekamy aż użytkownik wejdzie w Pocztę.
            if (auth !is DriveAuth.Token) return@withLock
            val token = auth.token
            val last = Prefs.mailHistoryId(c)
            if (last.isEmpty()) {
                Prefs.setMailHistoryId(c, GmailApi.profileHistoryId(token))
                return@withLock
            }
            val found = try {
                GmailApi.newInboxMessages(token, last)
            } catch (e: java.io.IOException) {
                if (e.message?.contains("HTTP 404") == true) {
                    // Zbyt stary punkt odniesienia — zaczynamy od nowa, bez powiadamiania o zaległościach.
                    Prefs.setMailHistoryId(c, GmailApi.profileHistoryId(token))
                    return@withLock
                }
                throw e
            }
            Prefs.setMailHistoryId(c, found.second)
            val mine = account.lowercase()
            val ids = found.first.distinct()
            if (ids.isEmpty()) return@withLock
            val rows = ids.take(5).mapNotNull { id ->
                try { GmailApi.messageRow(token, id) } catch (e: Exception) { null }
            }.filter { !it.fromEmail.equals(mine, ignoreCase = true) }
            if (rows.isNotEmpty()) post(c, rows.map { it.row }, maxOf(0, ids.size - rows.size))
        } catch (e: Exception) {
        }
    }

    fun post(ctx: Context, items: List<MailRow>, more: Int) {
        if (!canNotifyMail(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(MAIL_CHANNEL, "Poczta: nowe wiadomości", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MAIL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (items.size == 1 && more == 0) {
                val r = items[0]
                nm.notify(
                    7100,
                    NotificationCompat.Builder(ctx, MAIL_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notify_mail)
                        .setContentTitle(r.who.ifBlank { "Nowa wiadomość" })
                        .setContentText(r.subject)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(r.subject + "\n" + r.snippet))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                )
            } else {
                val total = items.size + more
                val style = NotificationCompat.InboxStyle()
                items.take(5).forEach { style.addLine(it.who.ifBlank { "—" } + ": " + it.subject) }
                nm.notify(
                    7100,
                    NotificationCompat.Builder(ctx, MAIL_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notify_mail)
                        .setContentTitle("Nowe wiadomości: $total")
                        .setContentText(items.first().who.ifBlank { "—" } + ": " + items.first().subject)
                        .setStyle(style)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
                )
            }
        } catch (e: SecurityException) {
        }
    }
}

class MailWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        MailNotify.checkOnce(c)
        // Przy okazji spróbuj podnieść usługę, jeśli system ją ubił.
        MailFast.ensure(c)
        Result.success()
    }
}
