package ai.cargo.planner

import android.content.Context

/**
 * Names only: which unsaved scenes and which profiles exist. :data has no listing for either yet, and the values
 * themselves stay in Room. Losing this index hides a list row but never changes a scene or a judgement.
 */
class LocalIndex(context: Context) {
    private val prefs = context.getSharedPreferences("cargo-index", Context.MODE_PRIVATE)

    fun unsavedScenes(): Set<String> = prefs.getStringSet(UNSAVED, emptySet()).orEmpty()
    fun profiles(): List<String> = prefs.getString(PROFILES, "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun addUnsaved(id: String) { prefs.edit().putStringSet(UNSAVED, unsavedScenes() + id).commit() }
    fun addProfile(id: String) { prefs.edit().putString(PROFILES, (profiles() + id).distinct().joinToString(",")).commit() }

    private companion object {
        const val UNSAVED = "unsaved"
        const val PROFILES = "profiles"
    }
}
