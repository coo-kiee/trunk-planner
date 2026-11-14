package ai.cargo.planner

import kotlin.test.Test
import kotlin.test.assertEquals

class AppRulesTest {
    @Test fun stackRoundTripsThroughText() {
        val stack = listOf(AppRoute.Home, AppRoute.Library, AppRoute.Editor("abc"), AppRoute.Detail("abc"), AppRoute.Checklist("x"))
        assertEquals(stack, RouteStack.decode(RouteStack.encode(stack)))
    }

    @Test fun unknownTextFallsBackToHome() {
        assertEquals(listOf(AppRoute.Home), RouteStack.decode(null))
        assertEquals(listOf(AppRoute.Home), RouteStack.decode("nope"))
    }

    @Test fun backNeverEmptiesTheStack() {
        assertEquals(listOf(AppRoute.Home), RouteStack.pop(listOf(AppRoute.Home)))
    }

    @Test fun keypadCollapsesLeadingZerosAndLimitsLength() {
        assertEquals("7", KeypadRules.apply("0", "7"))
        assertEquals("120", KeypadRules.apply("12", "0"))
        assertEquals("123456", KeypadRules.apply("123456", "7"))
        assertEquals("1", KeypadRules.apply("12", "back"))
        assertEquals("", KeypadRules.apply("12", "clear"))
    }
}
