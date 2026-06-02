package com.terraworld.api.reward

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * SSV 서명 검증 로직 단위 테스트. self-signed P-256 키로 happy/tamper/wrong-key/인코딩 변형을 실증.
 * (실 AdMob 콜백/키서버 연동은 G2/G3 인간·실트래픽 게이트 — 본 테스트는 crypto 로직만.)
 */
class AdSsvSignatureVerifierTest {
    private val verifier = AdSsvSignatureVerifier(ObjectMapper(), "http://unused.test/keys.json")

    private fun genKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1")) // P-256
        return kpg.generateKeyPair()
    }

    private fun signUrl(
        content: String,
        priv: PrivateKey,
    ): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(content.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign())
    }

    private val sampleContent =
        "ad_network=5450213213286189855&ad_unit=1234567890&reward_amount=1" +
            "&reward_item=coin&timestamp=1591710000000&transaction_id=abc123def456"

    @Test
    fun `유효 서명은 검증 통과`() {
        val kp = genKeyPair()
        val sig = signUrl(sampleContent, kp.private)
        assertTrue(verifier.verifyWithKey(sampleContent, sig, kp.public))
    }

    @Test
    fun `content 변조 시 검증 실패`() {
        val kp = genKeyPair()
        val sig = signUrl(sampleContent, kp.private)
        val tampered = sampleContent.replace("reward_amount=1", "reward_amount=999")
        assertFalse(verifier.verifyWithKey(tampered, sig, kp.public))
    }

    @Test
    fun `다른 키로 서명한 경우 검증 실패`() {
        val signer = genKeyPair()
        val other = genKeyPair()
        val sig = signUrl(sampleContent, signer.private)
        assertFalse(verifier.verifyWithKey(sampleContent, sig, other.public))
    }

    @Test
    fun `표준 base64(패딩 포함) 서명도 디코드 검증`() {
        val kp = genKeyPair()
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(kp.private)
        s.update(sampleContent.toByteArray(StandardCharsets.UTF_8))
        val sigStd = Base64.getEncoder().encodeToString(s.sign())
        assertTrue(verifier.verifyWithKey(sampleContent, sigStd, kp.public))
    }

    @Test
    fun `잘못된 base64 서명은 예외 없이 false`() {
        val kp = genKeyPair()
        assertFalse(verifier.verifyWithKey(sampleContent, "!!!not-base64!!!", kp.public))
    }
}
