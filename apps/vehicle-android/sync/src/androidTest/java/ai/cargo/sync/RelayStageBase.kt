package ai.cargo.sync

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ItemDraft
import ai.cargo.contracts.ItemVersion
import ai.cargo.contracts.MeasurementSource
import ai.cargo.contracts.RotationRule
import ai.cargo.contracts.ShapeKind
import ai.cargo.contracts.Vec3Mm
import ai.cargo.sync.SyncWorkerService as W
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Stage driver and protocol input shared by the real-relay tests. The phone side is a plain HTTP client, not the phone app. */
abstract class RelayStageBase {
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val args = InstrumentationRegistry.getArguments()
    protected val relay: String = args.getString("relayUrl").orEmpty()
    protected val phone: String = args.getString("phoneUrl").orEmpty()
    protected val json = Json { explicitNulls = false }
    protected val results = File(context.filesDir, W.RESULTS)
    protected var lastPid = -1

    protected fun record(name: String, value: Any?) = Log.i("SYNCIT", JSONObject().put("check", name).put("value", value.toString()).toString())

    protected fun stage(name: String, draftId: String? = null, pairing: String? = null, photoRef: String? = null): JSONObject {
        val file = File(results, "$name.json")
        file.delete()
        val intent = Intent(context, W::class.java).putExtra(W.STAGE, name).putExtra(W.RELAY, relay).putExtra(W.PHONE, phone)
        draftId?.let { intent.putExtra(W.DRAFT, it).putExtra(W.PAIRING_ID, pairing) }
        photoRef?.let { intent.putExtra(W.PHOTO_REF, it) }
        context.startService(intent)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!file.exists()) {
            assertTrue(SystemClock.elapsedRealtime() < deadline, "stage $name timed out")
            Thread.sleep(100)
        }
        val out = JSONObject(file.readText())
        assertFalse(out.has("error"), "$name: ${out.optString("error")}")
        assertNotEquals(lastPid, out.getInt("pid"), "$name must run in a recreated process")
        lastPid = out.getInt("pid")
        // Stop the service process explicitly, so that the next stage cannot reuse it.
        context.stopService(intent)
        killWorker(lastPid)
        record(name, out.toString().replace(Regex("qp_[A-Za-z0-9_-]+"), "qp_REDACTED"))
        return out
    }

    protected fun killWorker(pid: Int) {
        android.os.Process.killProcess(pid)
        Thread.sleep(300)
    }

    protected fun http(method: String, path: String, bearer: String?, body: String?): Pair<Int, JSONObject> {
        val connection = URL(relay + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        body?.let {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { o -> o.write(it.toByteArray()) }
        }
        val status = connection.responseCode
        val text = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        return status to (if (text.isEmpty()) JSONObject() else JSONObject(text))
    }

    protected fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    protected fun itemDraft(pairing: String, id: Int, base: Long?, photoRef: String? = null) = ItemDraft(
        draftId = uuid(id), pairingId = pairing, baseItemId = if (base != null) uuid(1) else null, baseItemRevision = base,
        schemaVersion = 1, clientRevision = 1,
        payload = ItemVersion(
            schemaVersion = 1, id = uuid(1), revision = 1, createdAt = "2026-11-04T00:00:00Z", updatedAt = "2026-11-04T00:00:00Z", name = "실측 상자",
            photoRef = photoRef, shapeKind = ShapeKind.BOX, shapeProxy = listOf(BoxProxy(Vec3Mm(0, 0, 0), DimensionsMm(100L + id, 200, 200))),
            measuredDimensionsMm = DimensionsMm(100L + id, 200, 200), errorMm = 2, measurementSource = MeasurementSource.USER_TAPE,
            rotationRule = RotationRule.ANY24, stackable = true, removalPriority = 1, confirmedDimensions = true,
        ),
    )

    protected fun submit(phoneToken: String, draft: ItemDraft, photo: ByteArray? = null): Pair<Int, JSONObject> {
        val draftJson = json.encodeToString(ItemDraft.serializer(), draft)
        val photoJson = photo?.let { ""","photo":{"mime":"image/jpeg","dataBase64":"${Base64.encodeToString(it, Base64.NO_WRAP)}"}""" }.orEmpty()
        val body = """{"consent":{"consentVersion":"transfer-consent-v1","dimensionsConsent":true,"photoConsent":${photo != null}},"draft":$draftJson$photoJson}"""
        return http("POST", "/phone/v1/drafts", phoneToken, body)
    }

    protected fun phoneView(phoneToken: String, draft: ItemDraft) = http("GET", "/phone/v1/drafts/${draft.draftId}/1", phoneToken, null).second

    protected fun resetDeviceState() {
        context.deleteDatabase(W.DB)
        File(context.noBackupFilesDir, W.DIR).deleteRecursively()
        results.deleteRecursively()
        AndroidKeystoreCipher(W.ALIAS).destroy()
    }
}
