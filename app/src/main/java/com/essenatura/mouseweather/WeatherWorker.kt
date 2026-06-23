package com.essenatura.mouseweather

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

import androidx.core.content.edit

class WeatherWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val PREFS_NAME = "MouseWeatherPrefs"

    override suspend fun doWork(): Result {
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val langCode = sharedPrefs.getString("lang", "pl-PL") ?: "pl-PL"
        
        // Wymuszamy język dla tła (powiadomienia i widget)
        val locale = Locale.forLanguageTag(langCode)
        Locale.setDefault(locale)
        
        val context = applicationContext
        val resources = context.resources
        val config = resources.configuration
        config.setLocale(locale)
        
        // Tworzymy nowy kontekst z wymuszonym językiem, aby pobrać właściwe stringi
        val localizedContext = context.createConfigurationContext(config)

        val lat = sharedPrefs.getString("ostatniaLat", null)?.toDoubleOrNull()
        val lon = sharedPrefs.getString("ostatniaLon", null)?.toDoubleOrNull()
        val miasto = sharedPrefs.getString("ostatnieMiasto", localizedContext.getString(R.string.default_city))

        if ((lat == null) || (lon == null)) return Result.success()

        val dane = pobierzPelneDane(lat, lon) ?: return Result.retry()

        analizujWarunki(localizedContext, dane, miasto)

        // Aktualizacja widgetu
        try {
            val tempVal = dane.optDouble("temp", 0.0)
            val uvVal = dane.optDouble("uv_index", 0.0)
            val pressVal = dane.optDouble("pressure", 1013.0)
            val windSpeedVal = dane.optDouble("wind_speed", 0.0)
            val windDirDegrees = dane.optInt("wind_dir", -1)
            val humVal = dane.optInt("humidity", 0)
            val code = dane.optInt("weather_code", 0)
            val isDay = dane.optInt("is_day", 1) == 1

            val windDirStr = when (windDirDegrees) {
                in 0..22 -> localizedContext.getString(R.string.wind_n)
                in 23..67 -> localizedContext.getString(R.string.wind_ne)
                in 68..112 -> localizedContext.getString(R.string.wind_e)
                in 113..157 -> localizedContext.getString(R.string.wind_se)
                in 158..202 -> localizedContext.getString(R.string.wind_s)
                in 203..247 -> localizedContext.getString(R.string.wind_sw)
                in 248..292 -> localizedContext.getString(R.string.wind_w)
                in 293..337 -> localizedContext.getString(R.string.wind_nw)
                in 338..360 -> localizedContext.getString(R.string.wind_n)
                else -> localizedContext.getString(R.string.wind_dir_none)
            }

            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val hPrecipArray = dane.optJSONArray("hourly_precip")
            val currentHPrecip = hPrecipArray?.optDouble(currentHour, 0.0) ?: 0.0
            val nextHPrecip = hPrecipArray?.optDouble((currentHour + 1) % 24, 0.0) ?: 0.0
            val maxPrecip = maxOf(dane.optDouble("current_precip", 0.0), currentHPrecip, nextHPrecip)
            
            val metSymbol = sharedPrefs.getString("last_met_symbol", "") ?: ""

            val icon = when {
                // 1. Burza i Deszcz
                code >= 95 || metSymbol.contains("thunder") -> "⛈️"
                (code in 51..82 || metSymbol.contains("rain") || metSymbol.contains("sleet")) && maxPrecip > 0.1 -> "🌧️"
                
                // 2. Śnieg i Mgła
                code in 71..77 || metSymbol.contains("snow") -> "❄️"
                code == 45 || code == 48 || metSymbol.contains("fog") -> "🌫️"
                
                // 3. Logika słoneczna (Dzień)
                isDay -> {
                    when {
                        metSymbol.contains("sun") || metSymbol.contains("clearsky") || code == 0 || (code == 1 && dane.optInt("current_clouds", 100) < 15) || dane.optInt("current_clouds", 100) < 15 -> "☀️"
                        metSymbol.contains("fair") || metSymbol.contains("partlycloudy") || code <= 2 || dane.optInt("current_clouds", 100) < 85 -> "🌤️"
                        else -> "☁️"
                    }
                }
                
                // 4. Logika nocna
                else -> if (dane.optInt("current_clouds", 100) < 30) "🌙" else "☁️"
            }

            val (moonIkonaCode, _) = obliczFazeKsiezyca(System.currentTimeMillis())
            val moonEmoji = String(Character.toChars(moonIkonaCode))

            updateMouseWeatherWidget(
                context = localizedContext,
                city = miasto ?: localizedContext.getString(R.string.default_city),
                temp = "${String.format(Locale.US, "%.1f", tempVal)}°C",
                press = "${pressVal.toInt()}hPa",
                wind = "$windSpeedVal km/h ($windDirStr)",
                hum = humVal.toString(),
                icon = icon,
                uv = String.format(Locale.US, "%.1f", uvVal),
                moonIcon = moonEmoji,
                isDay = isDay
            )
        } catch (_: Exception) { }

        return Result.success()
    }

    private fun analizujWarunki(context: Context, json: JSONObject, miasto: String?) {
        val now = Calendar.getInstance()
        val currentHour = now[Calendar.HOUR_OF_DAY]

        val hourlyTime = json.optJSONArray("hourly_time")
        val hourlyClouds = json.optJSONArray("hourly_clouds")
        val hourlyUv = json.optJSONArray("hourly_uv")
        val hourlyCodes = json.optJSONArray("hourly_codes")

        if ((hourlyTime != null) && (hourlyClouds != null) && (hourlyUv != null) && (hourlyCodes != null)) {
            val hourlyPrecip = json.optJSONArray("hourly_precip")
            // Skanujemy najbliższe 5 godzin, aby nie przegapić gwałtownych zjawisk
            for (offset in 0..5) {
                val targetIdx = (currentHour + offset) % hourlyCodes.length()
                val code = hourlyCodes.optInt(targetIdx, 0)
                val uv = hourlyUv.optDouble(targetIdx, 0.0)
                val clouds = hourlyClouds.optInt(targetIdx, 100)
                val precip = hourlyPrecip?.optDouble(targetIdx, 0.0) ?: 0.0
                val targetTimeStr = hourlyTime.optString(targetIdx, "").substringAfter("T").take(5)

                // 1. Burza (Priorytet najwyższy)
                if (code >= 95) {
                    if (powinienemWyslacAlert("storm", targetTimeStr)) {
                        sendNotification(
                            100,
                            context.getString(R.string.notif_storm_title, targetTimeStr),
                            context.getString(R.string.notif_storm_desc, miasto)
                        )
                    }
                    return // Wysyłamy tylko jeden, najważniejszy alert
                }

                // 2. Deszcz (Czułość: > 0.01mm)
                if (code in 51..67 || code in 80..82 || precip > 0.01) {
                    if (powinienemWyslacAlert("rain", targetTimeStr)) {
                        sendNotification(
                            104,
                            context.getString(R.string.notif_rain_title, targetTimeStr),
                            context.getString(R.string.notif_rain_desc, targetTimeStr)
                        )
                    }
                    return
                }

                // 3. Bardzo wysokie UV
                if (uv > 7.0) {
                    if (powinienemWyslacAlert("uv", targetTimeStr)) {
                        sendNotification(
                            101,
                            context.getString(R.string.notif_uv_title, targetTimeStr),
                            context.getString(R.string.notif_uv_desc, miasto, String.format(Locale.US, "%.1f", uv))
                        )
                    }
                    return
                }

                // 4. Pełne słońce (Bardziej rygorystyczne: clouds < 10% i kod 0)
                if (offset > 0 && clouds < 10 && code == 0) {
                    if (powinienemWyslacAlert("sun", targetTimeStr)) {
                        sendNotification(
                            102,
                            context.getString(R.string.notif_sun_title, targetTimeStr),
                            context.getString(R.string.notif_sun_desc, miasto)
                        )
                    }
                    return
                }
            }
        }

        // 5. Pełnia księżyca (raz dziennie)
        sprawdzPelnie(context, now)
    }

    private fun powinienemWyslacAlert(typ: String, targetTime: String): Boolean {
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val klucz = "last_alert_$typ"
        val zapizanyAlert = sharedPrefs.getString(klucz, "") ?: ""
        
        // Format: "GodzinaEventu|SystemowyCzasWysylki"
        val czesci = zapizanyAlert.split("|")
        val teraz = System.currentTimeMillis()
        
        if (czesci.size == 2) {
            val staraGodzina = czesci[0]
            val staryTimestamp = czesci[1].toLongOrNull() ?: 0L
            
            // Jeśli to ta sama godzina zjawiska I minęło mniej niż 3 godziny od ostatniego powiadomienia
            if (staraGodzina == targetTime && (teraz - staryTimestamp) < (3 * 60 * 60 * 1000)) {
                return false
            }
        }
        
        sharedPrefs.edit {
            putString(klucz, "$targetTime|$teraz")
        }
        return true
    }

    private fun sprawdzPelnie(context: Context, calendar: Calendar) {
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todayStr = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
        val lastMoonAlert = sharedPrefs.getString("lastMoonAlert", "")

        if (lastMoonAlert == todayStr) return

        val timeMillis = calendar.timeInMillis
        val refNewMoon = 947182440000L // Nów: 6 stycznia 2000, 18:14 UTC
        val synodicMonth = 2551442.8 // Miesiąc synodyczny w sekundach
        val secondsSinceRef = (timeMillis / 1000) - (refNewMoon / 1000)
        val phasePercent = (secondsSinceRef % synodicMonth) / synodicMonth

        // Pełnia jest około 0.5 (50% cyklu)
        if (phasePercent in 0.46..0.54) {
            sendNotification(
                103,
                context.getString(R.string.notif_moon_title),
                context.getString(R.string.notif_moon_desc)
            )
            sharedPrefs.edit { putString("lastMoonAlert", todayStr) }
        }
    }

    private fun sendNotification(id: Int, title: String, message: String) {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(applicationContext, "WEATHER_ALERTS")
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(id, builder.build())
        }
    }
}
