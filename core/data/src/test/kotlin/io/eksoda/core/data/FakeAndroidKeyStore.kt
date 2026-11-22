package io.eksoda.core.data

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The Android Keystore for Robolectric, which has none: an in-memory "AndroidKeyStore" provider
 * whose AES keys are made from a KeyGenParameterSpec and kept under its alias. [install] before
 * a test and [uninstall] after; [lose] drops a key the way a reset Keystore does.
 */
internal object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        uninstall()
        Security.insertProviderAt(StandIn(), 1)
    }

    fun uninstall() {
        Security.removeProvider(NAME)
        keys.clear()
    }

    fun aliases(): Set<String> = keys.keys.toSet()

    fun lose(alias: String) {
        keys.remove(alias)
    }

    // The (String, double, String) constructor: unit tests compile against android.jar.
    @Suppress("DEPRECATION")
    private class StandIn : Provider(NAME, 1.0, "Robolectric stand-in for the Android Keystore") {
        init {
            putService(Spi(this, "KeyStore", NAME) { Store() })
            putService(Spi(this, "KeyGenerator", "AES") { Generator() })
        }
    }

    private class Spi(provider: Provider, type: String, algorithm: String, val make: () -> Any) :
        Provider.Service(provider, type, algorithm, Spi::class.java.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = make()
    }

    private class Store : KeyStoreSpi() {
        override fun engineGetEntry(
            alias: String,
            protParam: KeyStore.ProtectionParameter?,
        ): KeyStore.Entry? = keys[alias]?.let(KeyStore::SecretKeyEntry)

        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException("only generated keys")

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) =
            throw UnsupportedOperationException("no certificates")

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        // KeyStoreSpi's own type: the rest of the code uses java.time only.
        override fun engineGetCreationDate(alias: String): java.util.Date? = null

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    private class Generator : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as? KeyGenParameterSpec
                ?: throw InvalidAlgorithmParameterException("a KeyGenParameterSpec is needed")
        }

        override fun engineInit(random: SecureRandom?) =
            throw InvalidAlgorithmParameterException("a KeyGenParameterSpec is needed")

        override fun engineInit(keysize: Int, random: SecureRandom?) =
            throw InvalidAlgorithmParameterException("a KeyGenParameterSpec is needed")

        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec) { "not initialized" }
            val bytes = ByteArray(spec.keySize / Byte.SIZE_BITS).also(SecureRandom()::nextBytes)
            return SecretKeySpec(bytes, "AES").also { keys[spec.keystoreAlias] = it }
        }
    }
}
