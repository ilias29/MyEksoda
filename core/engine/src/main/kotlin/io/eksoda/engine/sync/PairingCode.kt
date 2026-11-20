package io.eksoda.engine.sync

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What the "Add a phone" QR code holds: the household key, and the phone that shows it (its id,
 * its name, and where it listens on the local network, when known). It gives access to the
 * household's data, so the app shows it only on request and never lets it be screenshotted.
 *
 * As text: `eksoda-pair:1?k=<key, base64url>&d=<device id>&n=<name>&a=<host:port>`.
 */
public class PairingCode(
    public val key: ByteArray,
    public val deviceId: String,
    public val deviceName: String,
    public val address: String? = null,
) {
    init {
        require(key.size == HouseholdKey.BYTES) { "a household key is ${HouseholdKey.BYTES} bytes" }
    }

    public fun encode(): String = buildString {
        append(PREFIX)
        append("k=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        append("&d=").append(escape(deviceId))
        append("&n=").append(escape(deviceName))
        address?.let { append("&a=").append(escape(it)) }
    }

    public companion object {
        private const val PREFIX = "eksoda-pair:1?"

        /** The code in [text], or null when it is not one this version reads. */
        public fun decode(text: String): PairingCode? {
            if (!text.startsWith(PREFIX)) return null
            val fields = text.removePrefix(PREFIX).split('&').mapNotNull { field ->
                val name = field.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null else name to field.substringAfter('=')
            }.toMap()
            val key = fields["k"]?.let { runCatching { Base64.getUrlDecoder().decode(it) } }
                ?.getOrNull()
            val deviceId = fields["d"]?.let(::unescape)
            val valid = key != null && key.size == HouseholdKey.BYTES && !deviceId.isNullOrBlank()
            if (!valid) return null
            return PairingCode(
                key = key,
                deviceId = deviceId,
                deviceName = fields["n"]?.let(::unescape).orEmpty(),
                address = fields["a"]?.let(::unescape)?.takeIf { it.isNotBlank() },
            )
        }

        private fun escape(text: String): String = URLEncoder.encode(text, "UTF-8")

        /** Null for broken percent escapes. */
        private fun unescape(text: String): String? =
            runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
    }
}
