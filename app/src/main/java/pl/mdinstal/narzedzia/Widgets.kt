package pl.mdinstal.narzedzia

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dodatek do intencji: który ekran otworzyć po dotknięciu widżetu (pumps, lan, wifi, sadp, weather, mail). */
const val EXTRA_OPEN_SCREEN = "open_screen"

/** Wspólne narzędzia widżetów ekranu głównego. */
object Widgets {
    fun nav(ctx: Context, target: String, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SCREEN, target)
            .setData(Uri.parse("mdtools://open/$target"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** Wołane po zmianie listy pomp (PumpStore). */
    fun updatePumps(ctx: Context) {
        try {
            val ids = ids(ctx, PumpsWidget::class.java)
            if (ids.isNotEmpty()) PumpsWidget.render(ctx, AppWidgetManager.getInstance(ctx), ids)
        } catch (e: Exception) { }
    }

    /** Wołane po zmianie licznika nieprzeczytanych (Prefs). */
    fun updateMail(ctx: Context) {
        try {
            val ids = ids(ctx, MailWidget::class.java)
            if (ids.isNotEmpty()) MailWidget.render(ctx, AppWidgetManager.getInstance(ctx), ids)
        } catch (e: Exception) { }
    }

    fun hhmm(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

class PumpsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val pumps = PumpStore.load(ctx)
            val online = pumps.count { it.online }
            val alarms = pumps.count { it.alarm }
            val warns = pumps.count { !it.alarm && it.warning }
            val rv = RemoteViews(ctx.packageName, R.layout.widget_pumps)
            rv.setTextViewText(R.id.p_total, pumps.size.toString())
            rv.setTextViewText(R.id.p_online, online.toString())
            rv.setTextViewText(R.id.p_offline, (pumps.size - online).toString())
            val showAlarm = alarms > 0 || warns == 0
            rv.setTextViewText(R.id.p_alarm, (if (showAlarm) alarms else warns).toString())
            rv.setTextViewText(R.id.p_alarm_label, if (showAlarm) "alarmy" else "ostrzeżenia")
            val red = ctx.getColor(R.color.widget_red)
            val amber = ctx.getColor(R.color.widget_amber)
            val text = ctx.getColor(R.color.widget_text)
            rv.setTextColor(R.id.p_alarm, if (alarms > 0) red else if (warns > 0) amber else text)
            val first = pumps.firstOrNull { it.alarm } ?: pumps.firstOrNull { it.warning }
            rv.setTextViewText(
                R.id.p_line,
                when {
                    pumps.isEmpty() -> "Otwórz aplikację, aby pobrać listę"
                    first == null -> "Brak alarmów · stan z ${Widgets.hhmm()}"
                    else -> "● ${first.serial} · " + (if (first.alarm) "alarm" else "ostrzeżenie")
                }
            )
            rv.setTextColor(R.id.p_line, if (first == null) ctx.getColor(R.color.widget_text2) else if (first.alarm) red else amber)
            rv.setOnClickPendingIntent(R.id.p_root, Widgets.nav(ctx, "pumps", 1))
            mgr.updateAppWidget(ids, rv)
        }
    }
}

class MailWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val n = Prefs.mailUnread(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_mail)
            rv.setTextViewText(R.id.m_count, n.toString())
            rv.setTextColor(R.id.m_count, ctx.getColor(if (n > 0) R.color.widget_accent else R.color.widget_text))
            rv.setTextViewText(R.id.m_label, if (n == 1) "nieprzeczytana" else "nieprzeczytane")
            rv.setTextViewText(R.id.m_time, "Zaktualizowano ${Widgets.hhmm()}")
            rv.setOnClickPendingIntent(R.id.m_root, Widgets.nav(ctx, "mail", 2))
            mgr.updateAppWidget(ids, rv)
        }
    }
}

class ShortcutsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_shortcuts)
        rv.setOnClickPendingIntent(R.id.s_lan, Widgets.nav(ctx, "lan", 3))
        rv.setOnClickPendingIntent(R.id.s_wifi, Widgets.nav(ctx, "wifi", 4))
        rv.setOnClickPendingIntent(R.id.s_cam, Widgets.nav(ctx, "sadp", 5))
        rv.setOnClickPendingIntent(R.id.s_pumps, Widgets.nav(ctx, "pumps", 6))
        mgr.updateAppWidget(ids, rv)
    }
}

class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, mgr, ids)
        // Pobranie świeżej prognozy poza wątkiem głównym; goAsync trzyma proces do końca pracy.
        val pending = goAsync()
        Thread {
            try {
                if (fetch(ctx)) render(ctx, mgr, ids)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val PREFS = "widget_weather"

        /** Zapamiętuje współrzędne z ekranu Pogoda; widżet w tle nie ma dostępu do lokalizacji. */
        fun saveLocation(ctx: Context, lat: Double, lon: Double, name: String?) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lat", lat.toString()).putString("lon", lon.toString())
                .putString("name", name ?: "").apply()
        }

        private fun fetch(ctx: Context): Boolean {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lat = p.getString("lat", null) ?: return false
            val lon = p.getString("lon", null) ?: return false
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"
            val o = JSONObject(Http.get(url))
            val c = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            p.edit()
                .putInt("temp", Math.round(c.getDouble("temperature_2m")).toInt())
                .putInt("code", c.getInt("weather_code"))
                .putInt("tmax", Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
                .putInt("tmin", Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
                .putInt("rain", d.getJSONArray("precipitation_probability_max").optInt(0))
                .putString("at", Widgets.hhmm())
                .apply()
            return true
        }

        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_weather)
            if (!p.contains("temp")) {
                rv.setTextViewText(R.id.w_city, "Pogoda")
                rv.setTextViewText(R.id.w_temp, "--°")
                rv.setTextViewText(R.id.w_desc, "Otwórz Pogodę w aplikacji,")
                rv.setTextViewText(R.id.w_range, "aby ustawić lokalizację")
            } else {
                val code = p.getInt("code", 0)
                val name = p.getString("name", "").orEmpty()
                rv.setTextViewText(R.id.w_city, if (name.isBlank()) "Twoja lokalizacja" else name)
                rv.setTextViewText(R.id.w_temp, "${p.getInt("temp", 0)}°")
                rv.setTextViewText(R.id.w_icon, weatherIcon(code))
                rv.setTextViewText(R.id.w_desc, weatherText(code))
                rv.setTextViewText(R.id.w_range, "Dziś ${p.getInt("tmin", 0)}° / ${p.getInt("tmax", 0)}° · opady ${p.getInt("rain", 0)}%")
            }
            rv.setOnClickPendingIntent(R.id.w_root, Widgets.nav(ctx, "weather", 7))
            mgr.updateAppWidget(ids, rv)
        }
    }
}
