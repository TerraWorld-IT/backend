package com.terraworld.api.upload

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class R2PhotoStorageTest {
    private val key = "photos/00000000-0000-0000-0000-000000000001.jpg"

    @Test
    fun `R2 미설정이면 삭제는 무시한다`() {
        val storage = R2PhotoStorage("", "", "", "", "")
        storage.delete("https://photos.example/$key")
        assertFalse(storage.isEnabled())
        assertFalse(storage.ownsPublicUrl("https://photos.example/$key"))
    }

    @Test
    fun `공개 URL의 업로드 키만 S3 DELETE로 변환하며 외부 URL과 경로 변조는 무시한다`() {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val storage =
                R2PhotoStorage(
                    "http://127.0.0.1:${server.address.port}",
                    "test-key",
                    "test-secret",
                    "test-bucket",
                    "https://photos.example/cdn/",
                )
            val valid = "https://photos.example/cdn/$key"
            assertTrue(storage.ownsPublicUrl(valid))
            storage.delete(valid)
            storage.delete(valid)
            listOf(
                "https://photos.example.evil/cdn/$key",
                "https://external.example/cdn/$key",
                "https://photos.example/cdn-other/$key",
                "https://photos.example/cdn/photos/../private/key",
                "https://photos.example/cdn/photos/%2e%2e/private/key",
                "$valid?extra=1",
                "data:image/png;base64,AAAA",
            ).forEach {
                assertFalse(storage.ownsPublicUrl(it))
                storage.delete(it)
            }
            assertEquals(listOf("DELETE /test-bucket/$key", "DELETE /test-bucket/$key"), requests.toList())
        } finally {
            server.stop(0)
        }
    }
}
