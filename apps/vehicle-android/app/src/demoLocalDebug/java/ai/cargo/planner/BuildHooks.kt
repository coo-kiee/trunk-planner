package ai.cargo.planner

import ai.cargo.data.SaveFaultHook
import ai.cargo.data.SavePoint
import ai.cargo.designsystem.CargoThemeValues
import ai.cargo.geometry.SearchBudget
import ai.cargo.sync.TransportTrust
import ai.cargo.vehicleport.api.VehiclePort
import ai.cargo.vehicleport.api.VehiclePorts
import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Local demo variant. Each seam reads an explicit flag file written by the acceptance runner, so a run without flags
 * behaves as the product. None of this source exists in the release APK.
 */
object BuildHooks {
    const val VARIANT = "demoLocalDebug"
    val TRUST = TransportTrust.DEMO_LOCAL

    private fun flag(context: Context, name: String): String? =
        File(context.filesDir, "debug-flags/$name").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Real SDK port unless a named port state is requested. Those screens are graded fixture_state in evidence. */
    fun vehiclePort(context: Context): VehiclePort =
        flag(context, "port_state")?.let(::DemoPortState) ?: VehiclePorts.create(context)

    /** Test-time token override, e.g. "conflict=#00FF00,item=#FF00FF". The product token source is not touched. */
    fun theme(context: Context): CargoThemeValues {
        var values = CargoThemeValues.generated()
        flag(context, "theme")?.split(',')?.forEach { pair ->
            val (name, raw) = pair.split('=')
            if (name == "spacingLarge") { values = values.copy(spacingLarge = raw.toInt().dp); return@forEach }
            if (name == "typeBody") { values = values.copy(typeBody = raw.toInt().sp); return@forEach }
            val color = Color(android.graphics.Color.parseColor(raw))
            values = when (name) {
                "clear" -> values.copy(clear = color)
                "conflict" -> values.copy(conflict = color)
                "uncertain" -> values.copy(uncertain = color)
                "unverified" -> values.copy(unverified = color)
                "stale" -> values.copy(stale = color)
                "selected" -> values.copy(selected = color)
                "item" -> values.copy(item = color)
                "accent" -> values.copy(accent = color)
                "surface" -> values.copy(surface = color)
                "background" -> values.copy(background = color)
                else -> values
            }
        }
        return values
    }

    fun relayUrl(context: Context): String? = flag(context, "relay_url") ?: BuildConfig.DEMO_RELAY_URL
    fun phoneUrl(context: Context): String? = flag(context, "phone_url") ?: BuildConfig.DEMO_PHONE_URL

    /** Parks the save at the named point so the runner can force-stop the process there. */
    fun saveFault(context: Context): SaveFaultHook {
        val point = flag(context, "save_fault")?.let(SavePoint::valueOf) ?: return SaveFaultHook.NONE
        return SaveFaultHook { reached ->
            if (reached == point) {
                Log.i("CargoSeed", "SAVE_PARKED $reached")
                Thread.sleep(Long.MAX_VALUE)
            }
        }
    }

    fun searchBudget(context: Context): SearchBudget =
        flag(context, "search_budget")?.toLongOrNull()?.let(::SearchBudget) ?: SearchBudget.DEFAULT
}
