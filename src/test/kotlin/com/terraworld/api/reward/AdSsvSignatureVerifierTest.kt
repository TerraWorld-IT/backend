package com.terraworld.api.reward

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdSsvSignatureVerifierTest {
    @Test
    fun `P-256 SHA-256 정상 서명만 검증을 통과한다`() {
        val keyPair = generateKeyPair()
        val objectMapper = ObjectMapper()
        val keySetJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "keys" to
                        listOf(
                            mapOf(
                                "keyId" to KEY_ID,
                                "pem" to publicKeyPem(keyPair),
                            ),
                        ),
                ),
            )
        val verifier = AdSsvSignatureVerifier(objectMapper, keySetJson)
        val content = "transaction_id=tx-1&user_id=user-1&timestamp=1788130800000"
        val signature = sign(content, keyPair)

        assertTrue(verifier.verify(content, signature, KEY_ID))
        assertFalse(verifier.verify("$content&reward_amount=999", signature, KEY_ID))
        assertFalse(verifier.verify(content, sign(content, generateKeyPair()), KEY_ID))
        assertFalse(verifier.verify(content, "not-base64!", KEY_ID))
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    private fun publicKeyPem(keyPair: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
            "\n-----END PUBLIC KEY-----"

    private fun sign(
        content: String,
        keyPair: KeyPair,
    ): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(content.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    companion object {
        private const val KEY_ID = 42L
    }
}
