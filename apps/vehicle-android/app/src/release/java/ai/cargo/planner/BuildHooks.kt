package ai.cargo.planner

import ai.cargo.data.SaveFaultHook
import ai.cargo.designsystem.CargoThemeValues
import ai.cargo.geometry.SearchBudget
import ai.cargo.sync.TransportTrust
import ai.cargo.vehicleport.api.VehiclePort
import ai.cargo.vehicleport.api.VehiclePorts
import android.content.Context

/** Release values. HTTPS endpoints are not configured or approved yet, so pairing resolves to Disabled. */
object BuildHooks {
    const val VARIANT = "release"
    val TRUST = TransportTrust.RELEASE

    fun vehiclePort(context: Context): VehiclePort = VehiclePorts.create(context)
    fun theme(context: Context): CargoThemeValues = CargoThemeValues.generated()
    fun relayUrl(context: Context): String? = null
    fun phoneUrl(context: Context): String? = null
    fun saveFault(context: Context): SaveFaultHook = SaveFaultHook.NONE
    fun searchBudget(context: Context): SearchBudget = SearchBudget.DEFAULT
}
