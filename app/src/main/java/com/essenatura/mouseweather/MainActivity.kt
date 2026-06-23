/**
 * Główna logika aplikacji MouseWeather, aplikacji pogodowej, która zapewnia
 * konsensus temperatury z wielu źródeł i prezentuje go w interfejsie z motywem myszy.
 */
package com.essenatura.mouseweather

import android.content.Intent
import android.os.Bundle
import android.content.Context
import android.content.SharedPreferences
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.essenatura.mouseweather.ui.theme.MouseWeatherTheme
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import org.json.JSONObject
import java.util.Locale
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Domyślne wagi dla portali pogodowych używane do obliczania konsensusu temperatury.
 */
var wagaPortalA = 0.333
var wagaPortalB = 0.333
var wagaPortalC = 0.334

/**
 * Globalne preferencje (SharedPreferences) dla aplikacji.
 */
lateinit var sharedPreferences: SharedPreferences

/**
 * Główna aktywność aplikacji MouseWeather.
 * Inicjalizuje wagi z [SharedPreferences] i ustawia zawartość interfejsu użytkownika.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("MouseWeatherPrefs", MODE_PRIVATE)
        
        // SPRZĄTANIE: Usuwamy przypadkowo wklejone długie teksty z nazwy miasta
        val savedCity = sharedPreferences.getString("ostatnieMiasto", "") ?: ""
        if (savedCity.length > 50 || savedCity.contains("Polityka") || savedCity.contains("Privacy")) {
            sharedPreferences.edit { putString("ostatnieMiasto", "Warszawa") }
        }

        wagaPortalA = sharedPreferences.getFloat("wagaA", 0.333f).toDouble()
        wagaPortalB = sharedPreferences.getFloat("wagaB", 0.333f).toDouble()
        wagaPortalC = sharedPreferences.getFloat("wagaC", 0.334f).toDouble()

        val langCode = sharedPreferences.getString("lang", "pl-PL") ?: "pl-PL"
        setAppLocale(this, langCode)

        createNotificationChannel()
        scheduleWeatherWorker()
        triggerImmediateWeatherCheck()

        setContent {
            var hasNotificationPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
                onResult = { permissions ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
                }
                }
            )

            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                
                if (permissionsToRequest.isNotEmpty()) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }

            MouseWeatherTheme {
                WeatherScreen(this@MainActivity)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Alerty Pogodowe"
            val descriptionText = "Powiadomienia o burzach, słońcu i UV"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("WEATHER_ALERTS", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scheduleWeatherWorker() {
        val weatherRequest = PeriodicWorkRequestBuilder<WeatherWorker>(
            15, TimeUnit.MINUTES // Minimalny odstęp dla WorkManagera
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WeatherUpdateWork",
            ExistingPeriodicWorkPolicy.KEEP,
            weatherRequest
        )
    }

    private fun triggerImmediateWeatherCheck() {
        val oneTimeRequest = OneTimeWorkRequestBuilder<WeatherWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this).enqueue(oneTimeRequest)
    }
}

fun setAppLocale(context: Context, lang: String) {
    val locale = Locale.forLanguageTag(lang)
    Locale.setDefault(locale)
    val config = context.resources.configuration
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}

/**
 * Komponent rysujący łuk reprezentujący ścieżkę słońca między wschodem a zachodem.
 *
 * @param sunriseStr Czas wschodu słońca w formacie ISO-8601 (np. "2023-10-27T07:15").
 * @param sunsetStr Czas zachodu słońca w formacie ISO-8601 (np. "2023-10-27T17:45").
 */
@Composable
fun SunArcAnimation(sunriseStr: String, sunsetStr: String) {
    if (sunriseStr.isBlank() || sunsetStr.isBlank()) return
    
    val sunriseParts = sunriseStr.substringAfter("T").split(":")
    val sunsetParts = sunsetStr.substringAfter("T").split(":")
    
    val sunriseMin = sunriseParts[0].toInt() * 60 + sunriseParts[1].toInt()
    val sunsetMin = sunsetParts[0].toInt() * 60 + sunsetParts[1].toInt()
    
    val now = java.util.Calendar.getInstance()
    val currentMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    
    val progress = if (currentMin < sunriseMin) 0f 
                  else if (currentMin > sunsetMin) 1f 
                  else (currentMin - sunriseMin).toFloat() / (sunsetMin - sunriseMin).toFloat()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .drawBehind {
                    val width = size.width
                    val height = size.height
                    
                    val orbitColor = Color.LightGray.copy(alpha = 0.3f)
                    drawArc(
                        color = orbitColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(50f, 20f),
                        size = androidx.compose.ui.geometry.Size(width - 100f, height * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                    
                    val angle = PI + (progress * PI)
                    val radiusX = (width - 100f) / 2
                    val sunX = (width / 2) + radiusX * cos(angle).toFloat()
                    val sunY = height + height * sin(angle).toFloat()
                    
                    drawCircle(
                        color = Color(0xFFF59E0B),
                        radius = 8.dp.toPx(),
                        center = Offset(sunX, sunY)
                    )
                }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "🌅 ${sunriseStr.substringAfter("T").take(5)}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(text = "🌇 ${sunsetStr.substringAfter("T").take(5)}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Komponent nakładający ikonę pogody na aktualną fazę księżyca w nocy.
 */
@Composable
fun LayeredWeatherIcon(
    icon: String,
    isDay: Boolean,
    fontSize: TextUnit,
    timeMillis: Long = System.currentTimeMillis()
) {
    if (isDay || icon == "☀️" || icon == "🌙" || icon == "🌤️") {
        Text(text = icon, fontSize = fontSize)
        return
    }

    val (ikonaCode, _) = obliczFazeKsiezyca(timeMillis)
    val moonEmoji = String(Character.toChars(ikonaCode))

    Box(contentAlignment = Alignment.Center) {
        // Faza księżyca w tle, lekko przesunięta
        Text(
            text = moonEmoji,
            fontSize = (fontSize.value * 0.7f).sp,
            modifier = Modifier.offset(x = (fontSize.value * 0.25f).dp, y = -(fontSize.value * 0.2f).dp)
        )
        // Główna ikona (chmura, deszcz itp.) na wierzchu
        Text(text = icon, fontSize = fontSize)
    }
}

/**
 * Funkcja obliczająca fazę księżyca dla danego czasu w milisekundach.
 * @return Para (ikona emoji, nazwa fazy)
 */
fun obliczFazeKsiezyca(timeMillis: Long): Pair<Int, Int> {
    val refNewMoon = 947182440000L // Nów: 6 stycznia 2000, 18:14 UTC
    val synodicMonth = 2551442.8 // Miesiąc synodyczny w sekundach
    
    val secondsSinceRef = (timeMillis / 1000) - (refNewMoon / 1000)
    val phasePercent = (secondsSinceRef % synodicMonth) / synodicMonth
    
    val ikonaEmoji = when {
        phasePercent !in 0.0625..0.9375 -> 0x1F311 // 🌑
        phasePercent < 0.1875 -> 0x1F312 // 🌒
        phasePercent < 0.3125 -> 0x1F313 // 🌓
        phasePercent < 0.4375 -> 0x1F314 // 🌔
        phasePercent < 0.5625 -> 0x1F315 // 🌕
        phasePercent < 0.6875 -> 0x1F316 // 🌖
        phasePercent < 0.8125 -> 0x1F317 // 🌗
        else -> 0x1F318 // 🌘
    }

    val nazwaResId = when {
        phasePercent !in 0.0625..0.9375 -> R.string.moon_new
        phasePercent < 0.1875 -> R.string.moon_waxing_crescent
        phasePercent < 0.3125 -> R.string.moon_first_quarter
        phasePercent < 0.4375 -> R.string.moon_waxing_gibbous
        phasePercent < 0.5625 -> R.string.moon_full
        phasePercent < 0.6875 -> R.string.moon_waning_gibbous
        phasePercent < 0.8125 -> R.string.moon_last_quarter
        else -> R.string.moon_waning_crescent
    }

    return ikonaEmoji to nazwaResId
}

/**
 * Komponent wyświetlający aktualną fazę księżyca.
 */
@Composable
fun MoonPhaseView() {
    val calendar = java.util.Calendar.getInstance()
    val (ikonaCode, nazwaResId) = obliczFazeKsiezyca(calendar.timeInMillis)
    val ikona = String(Character.toChars(ikonaCode))
    val nazwa = stringResource(nazwaResId)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(text = stringResource(R.string.moon_phase, ikona, nazwa), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
    }
}

/**
 * Podgląd dla komponentu [WeatherScreen].
 */
@Preview(showBackground = true)
@Composable
fun WeatherPreview() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Inicjalizujemy sharedPreferences dla podglądu, jeśli nie zostały jeszcze zainicjalizowane.
    // Zapobiega to błędowi UninitializedPropertyAccessException w Android Studio Preview.
    if (!::sharedPreferences.isInitialized) {
        sharedPreferences = context.getSharedPreferences("MouseWeatherPrefs", Context.MODE_PRIVATE)
    }
    MouseWeatherTheme {
        WeatherScreen(context)
    }
}

/**
 * Klasa danych reprezentująca sugestię miasta z API geokodowania.
 *
 * @property nazwa Nazwa miasta.
 * @property kraj Kod lub nazwa kraju.
 * @property kodKraju Kod kraju ISO 3166-1 alpha-2.
 * @property region Region administracyjny lub województwo (opcjonalnie).
 * @property lat Szerokość geograficzna miasta.
 * @property lon Długość geograficzna miasta.
 */
data class MiastoSugestia(
    val nazwa: String,
    val kraj: String,
    val kodKraju: String,
    val region: String?,
    val lat: Double,
    val lon: Double
)

/**
 * Klasa danych dla prognozy godzinowej.
 */
data class WeatherHour(
    val time: String,
    val icon: String,
    val temp: String,
    val isDay: Boolean
)

/**
 * Pobiera sugestie miast na podstawie podanego ciągu zapytania.
 *
 * @param miasto Zapytanie wyszukiwania (minimum 3 znaki).
 * @return Lista obiektów [MiastoSugestia].
 */
suspend fun pobierzSugestieMiast(miasto: String): List<MiastoSugestia> {
    if (miasto.length < 3) return emptyList()
    val langFull = try { sharedPreferences.getString("lang", "pl-PL") ?: "pl-PL" } catch(_: Exception) { "pl-PL" }
    val currentLang = langFull.substringBefore("-")
    return try {
        HttpClient(CIO).use { client ->
            val encodedMiasto = java.net.URLEncoder.encode(miasto.trim(), "UTF-8")
            val response: HttpResponse = client.get("https://geocoding-api.open-meteo.com/v1/search?name=$encodedMiasto&count=5&language=$currentLang&format=json")
            
            if (response.status.value in 200..299) {
                val json = JSONObject(response.bodyAsText())
                if (json.has("results")) {
                    val results = json.getJSONArray("results")
                    val sugestie = mutableListOf<MiastoSugestia>()
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        sugestie.add(
                            MiastoSugestia(
                                nazwa = item.getString("name"),
                                kraj = item.optString("country", ""),
                                kodKraju = item.optString("country_code", "PL"),
                                region = if (item.isNull("admin1")) null else item.getString("admin1"),
                                lat = item.getDouble("latitude"),
                                lon = item.getDouble("longitude")
                            )
                        )
                    }
                    sugestie
                } else emptyList()
            } else emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Pobiera kompleksowe dane pogodowe i jakości powietrza dla danych współrzędnych.
 *
 * @param lat Szerokość geograficzna lokalizacji.
 * @param lon Długość geograficzna lokalizacji.
 * @return [JSONObject] zawierający bieżące, godzinowe i dzienne dane pogodowe oraz informacje o jakości powietrza.
 */
suspend fun pobierzPelneDane(lat: Double, lon: Double): JSONObject? = coroutineScope {
    HttpClient(CIO).use { client ->
        try {
            val formattedLat = String.format(Locale.US, "%.4f", lat)
            val formattedLon = String.format(Locale.US, "%.4f", lon)

            val result = JSONObject()

            // 1. Pobieramy dane pogodowe
            try {
                val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$formattedLat&longitude=$formattedLon&current=temperature_2m,relative_humidity_2m,apparent_temperature,surface_pressure,wind_speed_10m,wind_direction_10m,precipitation,cloud_cover,weather_code,is_day&hourly=temperature_2m,precipitation,cloud_cover,uv_index,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,weathercode,sunrise,sunset&timezone=auto"
                val weatherResp: HttpResponse = client.get(weatherUrl)
                if (weatherResp.status.value in 200..299) {
                    val weatherJson = JSONObject(weatherResp.bodyAsText())
                    val current = weatherJson.getJSONObject("current")
                    val hourly = weatherJson.getJSONObject("hourly")
                    val daily = weatherJson.getJSONObject("daily")
                    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    
                    result.put("temp", current.optDouble("temperature_2m", 0.0))
                    result.put("feels_like", current.optDouble("apparent_temperature", 0.0))
                    result.put("humidity", current.optInt("relative_humidity_2m", 0))
                    result.put("pressure", current.optDouble("surface_pressure", 1013.0))
                    result.put("wind_speed", current.optDouble("wind_speed_10m", 0.0))
                    result.put("wind_dir", current.optInt("wind_direction_10m", 0))
                    result.put("current_precip", current.optDouble("precipitation", 0.0))
                    result.put("current_clouds", current.optInt("cloud_cover", 0))
                    result.put("weather_code", current.optInt("weather_code", 0))
                    result.put("is_day", current.optInt("is_day", 1))
                    
                    // Czas lokalny dla wybranego miasta
                    val localTimeStr = current.optString("time", "")
                    val localHour = if (localTimeStr.contains("T")) {
                        localTimeStr.substringAfter("T").take(2).toIntOrNull() ?: 0
                    } else {
                        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    }
                    result.put("local_hour", localHour)
                    
                    // Wyłączamy agresywne nadpisywanie kodu pogodowego danymi godzinowymi,
                    // aby uniknąć "skakania" ikony przy niepewnych prognozach opadów.
                    /* 
                    val currentHourCode = hourly.getJSONArray("weather_code").optInt(localHour, 0)
                    val hourlyPrecip = hourly.getJSONArray("precipitation").optDouble(localHour, 0.0)
                    if (result.optInt("weather_code") < 51 && currentHourCode >= 51 && hourlyPrecip > 0.2) {
                        result.put("weather_code", currentHourCode)
                    }
                    */
                    
                    result.put("hourly_time", hourly.getJSONArray("time"))
                    result.put("hourly_temp", hourly.getJSONArray("temperature_2m"))
                    result.put("hourly_precip", hourly.getJSONArray("precipitation"))
                    result.put("hourly_clouds", hourly.getJSONArray("cloud_cover"))
                    result.put("hourly_is_day", hourly.getJSONArray("is_day"))
                    if (hourly.has("uv_index")) result.put("hourly_uv", hourly.getJSONArray("uv_index"))
                    if (hourly.has("weather_code")) result.put("hourly_codes", hourly.getJSONArray("weather_code"))
                    result.put("uv_index", hourly.getJSONArray("uv_index").optDouble(currentHour, 0.0))
                    
                    result.put("daily_time", daily.getJSONArray("time"))
                    result.put("daily_max", daily.getJSONArray("temperature_2m_max"))
                    result.put("daily_min", daily.getJSONArray("temperature_2m_min"))
                    result.put("daily_code", daily.getJSONArray("weathercode"))
                    result.put("daily_sunrise", daily.getJSONArray("sunrise"))
                    result.put("daily_sunset", daily.getJSONArray("sunset"))
                    result.put("sunrise", daily.getJSONArray("sunrise").getString(0))
                    result.put("sunset", daily.getJSONArray("sunset").getString(0))
                }
            } catch (_: Exception) { }

            // 2. Pobieramy dane o jakości powietrza
            try {
                // Open-Meteo używa nazw birch_pollen, grass_pollen oraz nie wspiera pm1_0
                val airQualityUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$formattedLat&longitude=$formattedLon&current=european_aqi,us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone,alder_pollen,birch_pollen,grass_pollen,mugwort_pollen,olive_pollen,ragweed_pollen&hourly=european_aqi,us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone&timezone=auto"
                val airQualityResp: HttpResponse = client.get(airQualityUrl)
                if (airQualityResp.status.value in 200..299) {
                    val airJson = JSONObject(airQualityResp.bodyAsText())
                    val current = airJson.optJSONObject("current")
                    val hourly = airJson.optJSONObject("hourly")
                    val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    
                    fun getVal(key: String): Double {
                        var v = current?.optDouble(key, -1.0) ?: -1.0
                        // Jeśli w 'current' jest 0.0 lub brak, sprawdzamy 'hourly'
                        if (v <= 0.0 && hourly != null && hourly.has(key)) {
                            val array = hourly.getJSONArray(key)
                            v = array.optDouble(currentHour, 0.0)
                            // Jeśli nadal 0, a jest dzień, weźmy średnią z kilku godzin
                            if (v <= 0.0 && array.length() > currentHour + 1) {
                                v = array.optDouble(currentHour + 1, 0.0)
                            }
                        }
                        return if (v < 0.0) 0.0 else v
                    }

                    fun getIntVal(key: String): Int {
                        var v = current?.optInt(key, -1) ?: -1
                        if (v <= 0 && hourly != null && hourly.has(key)) {
                            v = hourly.getJSONArray(key).optInt(currentHour, 0)
                        }
                        return if (v < 0) 0 else v
                    }

                    result.put("aqi", getIntVal("european_aqi"))
                    result.put("us_aqi", getIntVal("us_aqi"))
                    result.put("pm10", getVal("pm10"))
                    result.put("pm2_5", getVal("pm2_5"))
                    result.put("pm1_0", 0.0) // API Open-Meteo nie zwraca PM1.0
                    result.put("co", getVal("carbon_monoxide"))
                    result.put("no2", getVal("nitrogen_dioxide"))
                    result.put("so2", getVal("sulphur_dioxide"))
                    result.put("o3", getVal("ozone"))
                    
                    result.put("pollen_alder", getVal("alder_pollen"))
                    result.put("pollen_birch", getVal("birch_pollen"))
                    result.put("pollen_grass", getVal("grass_pollen"))
                    result.put("pollen_mugwort", getVal("mugwort_pollen"))
                    result.put("pollen_olive", getVal("olive_pollen"))
                    result.put("pollen_ragweed", getVal("ragweed_pollen"))
                }
            } catch (_: Exception) { }

            if (result.length() > 0) result else null
        } catch (_: Exception) { null }
    }
}

/**
 * Pobiera dane o temperaturze z trzech różnych portali pogodowych, aby obliczyć konsensus.
 * Pobiera również aktualny symbol pogody z MET Norway jako dodatkowe źródło precyzji.
 */
suspend fun pobierzKonsensusZ3Portali(lat: Double, lon: Double): Triple<Double?, Double?, Double?> = coroutineScope {
    val formattedLat = String.format(Locale.US, "%.4f", lat)
    val formattedLon = String.format(Locale.US, "%.4f", lon)

    val pobieranieA = async {
        try {
            HttpClient(CIO).use { client ->
                val response: HttpResponse = client.get("https://api.open-meteo.com/v1/forecast?latitude=$formattedLat&longitude=$formattedLon&current_weather=true")
                val json = JSONObject(response.bodyAsText())
                json.getJSONObject("current_weather").getDouble("temperature")
            }
        } catch (_: Exception) { null }
    }

    val pobieranieB = async {
        try {
            HttpClient(CIO).use { client ->
                val response: HttpResponse = client.get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$formattedLat&lon=$formattedLon") {
                    headers.append("User-Agent", "MouseWeatherAndroidApp/1.0")
                }
                val json = JSONObject(response.bodyAsText())
                val instant = json.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data").getJSONObject("instant")
                
                // Czyścimy poprzedni symbol przed zapisem nowego, aby uniknąć błędów przy zmianie miast
                sharedPreferences.edit(commit = true) { remove("last_met_symbol") }

                val metSymbol = json.getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
                    .getJSONObject("data").optJSONObject("next_1_hours")
                    ?.getJSONObject("summary")?.optString("symbol_code", "")
                if (!metSymbol.isNullOrBlank()) {
                    sharedPreferences.edit(commit = true) { putString("last_met_symbol", metSymbol) }
                }
                
                instant.getJSONObject("details").getDouble("air_temperature")
            }
        } catch (_: Exception) { null }
    }

    val pobieranieC = async {
        try {
            HttpClient(CIO).use { client ->
                val response: HttpResponse = client.get("https://api.open-meteo.com/v1/forecast?latitude=$formattedLat&longitude=$formattedLon&current_weather=true&models=icon_seamless")
                val json = JSONObject(response.bodyAsText())
                json.getJSONObject("current_weather").getDouble("temperature")
            }
        } catch (_: Exception) { null }
    }

    Triple(pobieranieA.await(), pobieranieB.await(), pobieranieC.await())
}

/**
 * Główny ekran pogodowy aplikacji.
 * Obsługuje wprowadzanie danych przez użytkownika, pobieranie danych i wyświetla aktualne warunki pogodowe,
 * jakość powietrza oraz prognozy.
 */
@Composable
fun WeatherScreen(context: Context) {
    var showHelpDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(!sharedPreferences.contains("lang")) }
    var showRateDialog by remember { mutableStateOf(false) }

    // Logika sprawdzania czy pokazać prośbę o ocenę
    LaunchedEffect(Unit) {
        val firstOpen = sharedPreferences.getLong("first_open_time", 0L)
        val hasRated = sharedPreferences.getBoolean("has_rated", false)
        val now = System.currentTimeMillis()
        
        if (firstOpen == 0L) {
            sharedPreferences.edit { putLong("first_open_time", now) }
        } else if (!hasRated) {
            val threeDaysInMillis = 3 * 24 * 60 * 60 * 1000L
            if (now - firstOpen > threeDaysInMillis) {
                showRateDialog = true
            }
        }
    }
    var wybraneDaneDnia by remember { mutableStateOf<JSONObject?>(null) }
    val initialStatus = stringResource(R.string.status_initial)
    var stanPogody by remember { mutableStateOf(initialStatus) }
    var temperaturaWyswietlana by remember { mutableStateOf("--") }
    var ikonaMyszki by remember { mutableStateOf("🐭") }
    var ikonaPogody by remember { mutableStateOf("❓") }
    var isLoading by remember { mutableStateOf(false) }

    val wagaAState by remember { mutableDoubleStateOf(wagaPortalA) }
    val wagaBState by remember { mutableDoubleStateOf(wagaPortalB) }
    val wagaCState by remember { mutableDoubleStateOf(wagaPortalC) }

    var wpisaneMiasto by remember { mutableStateOf(sharedPreferences.getString("ostatnieMiasto", "") ?: "") }
    var listaSugestii by remember { mutableStateOf<List<MiastoSugestia>>(emptyList()) }

    var wilgotnosc by remember { mutableStateOf<Int?>(null) }
    var cisnienie by remember { mutableStateOf<Double?>(null) }
    var tempOdczuwalna by remember { mutableStateOf<Double?>(null) }
    var uvIndex by remember { mutableStateOf<Double?>(null) }
    var pm25 by remember { mutableStateOf<Double?>(null) }
    var pm10 by remember { mutableStateOf<Double?>(null) }
    var aqi by remember { mutableStateOf<Int?>(null) }
    var usAqi by remember { mutableStateOf<Int?>(null) }
    var co by remember { mutableStateOf<Double?>(null) }
    var no2 by remember { mutableStateOf<Double?>(null) }
    var so2 by remember { mutableStateOf<Double?>(null) }
    var o3 by remember { mutableStateOf<Double?>(null) }
    
    var sunrise by remember { mutableStateOf("") }
    var sunset by remember { mutableStateOf("") }
    var pylkiInfo by remember { mutableStateOf("") }
    var prognoza3Dni by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var wiatrPredkosc by remember { mutableStateOf<Double?>(null) }
    var wiatrKierunekResId by remember { mutableIntStateOf(R.string.wind_dir_none) }
    var samopoczucieResId by remember { mutableIntStateOf(R.string.mouse_ok) }
    var oknoLas by remember { mutableStateOf("") }
    var oknoSlonce by remember { mutableStateOf("") }
    var oknoSlonceChmury by remember { mutableStateOf("") }
    var pelneDaneJson by remember { mutableStateOf<JSONObject?>(null) }
    var prognoza24h by remember { mutableStateOf<List<WeatherHour>>(emptyList()) }
    var wybranyKodKraju by remember { mutableStateOf(sharedPreferences.getString("ostatniKodKraju", "PL") ?: "PL") }
    var isDayNow by remember { mutableStateOf(true) }

    var latitudeInput by remember { mutableStateOf(sharedPreferences.getString("ostatniaLat", "52.2297") ?: "52.2297") }
    var longitudeInput by remember { mutableStateOf(sharedPreferences.getString("ostatniaLon", "21.0122") ?: "21.0122") }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val odswiezPogode = suspend {
        try {
            isLoading = true

            var finalLat = latitudeInput.toDoubleOrNull() ?: 52.2297
            var finalLon = longitudeInput.toDoubleOrNull() ?: 21.0122

            var miastoTekst = wpisaneMiasto.ifBlank { "Warszawa" }

            val currentLangFull = sharedPreferences.getString("lang", "pl-PL") ?: "pl-PL"
            val currentLang = currentLangFull.substringBefore("-")
            setAppLocale(context, currentLangFull) // Wymuszamy odświeżenie zasobów

            if (wpisaneMiasto.isNotBlank()) {
                // Czyścimy nazwę miasta z ewentualnych przypadkowych wklejeń długich tekstów
                val czysteMiasto = if (wpisaneMiasto.length > 50) wpisaneMiasto.take(20) else wpisaneMiasto
                
                val znalezioneKoordynaty = if (latitudeInput != "52.2297" || longitudeInput != "21.0122") {
                    Pair(latitudeInput.toDoubleOrNull() ?: 52.2297, longitudeInput.toDoubleOrNull() ?: 21.0122)
                } else {
                    HttpClient(CIO).use { client ->
                        try {
                            val encodedMiasto = java.net.URLEncoder.encode(czysteMiasto.trim(), "UTF-8")
                            val response: HttpResponse = client.get("https://geocoding-api.open-meteo.com/v1/search?name=$encodedMiasto&count=1&language=$currentLang&format=json")
                            val json = JSONObject(response.bodyAsText())
                            if (json.has("results")) {
                                val first = json.getJSONArray("results").getJSONObject(0)
                                wybranyKodKraju = first.optString("country_code", "PL")
                                wpisaneMiasto = first.getString("name") // Automatyczne tłumaczenie
                                Pair(first.getDouble("latitude"), first.getDouble("longitude"))
                            } else null
                        } catch (_: Exception) { null }
                    }
                }

                if (znalezioneKoordynaty != null) {
                    finalLat = znalezioneKoordynaty.first
                    finalLon = znalezioneKoordynaty.second
                    miastoTekst = wpisaneMiasto.trim().replaceFirstChar { it.uppercase() }

                    latitudeInput = String.format(Locale.US, "%.4f", finalLat)
                    longitudeInput = String.format(Locale.US, "%.4f", finalLon)
                    
                    sharedPreferences.edit {
                        putString("ostatnieMiasto", wpisaneMiasto)
                        putString("ostatniaLat", latitudeInput)
                        putString("ostatniaLon", longitudeInput)
                        putString("ostatniKodKraju", wybranyKodKraju)
                    }
                } else {
                    stanPogody = context.getString(R.string.status_error_city)
                    miastoTekst = context.getString(R.string.label_coordinates)
                }
            }

            val tripleResult = pobierzKonsensusZ3Portali(finalLat, finalLon)
            val dodatkoweDane = pobierzPelneDane(finalLat, finalLon)
            pelneDaneJson = dodatkoweDane
            
            val tempA = tripleResult.first
            val tempB = tripleResult.second
            val tempC = tripleResult.third

            if (tempA == null && tempB == null && tempC == null) {
                    stanPogody = context.getString(R.string.status_error_weather)
                    temperaturaWyswietlana = "--"
                } else {
                val listaDostepnych = mutableListOf<Double>()
                val listaWag = mutableListOf<Double>()
                
                if (tempA != null) { listaDostepnych.add(tempA); listaWag.add(wagaAState) }
                if (tempB != null) { listaDostepnych.add(tempB); listaWag.add(wagaBState) }
                if (tempC != null) { listaDostepnych.add(tempC); listaWag.add(wagaCState) }

                val sumaWagDostepnych = listaWag.sum()
                val wynikKonsensusu = if (sumaWagDostepnych > 0) {
                    var suma = 0.0
                    for (i in listaDostepnych.indices) {
                        suma += listaDostepnych[i] * (listaWag[i] / sumaWagDostepnych)
                    }
                    suma
                } else if (listaDostepnych.isNotEmpty()) {
                    listaDostepnych.average()
                } else 0.0

                if (dodatkoweDane != null) {
                    wilgotnosc = dodatkoweDane.optInt("humidity", 0)
                    cisnienie = dodatkoweDane.optDouble("pressure", 1013.0)
                    pm25 = dodatkoweDane.optDouble("pm2_5", 0.0)
                    pm10 = dodatkoweDane.optDouble("pm10", 0.0)
                    aqi = dodatkoweDane.optInt("aqi", 0)
                    usAqi = dodatkoweDane.optInt("us_aqi", 0)
                    co = dodatkoweDane.optDouble("co", 0.0)
                    no2 = dodatkoweDane.optDouble("no2", 0.0)
                    so2 = dodatkoweDane.optDouble("so2", 0.0)
                    o3 = dodatkoweDane.optDouble("o3", 0.0)

                    sunrise = dodatkoweDane.optString("sunrise", "")
                    sunset = dodatkoweDane.optString("sunset", "")
                    wiatrPredkosc = dodatkoweDane.optDouble("wind_speed", 0.0)
                    tempOdczuwalna = dodatkoweDane.optDouble("feels_like", wynikKonsensusu)
                    uvIndex = dodatkoweDane.optDouble("uv_index", 0.0)
                    
                    val pylkiMap = mapOf(
                        context.getString(R.string.pollen_alder) to dodatkoweDane.optDouble("pollen_alder", 0.0),
                        context.getString(R.string.pollen_birch) to dodatkoweDane.optDouble("pollen_birch", 0.0),
                        context.getString(R.string.pollen_grass) to dodatkoweDane.optDouble("pollen_grass", 0.0),
                        context.getString(R.string.pollen_mugwort) to dodatkoweDane.optDouble("pollen_mugwort", 0.0),
                        context.getString(R.string.pollen_olive) to dodatkoweDane.optDouble("pollen_olive", 0.0),
                        context.getString(R.string.pollen_ragweed) to dodatkoweDane.optDouble("pollen_ragweed", 0.0)
                    )
                    val aktywnePylki = pylkiMap.filter { it.value > 15 }.keys
                    pylkiInfo = if (aktywnePylki.isNotEmpty()) context.getString(R.string.pollen_prefix) + aktywnePylki.joinToString(", ") else context.getString(R.string.pollen_low)

                    if (dodatkoweDane.has("daily_time")) {
                        val dailyTimes = dodatkoweDane.getJSONArray("daily_time")
                        val dailyMax = dodatkoweDane.getJSONArray("daily_max")
                        val dailyMin = dodatkoweDane.getJSONArray("daily_min")
                        val dailyCodes = dodatkoweDane.getJSONArray("daily_code")
                        val nowaPrognoza = mutableListOf<Triple<String, String, String>>()
                        
                        val sdfInput = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        val currentLang = sharedPreferences.getString("lang", "pl-PL") ?: "pl-PL"
                        val sdfOutput = java.text.SimpleDateFormat("EEE", Locale.forLanguageTag(currentLang))

                        for (i in 1..3) {
                            val fullDate = dailyTimes.getString(i)
                            val dateObj = sdfInput.parse(fullDate)
                            val nazwaDnia = dateObj?.let { sdfOutput.format(it).replaceFirstChar { c -> c.uppercase() } } ?: fullDate.substring(5)

                            val maxT = dailyMax.getDouble(i).toInt()
                            val minT = dailyMin.getDouble(i).toInt()
                            val code = dailyCodes.getInt(i)
                            val ikona = when {
                                code <= 2 -> "☀️"
                                code <= 48 -> "⛅"
                                code in 51..67 || code in 80..82 -> "🌧️"
                                code in 71..77 || code in 85..86 -> "❄️"
                                else -> "⛈️"
                            }
                            nowaPrognoza.add(Triple(nazwaDnia, ikona, "$maxT°/$minT°"))
                        }
                        prognoza3Dni = nowaPrognoza
                    }

                    if (dodatkoweDane.has("hourly_time")) {
                        val hTimes = dodatkoweDane.getJSONArray("hourly_time")
                        val hTemps = dodatkoweDane.getJSONArray("hourly_temp")
                        val hCodes = dodatkoweDane.getJSONArray("hourly_codes")
                        val hPrecip = dodatkoweDane.optJSONArray("hourly_precip")
                        val hIsDay = dodatkoweDane.optJSONArray("hourly_is_day")
                        val nowa24h = mutableListOf<WeatherHour>()
                        val startH = pelneDaneJson!!.optInt("local_hour", java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                        
                        for (i in startH until startH + 24) {
                            if (i < hTimes.length()) {
                                val time = hTimes.getString(i).substringAfter("T").take(5)
                                val temp = hTemps.getDouble(i).toInt()
                                val code = hCodes.getInt(i)
                                val precip = hPrecip?.optDouble(i, 0.0) ?: 0.0
                                val isDayH = hIsDay?.optInt(i, 1) == 1
                                
                                // Dla pierwszej godziny (obecnej) używamy ikony głównej, która jest najdokładniejsza
                                val ikona = if (i == startH) {
                                    ikonaPogody 
                                } else {
                                    when {
                                        code >= 95 -> "⛈️"
                                        code in 51..67 || code in 80..82 || precip >= 0.1 -> "🌧️"
                                        code in 71..77 || code in 85..86 -> "❄️"
                                        code == 45 || code == 48 -> "🌫️"
                                        code <= 1 -> if (isDayH) "☀️" else "🌙"
                                        code == 2 -> if (isDayH) "🌤️" else "☁️"
                                        code == 3 -> "☁️"
                                        else -> if (isDayH) "☀️" else "🌙"
                                    }
                                }
                                nowa24h.add(WeatherHour(time, ikona, "$temp°C", isDayH))
                            }
                        }
                        prognoza24h = nowa24h
                    }

                    val stopnieWiatr = dodatkoweDane.optInt("wind_dir", -1)
                    wiatrKierunekResId = when (stopnieWiatr) {
                        in 0..22 -> R.string.wind_n
                        in 23..67 -> R.string.wind_ne
                        in 68..112 -> R.string.wind_e
                        in 113..157 -> R.string.wind_se
                        in 158..202 -> R.string.wind_s
                        in 203..247 -> R.string.wind_sw
                        in 248..292 -> R.string.wind_w
                        in 293..337 -> R.string.wind_nw
                        in 338..360 -> R.string.wind_n
                        else -> R.string.wind_dir_none
                    }

                    val safePm25 = pm25 ?: 0.0
                    val safeWiatr = wiatrPredkosc ?: 0.0
                    val safeHumidity = wilgotnosc ?: 50
                    val safeUv = uvIndex ?: 0.0
                    val currentCode = dodatkoweDane.optInt("weather_code", 0)

                    val currentHourForSamopoczucie = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val randomSeed = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) + currentHourForSamopoczucie
                    
                    samopoczucieResId = when {
                        currentCode >= 95 -> R.string.mouse_storm
                        currentCode in 51..67 -> R.string.mouse_rain
                        currentCode in 71..77 -> R.string.mouse_snow
                        safeUv > 7 -> R.string.mouse_uv
                        pylkiInfo.contains(context.getString(R.string.pollen_prefix)) -> {
                            val variants = listOf(R.string.mouse_pollen, R.string.mouse_pollen_2, R.string.mouse_pollen_3)
                            variants[randomSeed % variants.size]
                        }
                        safePm25 > 35 -> R.string.mouse_pm25
                        safeWiatr > 40 -> R.string.mouse_wind
                        wynikKonsensusu > 28 -> {
                            val variants = listOf(R.string.mouse_hot, R.string.mouse_hot_2)
                            variants[randomSeed % variants.size]
                        }
                        wynikKonsensusu < 0 -> {
                            val variants = listOf(R.string.mouse_cold, R.string.mouse_cold_2)
                            variants[randomSeed % variants.size]
                        }
                        safeHumidity > 85 -> R.string.mouse_humid
                        currentHourForSamopoczucie in 5..9 -> R.string.mouse_morning
                        currentHourForSamopoczucie in 20..23 -> R.string.mouse_evening
                        else -> {
                            val variants = listOf(R.string.mouse_ok, R.string.mouse_ok_2, R.string.mouse_ok_3)
                            variants[randomSeed % variants.size]
                        }
                    }

                    try {
                        val times = dodatkoweDane.getJSONArray("hourly_time")
                        val precips = dodatkoweDane.getJSONArray("hourly_precip")
                        val clouds = dodatkoweDane.getJSONArray("hourly_clouds")
                        val hourlyCodes = dodatkoweDane.getJSONArray("hourly_codes")
                        
                        val sunriseH = sunrise.substringAfter("T").take(2).toIntOrNull() ?: 6
                        val sunsetH = sunset.substringAfter("T").take(2).toIntOrNull() ?: 20
                        val currentHourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        
                        val currentCloudsReal = dodatkoweDane.optInt("current_clouds", 100)

                        val bezDeszczuIdx = mutableListOf<Int>()
                        val sloneczneIdx = mutableListOf<Int>()
                        val sloneczneChmuryIdx = mutableListOf<Int>()
                        
                        for (i in 0 until 48) {
                            if (i >= times.length()) break
                            val fullTime = times.getString(i)
                            val hour = fullTime.substringAfter("T").take(2).toIntOrNull() ?: 0
                            
                            // Dla aktualnej godziny bierzemy dane "na żywo", dla reszty z prognozy
                            val zachmurzenie = if (i == currentHourNow) currentCloudsReal else clouds.getInt(i)
                            val code = if (i == currentHourNow) currentCode else hourlyCodes.getInt(i)
                            
                            if (hour in sunriseH..sunsetH && i >= currentHourNow && i < 24) {
                                if (precips.getDouble(i) <= 0.0) bezDeszczuIdx.add(i)
                                
                                when {
                                    code <= 1 -> sloneczneIdx.add(i)
                                    code == 2 || (code == 3 && zachmurzenie < 80) -> sloneczneChmuryIdx.add(i)
                                }
                            }
                        }
                        
                        fun generujZakresy(indices: List<Int>, limitDzis: Boolean = true): String {
                            if (indices.isEmpty()) return context.getString(R.string.no_windows)
                            val okienka = mutableListOf<String>()
                            
                            var start = -1
                            var prev = -1
                            
                            fun dodajZakres(s: Int, p: Int) {
                                if (limitDzis && s >= 24) return 
                                val startH = times.getString(s).substringAfter("T").take(5)
                                val endH = times.getString(p + 1).substringAfter("T").take(5)
                                val zakres = "$startH-$endH"
                                okienka.add(zakres)
                            }

                            for (idx in indices) {
                                if (limitDzis && idx >= 24) continue 
                                if (start == -1) {
                                    start = idx
                                    prev = idx
                                } else if (idx == prev + 1) {
                                    prev = idx
                                } else {
                                    dodajZakres(start, prev)
                                    start = idx
                                    prev = idx
                                }
                            }
                            if (start != -1) dodajZakres(start, prev)

                            return if (okienka.isEmpty()) context.getString(R.string.no_windows) 
                                   else okienka.take(3).joinToString(", ")
                        }

                        oknoLas = generujZakresy(bezDeszczuIdx, limitDzis = true)
                        oknoSlonce = generujZakresy(sloneczneIdx, limitDzis = true)
                        oknoSlonceChmury = generujZakresy(sloneczneChmuryIdx, limitDzis = true)
                        
                    } catch (_: Exception) {
                        oknoLas = context.getString(R.string.status_error_weather)
                        oknoSlonce = context.getString(R.string.status_error_weather)
                        oknoSlonceChmury = ""
                    }
                }

                temperaturaWyswietlana = "${String.format(Locale.US, "%.1f", wynikKonsensusu)}°C"
                stanPogody = "$miastoTekst -> A: ${tempA ?: "--"}° | B: ${tempB ?: "--"}° | C: ${tempC ?: "--"}°"
                isDayNow = dodatkoweDane?.optInt("is_day", 1) == 1

                ikonaPogody = if (dodatkoweDane != null) {
                    try {
                        val code = dodatkoweDane.optInt("weather_code", 0)
                        val isDay = dodatkoweDane.optInt("is_day", 1) == 1
                        val currentClouds = dodatkoweDane.optInt("cloud_cover", 100)
                        val precipNow = dodatkoweDane.optDouble("current_precip", 0.0)
                        
                        val metSymbol = sharedPreferences.getString("last_met_symbol", "") ?: ""

                        when {
                            // 1. Burza (zawsze priorytet)
                            code >= 95 || metSymbol.contains("thunder") -> "⛈️"
                            
                            // 2. Deszcz - tylko gdy realnie pada (opad > 0.2mm)
                            (code in 51..82 || metSymbol.contains("rain")) && precipNow > 0.2 -> "🌧️"
                            
                            // 3. Śnieg i Mgła
                            code in 71..77 || metSymbol.contains("snow") -> "❄️"
                            code == 45 || code == 48 || metSymbol.contains("fog") -> "🌫️"
                            
                            // 4. Logika słoneczna (Dzień)
                            isDay -> {
                                when {
                                    // Słońce (☀️) tylko przy bardzo małym zachmurzeniu
                                    metSymbol.contains("sun") || metSymbol.contains("clearsky") || code == 0 || (code == 1 && currentClouds < 15) || currentClouds < 15 -> "☀️"
                                    // Słońce z chmurami (🌤️) dla reszty przypadków aż do 85%
                                    metSymbol.contains("fair") || metSymbol.contains("partlycloudy") || code <= 2 || currentClouds < 85 -> "🌤️"
                                    else -> "☁️"
                                }
                            }
                            
                            // 5. Logika nocna
                            else -> if (currentClouds < 30) "🌙" else "☁️"
                        }
                    } catch (_: Exception) { "☀️" }
                } else "☀️"

                ikonaMyszki = "🐭"

                // Aktualizacja widgetu
                scope.launch {
                    try {
                        val currentWindDir = context.getString(wiatrKierunekResId)
                        val (moonIkonaCode, _) = obliczFazeKsiezyca(System.currentTimeMillis())
                        val moonEmoji = String(Character.toChars(moonIkonaCode))
                        
                        updateMouseWeatherWidget(
                            context = context,
                            city = miastoTekst,
                            temp = temperaturaWyswietlana,
                            press = "${cisnienie?.toInt() ?: "--"}hPa",
                            wind = "${wiatrPredkosc ?: "--"}km/h ($currentWindDir)",
                            hum = wilgotnosc?.toString() ?: "--",
                            icon = ikonaPogody,
                            uv = String.format(Locale.US, "%.1f", uvIndex ?: 0.0),
                            moonIcon = moonEmoji,
                            isDay = isDayNow
                        )
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            stanPogody = context.getString(R.string.status_error_network)
        } finally {
            isLoading = false
        }
    }

    val langCodeState = sharedPreferences.getString("lang", "pl-PL") ?: "pl-PL"
    
    // Refresh pogody przy każdym powrocie do aplikacji (np. kliknięcie w widget)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { odswiezPogode() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(langCodeState) {
        while (true) {
            odswiezPogode()
            delay(15.minutes)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF1F5F9))
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                title = { Text(stringResource(R.string.help_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.help_weights))
                        Text(stringResource(R.string.help_humidity))
                        Text(stringResource(R.string.help_pressure))
                        Text(stringResource(R.string.help_uv))
                        Text(stringResource(R.string.help_aqi))
                        Text(stringResource(R.string.help_pm25))
                        Text(stringResource(R.string.help_pm10))
                        Text(stringResource(R.string.help_gases))
                        Text(stringResource(R.string.help_wind))
                        Text(stringResource(R.string.help_pollen))
                        Text(stringResource(R.string.help_no_rain))
                        Text(stringResource(R.string.help_sun))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHelpDialog = false }) {
                        Text(stringResource(R.string.help_understand), fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                    }
                }
            )
        }

        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(stringResource(R.string.language_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "pl-PL") }
                            setAppLocale(context, "pl-PL")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇵🇱 Polski", fontSize = 16.sp, color = Color(0xFF1E293B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "en-US") }
                            setAppLocale(context, "en-US")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇬🇧 English", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "es-ES") }
                            setAppLocale(context, "es-ES")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇪🇸 Español", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "ru-RU") }
                            setAppLocale(context, "ru-RU")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇷🇺 Русский", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "hi-IN") }
                            setAppLocale(context, "hi-IN")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇮🇳 हिन्दी", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "zh-CN") }
                            setAppLocale(context, "zh-CN")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇨🇳 中文", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "fr-FR") }
                            setAppLocale(context, "fr-FR")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇫🇷 Français", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "ar-SA") }
                            setAppLocale(context, "ar-SA")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇸🇦 العربية", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                        TextButton(onClick = { 
                            sharedPreferences.edit { putString("lang", "in-ID") }
                            setAppLocale(context, "in-ID")
                            showLanguageDialog = false 
                            (context as? ComponentActivity)?.recreate()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🇮🇩 Indonesia", fontSize = 16.sp, color = Color(0xFF64748B))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) {
                        Text(stringResource(R.string.cancel), color = Color(0xFFF59E0B))
                    }
                }
            )
        }



        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                // Wybór języka w lewym górnym rogu
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .height(32.dp)
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF1F5F9))
                        .clickable { showLanguageDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    val displayLang = when(langCodeState) {
                        "pl-PL" -> "🇵🇱 PL"
                        "en-US" -> "🇬🇧 EN"
                        "es-ES" -> "🇪🇸 ES"
                        "ru-RU" -> "🇷🇺 RU"
                        "hi-IN" -> "🇮🇳 HI"
                        "zh-CN" -> "🇨🇳 ZH"
                        "fr-FR" -> "🇫🇷 FR"
                        "ar-SA" -> "🇸🇦 AR"
                        "in-ID" -> "🇮🇩 ID"
                        else -> "🌍"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(displayLang, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("▼", fontSize = 8.sp)
                    }
                }

                // Ikona pomocy w prawym górnym rogu
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF1F5F9))
                        .clickable { showHelpDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("ℹ️", fontSize = 18.sp)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(text = stringResource(R.string.app_name) + " 🧀", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                Text(
                    text = stringResource(R.string.wagi_label, (wagaAState * 100).toInt(), (wagaBState * 100).toInt(), (wagaCState * 100).toInt()),
                    fontSize = 14.sp,
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFF59E0B))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = ikonaMyszki, fontSize = 50.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        LayeredWeatherIcon(icon = ikonaPogody, isDay = isDayNow, fontSize = 50.sp)
                    }
                    
                    Text(text = temperaturaWyswietlana, fontSize = 52.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A))
                    
                    if (tempOdczuwalna != null) {
                        Text(text = stringResource(R.string.feels_like, tempOdczuwalna!!), fontSize = 16.sp, color = Color(0xFF64748B))
                    }

                    if (prognoza24h.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(prognoza24h) { godz ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(text = godz.time, fontSize = 13.sp, color = Color(0xFF64748B))
                                    LayeredWeatherIcon(icon = godz.icon, isDay = godz.isDay, fontSize = 24.sp)
                                    Text(text = godz.temp, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (wilgotnosc != null) Text(text = stringResource(R.string.humidity_label, wilgotnosc!!), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6))
                        if (cisnienie != null) Text(text = stringResource(R.string.pressure_label, cisnienie!!.toInt()), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        if (uvIndex != null) Text(text = stringResource(R.string.uv_label, String.format(Locale.US, "%.1f", uvIndex!!)), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                    }

                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val kodyEuropejskie = setOf("AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "IS", "LI", "NO", "CH", "GB", "UA", "BY", "MD", "ME", "RS", "MK", "AL", "BA", "AD", "MC", "SM", "VA")
                        if (wybranyKodKraju in kodyEuropejskie) {
                            if (aqi != null) Text(text = stringResource(R.string.aqi_eu, aqi!!), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        } else {
                            if (usAqi != null) Text(text = stringResource(R.string.aqi_us, usAqi!!), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6))
                        }
                        if (pm25 != null) Text(text = stringResource(R.string.pm25_label, String.format(Locale.US, "%.1f", pm25!!)), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                        if (pm10 != null) Text(text = stringResource(R.string.pm10_label, String.format(Locale.US, "%.1f", pm10!!)), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                    }
                    
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (co != null && co!! > 0) Text(text = "CO: ${co!!.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        if (no2 != null && no2!! > 0) Text(text = "NO2: ${no2!!.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        if (so2 != null && so2!! > 0) Text(text = "SO2: ${so2!!.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        if (o3 != null && o3!! > 0) Text(text = "O3: ${o3!!.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                    }

                    if (wiatrPredkosc != null) {
                        val kierunek = stringResource(wiatrKierunekResId)
                        Text(text = stringResource(R.string.wind_label, wiatrPredkosc.toString(), kierunek), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1), modifier = Modifier.padding(top = 2.dp))
                    }
                    
                    if (pylkiInfo.isNotBlank()) {
                        val czystePylki = pylkiInfo.replace(context.getString(R.string.pollen_prefix), "").replace(context.getString(R.string.pollen_low), "")
                        if (czystePylki.isNotBlank()) {
                            Text(text = stringResource(R.string.pollen_active, czystePylki), fontSize = 15.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Bold)
                        } else {
                            Text(text = stringResource(R.string.pollen_low), fontSize = 15.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "🐭: " + stringResource(samopoczucieResId), fontSize = 15.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(text = stringResource(R.string.no_rain, oknoLas), fontSize = 15.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    if (oknoSlonce.isNotBlank() && oknoSlonce != "Brak okienek" && oknoSlonce != "No windows") {
                         Text(text = stringResource(R.string.sun_window, oknoSlonce), fontSize = 15.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    }
                    if (oknoSlonceChmury.isNotBlank()) {
                        Text(text = stringResource(R.string.sun_clouds_window, oknoSlonceChmury), fontSize = 15.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    }

                    if (prognoza3Dni.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            prognoza3Dni.forEachIndexed { index, dzien ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            try {
                                                val dayOffset = index + 1
                                                val details = JSONObject()
                                                
                                                val dailyTimes = pelneDaneJson!!.getJSONArray("daily_time")
                                                val dailyMax = pelneDaneJson!!.getJSONArray("daily_max")
                                                val dailyMin = pelneDaneJson!!.getJSONArray("daily_min")
                                                val dailyCodes = pelneDaneJson!!.getJSONArray("daily_code")
                                                val hourlyTimes = pelneDaneJson!!.getJSONArray("hourly_time")
                                                val hourlyTemps = pelneDaneJson!!.getJSONArray("hourly_temp")
                                                val hourlyCodes = pelneDaneJson!!.getJSONArray("hourly_codes")
                                                val hourlyIsDay = pelneDaneJson!!.optJSONArray("hourly_is_day")
                                                val hPrecipDialog = pelneDaneJson!!.optJSONArray("hourly_precip")
                                                val precips = pelneDaneJson!!.getJSONArray("hourly_precip")

                                                details.put("date", dailyTimes.getString(dayOffset))
                                                details.put("max", dailyMax.getDouble(dayOffset))
                                                details.put("min", dailyMin.getDouble(dayOffset))
                                                details.put("code", dailyCodes.getInt(dayOffset))
                                                
                                                val dayHourlyData = org.json.JSONArray()
                                                val startIdx = dayOffset * 24
                                                val endIdx = startIdx + 23
                                                
                                                for (i in startIdx..endIdx) {
                                                    if (i < hourlyTimes.length()) {
                                                        val hourObj = JSONObject()
                                                        hourObj.put("time", hourlyTimes.getString(i).substringAfter("T").take(5))
                                                        hourObj.put("temp", hourlyTemps.getDouble(i).toInt())
                                                        val hCode = hourlyCodes.getInt(i)
                                                        val precipValue = hPrecipDialog?.optDouble(i, 0.0) ?: 0.0
                                                        val isDayH = hourlyIsDay?.optInt(i, 1) == 1
                                                        val hIkona = when {
                                                            hCode >= 95 -> "⛈️"
                                                            hCode in 51..67 || hCode in 80..82 || precipValue >= 0.1 -> "🌧️"
                                                            hCode in 71..77 || hCode in 85..86 -> "❄️"
                                                            hCode == 45 || hCode == 48 -> "🌫️"
                                                            hCode <= 1 -> if (isDayH) "☀️" else "🌙"
                                                            hCode == 2 -> if (isDayH) "🌤️" else "☁️"
                                                            hCode == 3 -> "☁️"
                                                            else -> if (isDayH) "☀️" else "🌙"
                                                        }
                                                        hourObj.put("icon", hIkona)
                                                        hourObj.put("isDay", isDayH)
                                                        dayHourlyData.put(hourObj)
                                                    }
                                                }
                                                details.put("hourly", dayHourlyData)
                                                
                                                // Obliczamy "bez deszczu" dla tego konkretnego dnia
                                                val dayPrecipIdx = mutableListOf<Int>()
                                                for (i in startIdx..endIdx) {
                                                    if (i < precips.length() && precips.getDouble(i) <= 0.0) {
                                                        dayPrecipIdx.add(i)
                                                    }
                                                }
                                                
                                                // Mini-funkcja do generowania zakresów dla dowolnego dnia
                                                fun formatRanges(idxList: List<Int>): String {
                                                    if (idxList.isEmpty()) return context.getString(R.string.no_windows)
                                                    val res = mutableListOf<String>()
                                                    var s = -1
                                                    var p = -1
                                                    for (idx in idxList) {
                                                        if (s == -1) { s = idx; p = idx }
                                                        else if (idx == p + 1) { p = idx }
                                                        else {
                                                            res.add("${hourlyTimes.getString(s).substringAfter("T").take(5)}-${hourlyTimes.getString(p+1).substringAfter("T").take(5)}")
                                                            s = idx; p = idx
                                                        }
                                                    }
                                                    if (s != -1) res.add("${hourlyTimes.getString(s).substringAfter("T").take(5)}-${hourlyTimes.getString(p+1).substringAfter("T").take(5)}")
                                                    return if (res.isEmpty()) context.getString(R.string.no_windows) else res.take(3).joinToString(", ")
                                                }
                                                
                                                details.put("bez_deszczu", formatRanges(dayPrecipIdx))

                                                // Obliczamy fazę księżyca dla tego dnia
                                                val targetCalendar = java.util.Calendar.getInstance()
                                                targetCalendar.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                                                val (moonIkonaCode, moonNazwaResId) = obliczFazeKsiezyca(targetCalendar.timeInMillis)
                                                details.put("moon_icon", String(Character.toChars(moonIkonaCode)))
                                                details.put("moon_name_res", moonNazwaResId)

                                                wybraneDaneDnia = details
                                            } catch (_: Exception) {}
                                        }
                                        .padding(8.dp)
                                ) {
                                    Text(text = dzien.first, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(text = dzien.second, fontSize = 24.sp)
                                    Text(text = dzien.third, fontSize = 14.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }

                    if (wybraneDaneDnia != null) {
                        AlertDialog(
                            onDismissRequest = { wybraneDaneDnia = null },
                            title = { Text(stringResource(R.string.forecast_title, wybraneDaneDnia!!.getString("date")), fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val code = wybraneDaneDnia!!.getInt("code")
                                    val ikona = when {
                                        code <= 2 -> "☀️"
                                        code <= 48 -> "⛅"
                                        code <= 67 -> "🌧️"
                                        else -> "⛈️"
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LayeredWeatherIcon(
                                            icon = ikona,
                                            isDay = false, // W pop-upach dniowych (szczegółach) używamy fazy
                                            fontSize = 40.sp
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "${wybraneDaneDnia!!.getDouble("max")}° / ${wybraneDaneDnia!!.getDouble("min")}°",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    HorizontalDivider(color = Color(0xFFE2E8F0))

                                    if (wybraneDaneDnia!!.has("hourly")) {
                                        val dayHourly = wybraneDaneDnia!!.getJSONArray("hourly")
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            items(dayHourly.length()) { i ->
                                                val h = dayHourly.getJSONObject(i)
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                                        .padding(6.dp)
                                                ) {
                                                    Text(text = h.getString("time"), fontSize = 12.sp, color = Color(0xFF64748B))
                                                    LayeredWeatherIcon(
                                                        icon = h.getString("icon"),
                                                        isDay = h.optBoolean("isDay", true),
                                                        fontSize = 20.sp
                                                    )
                                                    Text(text = "${h.getInt("temp")}°", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = Color(0xFFE2E8F0))
                                    }
                                    
                                    val opis = when {
                                        code == 0 -> stringResource(R.string.cond_clear)
                                        code <= 3 -> stringResource(R.string.cond_partly_cloudy)
                                        code <= 48 -> stringResource(R.string.cond_foggy)
                                        code <= 67 -> stringResource(R.string.cond_rainy)
                                        else -> stringResource(R.string.cond_stormy)
                                    }
                                    Text(stringResource(R.string.forecast_conditions, opis), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                    Text(stringResource(R.string.no_rain, wybraneDaneDnia!!.getString("bez_deszczu")), color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(stringResource(R.string.forecast_moon, wybraneDaneDnia!!.getString("moon_icon"), stringResource(wybraneDaneDnia!!.getInt("moon_name_res"))), fontSize = 15.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                                    
                                    HorizontalDivider(color = Color(0xFFE2E8F0))
                                    val sugestiaResId = when {
                                        code <= 1 -> R.string.mouse_hot // Simplified for pop-up or use different strings
                                        code <= 3 -> R.string.mouse_ok
                                        code <= 48 -> R.string.cond_foggy // Wait, I need proper mouse suggestion strings for conditions
                                        code <= 67 || (code in 80..82) -> R.string.mouse_rain
                                        code <= 77 -> R.string.mouse_snow
                                        else -> R.string.mouse_storm
                                    }
                                    Text(stringResource(R.string.forecast_mouse_suggestion, stringResource(sugestiaResId)), fontSize = 15.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { wybraneDaneDnia = null }) {
                                    Text(stringResource(R.string.close), color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }

                    if (sunrise.isNotBlank() && sunset.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        SunArcAnimation(sunrise, sunset)
                        MoonPhaseView()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Przycisk wsparcia (Buy Me a Coffee)
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = "https://www.buymeacoffee.com/essenatura".toUri()
                }
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            border = BorderStroke(1.dp, Color(0xFF166534).copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF166534))
        ) {
            Text(
                text = stringResource(R.string.support_project),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = wpisaneMiasto,
                    onValueChange = { 
                        wpisaneMiasto = it
                        if (it.length >= 3) {
                            scope.launch {
                                listaSugestii = pobierzSugestieMiast(it)
                            }
                        } else {
                            listaSugestii = emptyList()
                        }
                    },
                    label = { Text(stringResource(R.string.city_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp)),
                    singleLine = true
                )
            }

            if (listaSugestii.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column {
                        listaSugestii.forEach { sugestia ->
                            TextButton(
                                onClick = {
                                    wpisaneMiasto = sugestia.nazwa
                                    wybranyKodKraju = sugestia.kodKraju
                                    latitudeInput = String.format(Locale.US, "%.4f", sugestia.lat)
                                    longitudeInput = String.format(Locale.US, "%.4f", sugestia.lon)
                                    listaSugestii = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    Text(
                                        text = "${sugestia.nazwa}, ${sugestia.kraj}${if (sugestia.region != null) " (${sugestia.region})" else ""}",
                                        color = Color(0xFF1E293B),
                                        fontSize = 15.sp
                                    )
                                }
                            }
                            if (sugestia != listaSugestii.last()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), color = Color(0xFFE2E8F0))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    odswiezPogode()
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
        ) {
            Text(text = stringResource(R.string.check_weather_btn), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}
}
