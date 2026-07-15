package com.terraworld.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.terraworld.common.cache.CacheNames
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * BE-08 (2026-07-15 성능 감사): 정적 config 로컬 캐시 (Caffeine).
 *
 * - TTL 10분 (expireAfterWrite) — admin 이 seed config(tier/카탈로그/카테고리 등)를 바꿔도
 *   최대 10분 stale 로 수렴 (성능 감사 수용 결정, 무효화 배선 없음).
 * - maximumSize 2,000 — 대상이 전부 소규모 config 라 여유값 (7 화폐 / 4 티어 / 종·stage 수십 /
 *   카탈로그 필터 조합 수십).
 * - 캐시 이름을 [CacheNames.ALL] 로 고정 선언 — 오타 캐시명은 기동 후 첫 사용 시 fail-fast.
 * - CacheManager 를 명시 @Bean 으로 선언하므로 spring-boot-starter-data-redis 가 있어도
 *   cache auto-config 가 Redis 로 붙지 않는다 (로컬 인메모리 의도).
 */
@Configuration
@EnableCaching
class CacheConfig {
    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager(*CacheNames.ALL)
        manager.setCaffeine(
            Caffeine
                .newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(2_000),
        )
        return manager
    }
}
