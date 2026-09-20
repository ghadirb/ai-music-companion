package com.ghadirb.aimusic.premium

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies the gateway's ES256 entitlement token OFFLINE with the embedded public key
 * (base64 X.509 SPKI, `ENTITLEMENT_PUBLIC_KEY` build property). The matching private key exists only
 * on the server, so a modified APK cannot mint a valid token.
 */
class EntitlementTokenVerifier(publicKeyBase64: String) {

    private val publicKey: PublicKey? = try {
        if (publicKeyBase64.isBlank()) null
        else KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
    } catch (_: Exception) { null }

    val isConfigured: Boolean get() = publicKey != null

    /** Returns the entitlement if the token is authentic, current and issued for [expectedSubject]; otherwise null. */
    fun verify(token: String, expectedSubject: String, nowMs: Long): Entitlement? {
        val key = publicKey ?: return null
        return try {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val urlDecoder = Base64.getUrlDecoder()
            val header = JSONObject(String(urlDecoder.decode(parts[0]), Charsets.UTF_8))
            if (header.optString("alg") != "ES256") return null
            val signature = urlDecoder.decode(parts[2])
            if (signature.size != 64) return null

            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(rawToDer(signature))) return null

            val claims = JSONObject(String(urlDecoder.decode(parts[1]), Charsets.UTF_8))
            if (claims.optString("iss") != ISSUER || claims.optString("sub") != expectedSubject) return null
            if (claims.optString("plan") != "premium") return null
            val exp = claims.optLong("exp", 0L) * 1000L
            val iat = claims.optLong("iat", 0L) * 1000L
            if (exp <= nowMs || iat > nowMs + CLOCK_SKEW_MS) return null
            val skus = claims.optJSONArray("skus")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
            Entitlement(Plan.PREMIUM, skus, exp, EntitlementSource.VERIFIED_TOKEN)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ISSUER = "aimusic-gateway"
        private const val CLOCK_SKEW_MS = 10 * 60 * 1000L

        /** JWT ES256 signatures are raw r||s (64 bytes); the JCA verifier expects an ASN.1 DER sequence. */
        internal fun rawToDer(raw: ByteArray): ByteArray {
            fun encodeInt(bytes: ByteArray): ByteArray {
                var start = 0
                while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
                var value = bytes.copyOfRange(start, bytes.size)
                if (value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
                return byteArrayOf(0x02, value.size.toByte()) + value
            }
            val r = encodeInt(raw.copyOfRange(0, 32))
            val s = encodeInt(raw.copyOfRange(32, 64))
            val body = r + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
