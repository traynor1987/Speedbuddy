package uk.co.traynor.speedbuddy

import android.content.Context

/** The owner-entered credential used by the existing regional catalogue and live-road clients. */
internal class RegionalRoadDataAccess(context: Context) {
    private val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)

    fun credential(): String?=preferences.getString(KEY,null)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(value: String) {
        val credential=value.trim()
        require(credential.isNotEmpty()) { "Enter your regional road data access token" }
        preferences.edit().putString(KEY,credential).apply()
    }

    fun clear() { preferences.edit().remove(KEY).apply() }

    private companion object { const val KEY="speedBuddyCredential" }
}
