package ai.cargo.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [credentialExpiresAtMs] is always a server value: the claim limit until [claimed] is true, the real credential end after.
 * Files written before this field existed read as unclaimed, so the next poll asks the server.
 */
data class VehicleCredential(val pairingId: String, val bearer: VehicleBearer, val credentialExpiresAtMs: Long, val claimed: Boolean = false)

/** Wraps the raw bearer; Room only ever keeps its hash, which cannot authenticate. */
interface CredentialCipher {
    fun seal(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun open(iv: ByteArray, sealed: ByteArray): ByteArray
    fun destroy()
}

class AndroidKeystoreCipher(private val alias: String = "cargo.sync.vehicle-credential") : CredentialCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }

    override fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv to cipher.doFinal(plain)
    }

    override fun open(iv: ByteArray, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(sealed)
    }

    override fun destroy() = keyStore.deleteEntry(alias)

    private companion object { const val TRANSFORM = "AES/GCM/NoPadding" }
}

sealed interface CredentialLoad {
    data object None : CredentialLoad
    data class Found(val credential: VehicleCredential) : CredentialLoad

    /** A file exists but cannot be opened, for example after a Keystore wipe. Re-pairing is the only way forward. */
    data object Unrecoverable : CredentialLoad
}

/** Pass context.noBackupFilesDir as [directory], so the file stays out of Android backup. */
class CredentialStore(directory: File, private val cipher: CredentialCipher) {
    private val file = File(directory, "vehicle-credential.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Sealed(val pairingId: String, val credentialExpiresAtMs: Long, val iv: String, val token: String, val claimed: Boolean = false)

    fun save(credential: VehicleCredential) {
        val (iv, sealed) = cipher.seal(credential.bearer.reveal().toByteArray())
        val encoder = Base64.getEncoder()
        val text = json.encodeToString(Sealed(credential.pairingId, credential.credentialExpiresAtMs, encoder.encodeToString(iv), encoder.encodeToString(sealed), credential.claimed))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        check(temp.renameTo(file)) { "Credential rename failed" }
    }

    fun load(): CredentialLoad {
        if (!file.exists()) return CredentialLoad.None
        return runCatching {
            val stored = json.decodeFromString<Sealed>(file.readText())
            val decoder = Base64.getDecoder()
            val token = cipher.open(decoder.decode(stored.iv), decoder.decode(stored.token)).decodeToString()
            CredentialLoad.Found(VehicleCredential(stored.pairingId, VehicleBearer(token), stored.credentialExpiresAtMs, stored.claimed))
        }.getOrElse { CredentialLoad.Unrecoverable }
    }

    fun clear() {
        file.delete()
    }
}
