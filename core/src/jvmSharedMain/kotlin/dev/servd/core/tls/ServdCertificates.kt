package dev.servd.core.tls

import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * A loaded/generated self-signed keystore plus its verifiable fingerprint. Passwords are kept
 * as immutable Strings and handed out as fresh CharArrays via [keyStorePasswordChars] /
 * [privateKeyPasswordChars] - the TLS stack zeroes any CharArray it is given, so a shared array
 * would be wiped after the first server reads it.
 */
class TlsKeyStore(
    val keyStore: KeyStore,
    val alias: String,
    val keyStorePassword: String,
    val privateKeyPassword: String,
    val file: File,
    val fingerprintSha256: String,
) {
    fun keyStorePasswordChars(): CharArray = keyStorePassword.toCharArray()
    fun privateKeyPasswordChars(): CharArray = privateKeyPassword.toCharArray()
}

/**
 * Accepts any certificate. Only for connections to the host's own servers: the serve-site
 * target, and the check for a servd already running on this machine.
 */
object TrustAllCerts : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Generates (once) and persists a self-signed TLS keystore for servd, then reloads it on
 * later runs. A LAN host has no public domain, so there is no certificate authority to trust
 * it - the fingerprint is how a user verifies the connection instead of a CA. The passwords
 * are not secrets: the keystore only protects a self-signed local cert.
 */
object ServdCertificates {
    private const val ALIAS = "servd"
    // Store and key share one password: PKCS12 (and Apache FtpServer's SSL layer) work best
    // when they match, and this is a local self-signed cert, so the value isn't a secret.
    private const val STORE_PASSWORD = "servd-store"
    private const val KEY_PASSWORD = STORE_PASSWORD
    private const val KEYSTORE_FILE = "keystore.jks"

    fun loadOrCreate(dir: File, hosts: List<String>): TlsKeyStore {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, KEYSTORE_FILE)
        val storePw = STORE_PASSWORD.toCharArray()
        val keyPw = KEY_PASSWORD.toCharArray()

        // PKCS12, not JKS: Android has no JKS provider, and PKCS12 loads on both platforms.
        // Reload the persisted keystore; regenerate if it is missing or unreadable.
        val keyStore: KeyStore = runCatching {
            check(file.exists())
            KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, storePw) } }
        }.getOrElse { generate(file, hosts, storePw, keyPw) }

        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

        return TlsKeyStore(keyStore, ALIAS, STORE_PASSWORD, KEY_PASSWORD, file, fingerprint)
    }

    /** Generate a fresh self-signed cert and persist it as a PKCS12 keystore. */
    private fun generate(file: File, hosts: List<String>, storePw: CharArray, keyPw: CharArray): KeyStore {
        val generated = buildKeyStore {
            certificate(ALIAS) {
                password = KEY_PASSWORD
                domains = (hosts + listOf("localhost", "127.0.0.1")).distinct()
                daysValid = 3650
            }
        }
        // Re-store explicitly as PKCS12 so it reloads on both desktop and Android.
        val key = generated.getKey(ALIAS, keyPw) as PrivateKey
        val chain = generated.getCertificateChain(ALIAS)
        return KeyStore.getInstance("PKCS12").apply {
            load(null, storePw)
            setKeyEntry(ALIAS, key, keyPw, chain)
            file.outputStream().use { store(it, storePw) }
        }
    }
}
