package com.ghadirb.aimusic.premium

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EntitlementTest {

    private val fixture = JSONObject(javaClass.getResource("/entitlement_fixture.json")!!.readText())
    private val verifier = EntitlementTokenVerifier(fixture.getString("publicKey"))
    private val now = fixture.getLong("nowMs")
    private val sub = fixture.getString("sub")

    // ---- cross-language: a token signed by the Worker's WebCrypto (Node) verifies here ----
    @Test fun verifiesTokenSignedByTheWorker() {
        val e = verifier.verify(fixture.getString("valid"), sub, now)
        assertNotNull(e)
        assertEquals(Plan.PREMIUM, e!!.plan)
        assertEquals(setOf("premium_lifetime"), e.skus)
        assertEquals(EntitlementSource.VERIFIED_TOKEN, e.source)
        assertTrue(e.isPremiumAt(now))
    }

    @Test fun rejectsExpiredWrongSubjectAndTamperedTokens() {
        assertNull(verifier.verify(fixture.getString("expired"), sub, now))
        assertNull(verifier.verify(fixture.getString("otherSub"), sub, now)) // token copied from another install
        assertNull(verifier.verify(fixture.getString("valid"), "anon:different", now))
        val parts = fixture.getString("valid").split('.')
        val forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("iss", "aimusic-gateway").put("sub", sub).put("plan", "premium").put("skus", org.json.JSONArray(listOf("premium_lifetime")))
                .put("iat", 1).put("exp", 4102444800L).toString().toByteArray()
        )
        assertNull(verifier.verify("${parts[0]}.$forgedClaims.${parts[2]}", sub, now))
        assertNull(verifier.verify("a.b", sub, now))
        assertNull(verifier.verify("", sub, now))
        assertNull(verifier.verify("${parts[0]}.${parts[1]}.AAAA", sub, now))
    }

    @Test fun rejectsAnAlgNoneStyleTokenAndWrongKey() {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val claims = fixture.getString("valid").split('.')[1]
        assertNull(verifier.verify("$header.$claims.", sub, now))
        val other = EntitlementTokenVerifier(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded.let { Base64.getEncoder().encodeToString(it) })
        assertNull(other.verify(fixture.getString("valid"), sub, now))
    }

    @Test fun unconfiguredVerifierNeverGrantsPremium() {
        val none = EntitlementTokenVerifier("")
        assertFalse(none.isConfigured)
        assertNull(none.verify(fixture.getString("valid"), sub, now))
        assertNull(EntitlementTokenVerifier("not base64!!").verify(fixture.getString("valid"), sub, now))
    }

    @Test fun derConversionRoundTripsOnJcaSignatures() {
        // Sign with the JCA (DER), convert to JWT raw form, and make sure our rawToDer restores a verifiable signature.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(40) { i ->
            val data = "payload-$i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()
            val raw = derToRaw(der)
            val restored = EntitlementTokenVerifier.rawToDer(raw)
            assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(data) }.verify(restored))
        }
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 3
        val rLen = der[i].toInt(); i++
        var r = der.copyOfRange(i, i + rLen); i += rLen + 1
        val sLen = der[i].toInt(); i++
        var s = der.copyOfRange(i, i + sLen)
        fun fit(b: ByteArray) = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
        r = fit(r); s = fit(s)
        return r + s
    }

    // ---- entitlement model & feature gate ----
    @Test fun freeUserIsBlockedFromPremiumFeaturesButNotFromCore() {
        PremiumFeature.values().forEach { assertFalse(FeatureGate.isAllowed(it, Entitlement.FREE, now)) }
    }

    @Test fun premiumUserUnlocksAllUntilExpiry() {
        val premium = Entitlement(Plan.PREMIUM, setOf("premium_monthly"), now + 1000, EntitlementSource.VERIFIED_TOKEN)
        PremiumFeature.values().forEach { assertTrue(FeatureGate.isAllowed(it, premium, now)) }
        assertFalse(FeatureGate.isAllowed(PremiumFeature.AI_DJ, premium, now + 2000)) // expired entitlement
        assertTrue(Entitlement(Plan.PREMIUM).isPremiumAt(Long.MAX_VALUE)) // lifetime (no expiry)
    }

    @Test fun everyFeatureHasAnExplicitPlanAndCopy() {
        PremiumFeature.values().forEach {
            assertEquals(Plan.PREMIUM, FeatureGate.requiredPlan(it))
            assertTrue(it.titleFa.isNotBlank() && it.descriptionFa.isNotBlank())
        }
        assertEquals(PremiumFeature.values().toList(), FeatureGate.premiumFeatures)
    }
}
