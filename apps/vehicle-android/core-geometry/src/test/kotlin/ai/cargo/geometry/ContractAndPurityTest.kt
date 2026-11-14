package ai.cargo.geometry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scans pure modules for platform and side-effect access; failing here fails :core-geometry:test. */
object PurityScanner {
    private val forbidden = listOf(
        Regex("""^\s*import\s+(android|androidx|dalvik)\b""", RegexOption.MULTILINE),
        Regex("""\bjava\.(io|net|nio\.file|time)\b"""),
        Regex("""\bkotlin\.io\.path\b"""),
        Regex("""\bSystem\.(currentTimeMillis|nanoTime|getenv)\b"""),
        Regex("""\b(UUID\.randomUUID|Instant\.now|Thread\.sleep|ProcessBuilder)\b"""),
        Regex("""\bFile\("""),
    )

    fun violations(sources: List<File>): List<String> = sources.flatMap { file ->
        val text = file.readText()
        forbidden.filter { it.containsMatchIn(text) }.map { "${file.path}: ${it.pattern}" }
    }

    fun kotlinFiles(dir: File): List<File> = dir.walkTopDown().filter { it.extension == "kt" }.toList()
}

class ContractAndPurityTest {
    private val root = File(System.getProperty("cargo.productRoot"))
    private val module = File(System.getProperty("cargo.moduleRoot"))

    @Test fun configuration_copy_matches_contract_file() {
        val c = Json.parseToJsonElement(root.resolve("packages/contracts/configuration-v1.json").readText()).jsonObject
        val v = GeometryConfig.V1
        assertEquals(c["revision"]!!.jsonPrimitive.long, v.revision)
        assertEquals(c["engineVersion"]!!.jsonPrimitive.content, v.engineVersion)
        assertEquals(c["inputDimensionMaxMm"]!!.jsonPrimitive.long, v.inputDimensionMaxMm)
        assertEquals(c["coordinateAbsMaxMm"]!!.jsonPrimitive.long, v.coordinateAbsMaxMm)
        assertEquals(c["maxInstances"]!!.jsonPrimitive.long, v.maxInstances.toLong())
        assertEquals(c["maxProxyBoxesPerItem"]!!.jsonPrimitive.long, v.maxProxyBoxesPerItem.toLong())
        assertEquals(c["marginMm"]!!.jsonPrimitive.long, v.marginMm)
        assertEquals(c["supportRatioBasisPoints"]!!.jsonPrimitive.long, v.supportRatioBasisPoints)
    }

    @Test fun pure_modules_import_no_platform_or_effects() {
        val sources = PurityScanner.kotlinFiles(module.resolve("src/main")) +
            PurityScanner.kotlinFiles(module.resolve("../core-scene/src/main"))
        assertTrue(sources.size >= 10, "scan really found sources")
        assertEquals(emptyList(), PurityScanner.violations(sources))
    }

    @Test fun purity_scanner_catches_a_real_violation() {
        val fixture = File(checkNotNull(javaClass.getResource("/purity-violation/BadModule.kt.txt")).toURI())
        assertTrue(PurityScanner.violations(listOf(fixture)).size >= 3)
    }

    @Test fun runtime_classpath_has_no_android_artifact() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.none { it.endsWith(".aar") || it.contains("android.jar") || it.contains("/androidx.") }, entries.toString())
        assertEquals(null, runCatching { Class.forName("android.content.Context") }.getOrNull())
    }

    @Test fun no_mesh_type_enters_the_decision_inputs() {
        val inputs = module.resolve("src/main/kotlin/ai/cargo/geometry/Inputs.kt").readText()
        val code = inputs.lines().filterNot { it.trimStart().startsWith("/**") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertTrue(!code.contains("mesh", ignoreCase = true))
    }
}
