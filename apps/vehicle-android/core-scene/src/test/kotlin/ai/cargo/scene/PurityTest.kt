package ai.cargo.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PurityTest {
    @Test fun scene_runtime_has_no_android_artifact_and_no_time_source() {
        val entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        assertTrue(entries.none { it.endsWith(".aar") || it.contains("android.jar") }, entries.toString())
        assertEquals(null, runCatching { Class.forName("android.content.Context") }.getOrNull())
        // Same inputs give the same revision: id and time come only from arguments.
        assertEquals(SceneFx.lockedRevision(), SceneFx.lockedRevision())
    }
}
