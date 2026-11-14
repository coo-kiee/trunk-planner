package ai.cargo.planner

import android.app.Application

class CargoApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
