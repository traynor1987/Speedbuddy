package uk.co.traynor.speedbuddy

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

@Composable fun SpeedBuddyTheme(prefs: SharedPreferences, content: @Composable () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val mode=prefs.getString("theme","system").also { revision }
    val dark=when(mode) { "dark"->true;"light"->false;else->isSystemInDarkTheme() }
    val colours=if(dark) darkColorScheme(background=Color(0xFF071019),surface=Color(0xFF142331),
        primary=Color(0xFF77D5F0),onBackground=Color(0xFFF4F8FB),onSurface=Color(0xFFF4F8FB),
        onPrimary=Color(0xFF071019),surfaceVariant=Color(0xFF203443),onSurfaceVariant=Color(0xFFAFC4D2),outline=Color(0xFF304454))
        else lightColorScheme(background=Color(0xFFF5F8FA),surface=Color.White,primary=Color(0xFF006B85),
            onBackground=Color(0xFF102736),onSurface=Color(0xFF102736),onPrimary=Color.White,
            surfaceVariant=Color(0xFFE4EDF2),onSurfaceVariant=Color(0xFF465F6F),outline=Color(0xFF9AABB6))
    MaterialTheme(colorScheme=colours,content=content)
}
