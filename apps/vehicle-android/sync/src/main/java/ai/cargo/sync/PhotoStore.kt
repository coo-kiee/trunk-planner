package ai.cargo.sync

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** App-private photo bytes, addressed only by verified hash. Pass a directory under context.noBackupFilesDir. */
class PhotoStore(private val directory: File) {
    /** The returned file is what the UI consumes. Null means no verified photo is on this vehicle. */
    fun fileFor(photoRef: String?): File? {
        val hash = hashOf(photoRef) ?: return null
        return File(directory, hash).takeIf { it.isFile }
    }

    /** Stores only when the bytes hash to both the header and the draft's photoRef. */
    fun storeVerified(photoRef: String?, declaredSha256: String, bytes: ByteArray): Boolean {
        val hash = hashOf(photoRef) ?: return false
        if (sha256Hex(bytes) != hash || declaredSha256 != hash) return false
        directory.mkdirs()
        val temp = File(directory, "$hash.tmp")
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        return temp.renameTo(File(directory, hash))
    }

    private fun hashOf(photoRef: String?): String? =
        photoRef?.removePrefix("sha256:")?.takeIf { photoRef.startsWith("sha256:") && HASH.matches(it) }

    private companion object { val HASH = Regex("[0-9a-f]{64}") }
}
