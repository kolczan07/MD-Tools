package pl.mdinstal.narzedzia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val SERVICE_CHANNEL = "mail_service"
private const val SERVICE_NOTIF_ID = 7101

/**
 * Sprawdzanie poczty przy każdym włączeniu ekranu. Mała usługa pierwszoplanowa tylko trzyma odbiornik
 * ACTION_SCREEN_ON (od Androida 8 nie da się go zadeklarować w manifeście). Brak pollingu, wake locka i alarmów.
 */
object MailFast {
    /** Najkrótszy odstęp między sprawdzeniami wywołanymi włączeniem ekranu. */
    const val MIN_GAP_MS = 60_000L

    fun shouldRun(c: Context): Boolean =
        Prefs.mailNotify(c) && Prefs.mailFast(c) && Prefs.mailAccount(c).isNotBlank() && canNotifyMail(c)

    /** Uruchamia usługę, jeśli opcja jest włączona. */
    fun ensure(c: Context) {
        if (!shouldRun(c)) return
        try {
            ContextCompat.startForegroundService(c, Intent(c, MailService::class.java))
        } catch (e: Exception) {
            // System nie pozwala startować usługi z tła — ruszy przy następnym otwarciu aplikacji lub po restarcie telefonu.
        }
    }

    fun stop(c: Context) {
        c.stopService(Intent(c, MailService::class.java))
    }
}

class MailBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MailFast.ensure(context)
    }
}

class MailService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheck = 0L
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            check()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!MailFast.shouldRun(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            ContextCompat.registerReceiver(
                this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
        check() // od razu po starcie (np. po otwarciu aplikacji)
        return START_STICKY
    }

    private fun check() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < MailFast.MIN_GAP_MS) return
        lastCheck = now
        scope.launch { MailNotify.checkOnce(applicationContext) }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "Poczta: sprawdzanie przy włączeniu ekranu", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_mail)
            .setContentTitle("Poczta")
            .setContentText("Sprawdzam pocztę po włączeniu ekranu")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SERVICE_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SERVICE_NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        if (registered) {
            try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
            registered = false
        }
        scope.cancel()
        super.onDestroy()
    }
}
