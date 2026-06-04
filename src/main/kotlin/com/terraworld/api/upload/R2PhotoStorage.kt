package com.terraworld.api.upload

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.util.UUID

/**
 * Cloudflare R2(S3 호환) 사진 객체 스토리지. (fullstack-ultraplan WP-4, 2026-06-04)
 *
 * `R2_*` env 전체 설정 시 활성([isEnabled]=true), 부재 시 false → PhotoUploadService 가 base64
 * dataURL PoC 유지. 서버가 multipart 파일을 받아 R2 PutObject 후 공개 CDN URL 반환(클라이언트 변경 0).
 *
 * R2 특이사항: region="auto", custom endpoint(endpointOverride) + path-style access.
 * ⚠️ 실 업/다운로드 미검증(.env-ready) — R2 키 주입 후 1MB JPG 업/다운 1회 검증 필요.
 */
@Component
class R2PhotoStorage(
    @Value("\${r2.endpoint:}") private val endpoint: String,
    @Value("\${r2.access-key-id:}") private val accessKeyId: String,
    @Value("\${r2.secret-access-key:}") private val secretAccessKey: String,
    @Value("\${r2.bucket:}") private val bucket: String,
    @Value("\${r2.public-base-url:}") private val publicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun isEnabled(): Boolean =
        endpoint.isNotBlank() &&
            accessKeyId.isNotBlank() &&
            secretAccessKey.isNotBlank() &&
            bucket.isNotBlank() &&
            publicBaseUrl.isNotBlank()

    private val client: S3Client by lazy {
        S3Client
            .builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of("auto"))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)),
            ).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    /** 업로드 후 공개 CDN URL 반환. */
    fun put(
        bytes: ByteArray,
        mime: String,
    ): String {
        val ext =
            when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "bin"
            }
        val key = "photos/${UUID.randomUUID()}.$ext"
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime).build(),
            RequestBody.fromBytes(bytes),
        )
        log.info("r2.put key={}", key)
        return "${publicBaseUrl.trimEnd('/')}/$key"
    }
}
