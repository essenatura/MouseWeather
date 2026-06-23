package com.essenatura.mouseweather

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll

class MouseWeatherWidget : GlanceAppWidget() {

    override var stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val temp = prefs[stringPreferencesKey("temp")] ?: "--"
            val press = prefs[stringPreferencesKey("press")] ?: "--"
            val wind = prefs[stringPreferencesKey("wind")] ?: "--"
            val hum = prefs[stringPreferencesKey("hum")] ?: "--"
            val uv = prefs[stringPreferencesKey("uv")] ?: "--"
            val city = prefs[stringPreferencesKey("city")] ?: "Brak danych"
            val icon = prefs[stringPreferencesKey("icon")] ?: "🐭"
            val moonIcon = prefs[stringPreferencesKey("moon_icon")] ?: ""
            val isDay = prefs[stringPreferencesKey("is_day")] == "true"

            WidgetContent(city, temp, press, wind, hum, icon, uv, moonIcon, isDay)
        }
    }

    @Suppress("RestrictedApi")
    @Composable
    private fun WidgetContent(
        city: String, 
        temp: String, 
        press: String, 
        wind: String, 
        hum: String, 
        icon: String, 
        uv: String,
        moonIcon: String,
        isDay: Boolean
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.7f))
                .padding(8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (!isDay && moonIcon.isNotBlank() && icon != "☀️" && icon != "🌙" && icon != "🌤️") {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = moonIcon, 
                            style = TextStyle(fontSize = 24.sp),
                            modifier = GlanceModifier.padding(start = 12.dp, bottom = 12.dp)
                        )
                        Text(text = icon, style = TextStyle(fontSize = 32.sp))
                    }
                } else {
                    Text(text = icon, style = TextStyle(fontSize = 32.sp))
                }
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = temp,
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(R.color.widget_temp),
                    ),
                )
            }
            
            Text(
                text = city,
                style = TextStyle(
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(R.color.widget_city),
                ),
            )

            Row(
                modifier = GlanceModifier.padding(top = 2.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(text = "☀️ UV: $uv", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(text = "💧 $hum%", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(text = "⏲️ $press", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
            
            Text(
                text = "💨 $wind", 
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_temp)),
                modifier = GlanceModifier.padding(top = 2.dp)
            )
        }
    }
}

suspend fun updateMouseWeatherWidget(
    context: Context,
    city: String,
    temp: String,
    press: String,
    wind: String,
    hum: String,
    icon: String,
    uv: String,
    moonIcon: String = "",
    isDay: Boolean = true
) {
    val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
    val glanceIds = manager.getGlanceIds(MouseWeatherWidget::class.java)
    glanceIds.forEach { glanceId ->
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[stringPreferencesKey("city")] = city
            prefs[stringPreferencesKey("temp")] = temp
            prefs[stringPreferencesKey("press")] = press
            prefs[stringPreferencesKey("wind")] = wind
            prefs[stringPreferencesKey("hum")] = hum
            prefs[stringPreferencesKey("icon")] = icon
            prefs[stringPreferencesKey("uv")] = uv
            prefs[stringPreferencesKey("moon_icon")] = moonIcon
            prefs[stringPreferencesKey("is_day")] = isDay.toString()
        }
    }
    MouseWeatherWidget().updateAll(context)
}
