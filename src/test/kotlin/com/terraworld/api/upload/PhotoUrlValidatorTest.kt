package com.terraworld.api.upload

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * SF-005 follow-up — photoUrl 도메인 화이트리스트 검증.
 *
 * 단위 테스트 (Spring context 미로딩) — `PhotoUrlProperties` 를 직접 생성해 주입하고
 * URI 형식 / scheme / host / data MIME / userInfo 변종을 모두 점검.
 */
class PhotoUrlValidatorTest {
    private val defaultProps =
        PhotoUrlProperties(
            allowedSchemes = listOf("https", "data"),
            allowedHosts = listOf("cdn.terraworld.app", "terraworld.app", "*.example.com"),
            allowedDataMimes = listOf("image/jpeg", "image/png", "image/webp"),
        )
    private val validator = PhotoUrlValidator(defaultProps)

    @Nested
    inner class Pass {
        @Test
        fun `null 통과`() {
            assertThatCode { validator.requireAllowed(null) }.doesNotThrowAnyException()
        }

        @Test
        fun `blank 통과`() {
            assertThatCode { validator.requireAllowed("   ") }.doesNotThrowAnyException()
        }

        @Test
        fun `정확 일치 https 호스트 통과`() {
            assertThatCode {
                validator.requireAllowed("https://cdn.terraworld.app/photos/a.jpg")
            }.doesNotThrowAnyException()
        }

        @Test
        fun `wildcard 호스트 통과 — sub_example_com`() {
            assertThatCode {
                validator.requireAllowed("https://sub.example.com/x.png")
            }.doesNotThrowAnyException()
        }

        @Test
        fun `wildcard 호스트 통과 — multi level subdomain`() {
            assertThatCode {
                validator.requireAllowed("https://a.b.example.com/x.png")
            }.doesNotThrowAnyException()
        }

        @Test
        fun `data URL image_jpeg 통과`() {
            assertThatCode {
                validator.requireAllowed("data:image/jpeg;base64,/9j/4AAQ==")
            }.doesNotThrowAnyException()
        }

        @Test
        fun `data URL image_png 통과`() {
            assertThatCode {
                validator.requireAllowed("data:image/png;base64,iVBORw0KGgo=")
            }.doesNotThrowAnyException()
        }
    }

    @Nested
    inner class Reject {
        @Test
        fun `허용되지 않은 scheme — http`() {
            assertThatThrownBy { validator.requireAllowed("http://terraworld.app/x.jpg") }
                .isInstanceOf(BusinessException::class.java)
                .extracting { (it as BusinessException).errorCode }
                .isEqualTo(ErrorCode.INVALID_PHOTO_URL)
        }

        @Test
        fun `허용되지 않은 scheme — javascript`() {
            assertThatThrownBy { validator.requireAllowed("javascript:alert(1)") }
                .isInstanceOf(BusinessException::class.java)
                .extracting { (it as BusinessException).errorCode }
                .isEqualTo(ErrorCode.INVALID_PHOTO_URL)
        }

        @Test
        fun `허용되지 않은 scheme — file`() {
            assertThatThrownBy { validator.requireAllowed("file:///etc/passwd") }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `허용되지 않은 host — attacker_example`() {
            assertThatThrownBy { validator.requireAllowed("https://attacker.example/evil.jpg") }
                .isInstanceOf(BusinessException::class.java)
                .extracting { (it as BusinessException).errorCode }
                .isEqualTo(ErrorCode.INVALID_PHOTO_URL)
        }

        @Test
        fun `wildcard 가 host suffix 정확 일치는 거부 (zero subdomain)`() {
            // *.example.com 은 sub.example.com 만 허용. 베어 example.com 자체는 미포함.
            assertThatThrownBy { validator.requireAllowed("https://example.com/x") }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `userInfo 포함 url 거부`() {
            // https://user:pass@cdn.terraworld.app/... 변종 차단 (host 가 일치해도 reject)
            assertThatThrownBy {
                validator.requireAllowed("https://attacker:pwn@cdn.terraworld.app/x.jpg")
            }.isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `허용되지 않은 data MIME — text_html`() {
            assertThatThrownBy { validator.requireAllowed("data:text/html,<script>alert(1)</script>") }
                .isInstanceOf(BusinessException::class.java)
                .extracting { (it as BusinessException).errorCode }
                .isEqualTo(ErrorCode.INVALID_PHOTO_URL)
        }

        @Test
        fun `허용되지 않은 data MIME — application_javascript`() {
            assertThatThrownBy { validator.requireAllowed("data:application/javascript,alert(1)") }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `data URL 콤마 누락 거부`() {
            assertThatThrownBy { validator.requireAllowed("data:image/jpeg;base64") }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `malformed URI 거부`() {
            assertThatThrownBy { validator.requireAllowed("ht!tp://broken url") }
                .isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `scheme 누락 거부`() {
            assertThatThrownBy { validator.requireAllowed("//cdn.terraworld.app/x.jpg") }
                .isInstanceOf(BusinessException::class.java)
        }
    }

    @Nested
    inner class CustomProps {
        @Test
        fun `데이터 URL 만 허용하는 prod-like 설정`() {
            // 운영에서 외부 호스트를 닫고 서버 생성 dataURL 만 허용하는 안전 모드
            val tight =
                PhotoUrlProperties(
                    allowedSchemes = listOf("data"),
                    allowedHosts = emptyList(),
                    allowedDataMimes = listOf("image/jpeg", "image/png", "image/webp"),
                )
            val tightValidator = PhotoUrlValidator(tight)

            assertThatCode {
                tightValidator.requireAllowed("data:image/jpeg;base64,/9j/")
            }.doesNotThrowAnyException()

            assertThatThrownBy {
                tightValidator.requireAllowed("https://cdn.terraworld.app/x.jpg")
            }.isInstanceOf(BusinessException::class.java)
        }

        @Test
        fun `CDN host 만 허용하는 prod-like 설정`() {
            val tight =
                PhotoUrlProperties(
                    allowedSchemes = listOf("https"),
                    allowedHosts = listOf("cdn.terraworld.app"),
                    allowedDataMimes = emptyList(),
                )
            val tightValidator = PhotoUrlValidator(tight)

            assertThat(
                runCatching {
                    tightValidator.requireAllowed("https://cdn.terraworld.app/x.jpg")
                }.isSuccess,
            ).isTrue
            assertThatThrownBy {
                tightValidator.requireAllowed("data:image/jpeg;base64,/9j/")
            }.isInstanceOf(BusinessException::class.java)
        }
    }
}
