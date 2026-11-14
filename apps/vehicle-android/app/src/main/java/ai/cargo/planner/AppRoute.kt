package ai.cargo.planner

/** Every screen of the vehicle app. V numbers match the plan and stay stable. */
sealed interface AppRoute {
    data object Home : AppRoute
    data object Profile : AppRoute
    data object Library : AppRoute
    data object Pairing : AppRoute
    data object Recovery : AppRoute
    data class Editor(val sceneId: String) : AppRoute
    data class Detail(val sceneId: String) : AppRoute
    data class Checklist(val sceneId: String) : AppRoute

    val code: String
        get() = when (this) {
            Home -> "V0"
            Profile -> "V1"
            Library -> "V2"
            Pairing -> "V3"
            is Editor -> "V4"
            is Detail -> "V5"
            is Checklist -> "V6"
            Recovery -> "V7"
        }

    val title: String
        get() = when (this) {
            Home -> "내 공간"
            Profile -> "차량 공간 설정"
            Library -> "물품 보관함"
            Pairing -> "휴대폰 연결"
            is Editor -> "3D 배치 편집"
            is Detail -> "판정 상세"
            is Checklist -> "적재 순서"
            Recovery -> "복구"
        }
}

/** Pure back stack. Encoded as text so the current screen survives process recreation through saved state. */
object RouteStack {
    fun push(stack: List<AppRoute>, route: AppRoute): List<AppRoute> =
        if (stack.lastOrNull() == route) stack else stack + route

    /** The bottom never pops, so back always has somewhere to land. */
    fun pop(stack: List<AppRoute>): List<AppRoute> = if (stack.size > 1) stack.dropLast(1) else stack

    fun encode(stack: List<AppRoute>): String = stack.joinToString("|") { route ->
        when (route) {
            is AppRoute.Editor -> "editor:${route.sceneId}"
            is AppRoute.Detail -> "detail:${route.sceneId}"
            is AppRoute.Checklist -> "checklist:${route.sceneId}"
            AppRoute.Home -> "home"
            AppRoute.Profile -> "profile"
            AppRoute.Library -> "library"
            AppRoute.Pairing -> "pairing"
            AppRoute.Recovery -> "recovery"
        }
    }

    fun decode(text: String?): List<AppRoute> {
        val decoded = text.orEmpty().split('|').mapNotNull(::decodeOne)
        return if (decoded.firstOrNull() == AppRoute.Home) decoded else listOf(AppRoute.Home)
    }

    private fun decodeOne(part: String): AppRoute? {
        val value = part.substringAfter(':', "")
        return when (part.substringBefore(':')) {
            "home" -> AppRoute.Home
            "profile" -> AppRoute.Profile
            "library" -> AppRoute.Library
            "pairing" -> AppRoute.Pairing
            "recovery" -> AppRoute.Recovery
            "editor" -> AppRoute.Editor(value)
            "detail" -> AppRoute.Detail(value)
            "checklist" -> AppRoute.Checklist(value)
            else -> null
        }
    }
}
