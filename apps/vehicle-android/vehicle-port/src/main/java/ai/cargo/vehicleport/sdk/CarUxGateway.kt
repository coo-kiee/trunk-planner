package ai.cargo.vehicleport.sdk

import ai.cargo.vehicleport.gateway.UxConnectResult
import ai.cargo.vehicleport.gateway.UxGateway
import ai.cargo.vehicleport.gateway.UxSample
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/** The only file that names android.car classes. */
internal class CarUxGateway(private val context: Context) : UxGateway {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    override fun connect(): UxConnectResult {
        val created = Car.createCar(context) ?: return UxConnectResult.CAR_NULL
        car = created
        manager = created.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        return if (manager == null) UxConnectResult.MANAGER_NULL else UxConnectResult.CONNECTED
    }

    override fun current(): UxSample? = manager?.currentCarUxRestrictions?.let(::sampleOf)

    override fun register(listener: (UxSample?) -> Unit) {
        manager?.registerListener { restrictions -> listener(restrictions?.let(::sampleOf)) }
    }

    override fun unregister() {
        manager?.unregisterListener()
    }

    override fun disconnect() {
        car?.disconnect()
        car = null
        manager = null
    }

    private fun sampleOf(value: CarUxRestrictions) = UxSample(value.isRequiresDistractionOptimization, value.activeRestrictions)
}
