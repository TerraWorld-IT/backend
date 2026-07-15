# TerraWorld Backend

Spring Boot Kotlin REST API server for TerraWorld.

- **Stack**: Spring Boot 3.4.4 / Kotlin 2.1.10 / Java 21 / Gradle 8.12.1
- **Persistence**: Spring Data JPA + Flyway (PostgreSQL 운영, H2 테스트) — JOOQ/QueryDSL 미사용
- **Auth**: better-auth (Nuxt/Nitro 측 issuer) 의 RS256 JWT 검증 + JWKS 캐시
- **Spec**: spec-first (TerraWorld-IT/openapi → generated `*Api` 인터페이스 implement)
- **Group / Artifact**: `com.terraworld` / `terraworld-backend` (`com.terraworld.api.*` namespace — [ADR-002](../docs/decisions/ADR-002-namespace-split.md))

상세 분석 보고서: [docs/analyze/2026-05-16-workspace/01-backend.md](../docs/analyze/2026-05-16-workspace/01-backend.md).

---

## Quick Start

### 사전 요구

- JDK 21
- Docker (Postgres + Redis 로컬 실행용)
- (선택) IntelliJ IDEA Community 이상

### 로컬 dev 환경

```bash
# 1. Postgres + Redis 실행 (워크스페이스 root 의 deploy/ 디렉토리 참조)
#    또는 단순 컨테이너:
docker run -d --name terraworld-pg -e POSTGRES_PASSWORD=devpass -p 5432:5432 postgres:16
docker run -d --name terraworld-redis -p 6379:6379 redis:7

# 2. openapi-backend submodule init (최초 1회)
git submodule update --init --recursive

# 3. 환경변수 설정 (DX-004 — fail-fast 방지)
#    필수: DATABASE_URL / BETTER_AUTH_SECRET (16자 이상) / INTERNAL_API_TOKEN (`replace-` prefix 금지)
#    선택: REDIS_HOST / REDIS_PORT / FCM_SERVICE_ACCOUNT_JSON / R2_*
#    예시:
export DATABASE_URL="jdbc:postgresql://localhost:5432/terraworld?currentSchema=public&user=postgres&password=devpass"
export BETTER_AUTH_SECRET="$(openssl rand -hex 32)"
export INTERNAL_API_TOKEN="$(openssl rand -hex 32)"

# 4. 실행
./gradlew bootRun
#   → http://localhost:8080
#   → Swagger / OpenAPI 문서는 spec-source repo (TerraWorld-IT/openapi) 참고
#
# 환경변수 부재 fail-fast:
#   - DATABASE_URL 부재 → Spring auto-configure 실패 (DataSource Bean 생성 불가, boot abort)
#   - BETTER_AUTH_SECRET 부재 → frontend Nitro 측 fail (backend 영향 적음, JWT 검증 시 JWKS fetch 실패)
#   - INTERNAL_API_TOKEN 부재 → SecurityConfig 의 internal endpoint 가 401 응답
```

profile 별:

- `bootRun` (기본) — `application.yml` default profile
- `bootRun --args='--spring.profiles.active=test'` — H2 + 인메모리

### 빌드

```bash
./gradlew build           # 컴파일 + 122 MockMvc 테스트 (test profile)
./gradlew ktlintCheck     # Kotlin lint
./gradlew ktlintFormat    # Kotlin auto-format
./gradlew bootJar         # 실행 가능 jar
```

### 테스트

```bash
./gradlew test            # 122 MockMvc + unit
./gradlew test --info     # 상세 로그
./gradlew test --tests "*UserControllerMvcTest"   # 특정 테스트
```

기존 122 MockMvc 베이스: `AbstractMvcTest` + `@WebMvcTest(addFilters = false)` + `SecurityContextHolder` 직접 주입 패턴.

---

## Architecture

### Layer 구성

```
com.terraworld.api.
├── config/         # @Configuration (Security / JWT / R2 / Redis / JPA / RateLimit)
├── security/       # Spring Security + JWT/JWKS RS256 검증 + rate-limit (Redis bucket)
├── controller/     # 15 controllers — 모두 io.terraworld.api.api.*Api 구현 (spec-first)
├── service/        # 13 services — domain logic
├── repository/     # 15 JPA repositories
├── domain/         # 17 @Entity 클래스
├── dto/            # local DTO (ARCH-008 마이그레이션 진행 중 — Phase 1-3 완료)
└── shared/dto/     # cross-cutting DTO (CurrencyMappers 등)
```

### Namespace

- `com.terraworld.api.*` — hand-written (본 backend repo)
- `io.terraworld.api.*` — openapi-backend submodule 의 generated 코드

상세: [ADR-002 — Backend Namespace Split](../docs/decisions/ADR-002-namespace-split.md).

### Spec-First Drift Guard

본 backend 는 controller 가 `io.terraworld.api.api.*Api` 인터페이스를 implement 함으로써 spec 변경을 컴파일 시점에 detect:

- spec method 시그니처 변경 → controller `override` 컴파일 fail
- spec enum 추가 → mapper `runCatching { Enum.forValue() }.onFailure { log.warn(...) }` 패턴 (silent drop 금지)
- spec optional 필드 추가 → service 가 generated DTO 직접 반환하면 컴파일러가 강제 (ARCH-008 마이그레이션 후 자동)

상세 정책: [ADR-019 — Spec Drift Guard Policy](docs/decisions/ADR-019-spec-drift-guard-policy.md).

OpenAPI tag → controller 매핑: [ADR-018 — OpenAPI Tag Controller Coupling](docs/decisions/ADR-018-openapi-tag-controller-domain-coupling.md).

---

## Domains (15 controllers / 13 services)

ARCH-008 (service 가 generated DTO 직접 반환) 마이그레이션: **Phase 1-13 전부 완료** (PR #37, #40~#48, 추가 cleanup #38/#39). cross-cutting cleanup (CurrencyMappers, CurrencyBuilder rename 등) 만 잔여.

| Controller | Tag | Service | ARCH-008 |
|-----------|-----|---------|----------|
| UserController | User | UserService + UserDeviceService (ADR-018 흡수) | ✅ Phase 13 (#48) |
| CategoryController | Category | (service 부재 — repository 직접) | ✅ Phase 3 (코드 분석) |
| ItemController | Item | ItemService | ✅ ItemMapper 추출 (#38) |
| RecordController | Record | RecordService | ✅ Phase 11 (#46) |
| LevelController | Level | (service 부재 — repository 직접) | ✅ Phase 1 (코드 분석) |
| NoteController | Note | (service 부재 — repository 직접) | ✅ Phase 2 (코드 분석) |
| RewardController | Reward | RewardService + AttendanceService (ADR-018 흡수) | ✅ Phase 5 (#40) + Phase 6 (#45) |
| ExchangeController | Exchange | ExchangeService | ✅ Phase 8 (#42) |
| PurchaseController | Purchase | PurchaseService | ✅ Phase 7 (#41) |
| TerrariumController | Terrarium | TerrariumService | ✅ Phase 12 (#47) |
| RankingController | Ranking | RankingService | ✅ Phase 9 (#43) |
| SocialController | Social | InviteService | ✅ Phase 10 (#44) |
| UploadController | Upload | PhotoUploadService | ✅ Phase 4 (#37) (R2PhotoStorage 구현 완료 2026-06-04 — R2_* 키 설정 시 PutObject→CDN, 미설정 base64) |
| InternalController | Internal | (controller 직접) | N/A — ADR-018 의도적 제외 |

서비스 DTO 마이그레이션 SoT plan: [docs/plans/2026-05-08-service-dto-migration.md](docs/plans/2026-05-08-service-dto-migration.md).

> Workspace root 의 `docs/plans/2026-05-16-service-dto-migration.md` 는 본 plan 의 redirect stub. workspace 분석 false negative 보정 기록.

---

## External Integrations

- **Cloudflare R2** — `R2PhotoStorage`(AWS S3 SDK) 구현 완료 (2026-06-04) — `R2_*` 키 설정 시 PutObject→CDN URL, 미설정 시 base64 PoC. (presigned 아닌 서버측 PutObject 방식.)
- **Google Play IAP** — `PlayPurchaseVerifier` 실 Play Developer API 검증 + `IapVerifyController`(POST `/billing/iap/verify`, @Hidden) + RTDN 실 Pub/Sub ingestion(`PlayBillingWebhookController.handlePubSubRtdn`) 구현 완료 (2026-06-04) — Play Console service account 키 대기.
- **AdMob SSV** — `AdSsvCallbackController`(GET `/rewards/ad/ssv-callback`, @Hidden) + `AdSsvSignatureVerifier`(ECDSA P-256 SHA-256, JDK 내장) 구현 완료 (2026-06-04) — AdMob production ID + SSV public key URL 대기.
- **Admin** — `AdminController.createItem`(POST `/admin/items`, @Hidden) 아이템 생성 추가 (2026-06-04, slug/카테고리/SSRF 검증).
- **FCM** — `FcmService` 코드 완성(.env-ready, noop fallback + Micrometer 카운터) — Firebase service account JSON 키 대기.
- **Discord webhook** — spec drift / 운영 알림 (`deploy/` 측 webhook URL 보관)
- **PostgreSQL** — main DB (Flyway V1~V10)
- **Redis** — rate-limit bucket
- **better-auth** — Nuxt/Nitro 측 issuer, backend 는 검증만

JWKS 운영 runbook: [docs/runbooks/jwks-failure.md](../docs/runbooks/jwks-failure.md).

---

## Development

### openapi-backend submodule

```bash
# 최초 init
git submodule update --init --recursive

# submodule 업데이트 (spec 변경 시)
cd openapi-backend && git fetch && git checkout <new-sha>
cd .. && git add openapi-backend && git commit -m "chore(submodule): bump openapi-backend <sha>"
```

UltraPlan H3 (submodule drift) 확인 명령:

```bash
cd openapi-backend && git rev-parse HEAD          # current pointer
cd openapi-backend && git fetch && git rev-parse origin/main  # latest available
```

### Lint

본 backend 는 `ktlint` 사용. lefthook pre-commit hook (UltraPlan M7) 도입 예정.

수동 실행:

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
```

### CI / 배포 (2026-07-15 재구조)

`.github/workflows/ci.yml` 이 검증과 배포를 한 파이프라인으로 소유한다:

- `test-and-build`: ktlintCheck → test(+jacoco) → bootJar → jar 아티팩트 업로드
- `build-push-image` (main 만, `needs`): 테스트 통과 jar 를 ubuntu 러너에서 이미지로 빌드해 ghcr push (`:latest` + `:sha` 롤백 태그)
- `deploy` (main 만, `needs`): 맥 self-hosted 러너가 pull 후 컨테이너 교체 — 맥에서 gradle/docker 빌드 안 함
- 구 `deploy-selfhosted.yml`(맥 로컬 재빌드 + `-x test`) 삭제, `deploy.yml`(SSH legacy) 은 dispatch 전용 강등
- health 대기 300s — 맥은 amd64 이미지를 Rosetta 에뮬레이션 실행이라 부팅 ~100s (실측)

### 성능 레이어 (2026-07-15)

- **Caffeine 캐시**: currencies/tier/growth/카탈로그/카테고리 (TTL 10분) + 인증 필터 bootstrap 확인 캐시. 배치 **쓰기 검증은 캐시 우회 fresh read**
- **결제 tx_ref 처리 원장** (`entitlement_tx_ledger`, V36): REVOKE = terminal — 환불된 권리가 webhook 재전송으로 복원되지 않음. 상세는 `EntitlementService` 주석 참조. ⚠️ 구독(갱신 간 동일 토큰) 실판매 전 토큰 세대 설계 필요

---

## ADR (Architecture Decision Records)

본 backend 내부 ADR: `docs/decisions/`

- [ADR-018 OpenAPI Tag Controller Domain Coupling](docs/decisions/ADR-018-openapi-tag-controller-domain-coupling.md)
- [ADR-019 Spec Drift Guard Policy](docs/decisions/ADR-019-spec-drift-guard-policy.md)

워크스페이스 root ADR: [`../docs/decisions/`](../docs/decisions/)

- [ADR-001 ADR Location Policy](../docs/decisions/ADR-001-adr-location-policy.md)
- [ADR-002 Backend Namespace Split](../docs/decisions/ADR-002-namespace-split.md)

ADR INDEX: [`../docs/decisions/ADR-INDEX.md`](../docs/decisions/ADR-INDEX.md)

---

## Plans & Handover

- 최신 인수인계: [docs/handover/2026-05-08-r2-poc-and-mobile-hardening.md](../docs/handover/2026-05-08-r2-poc-and-mobile-hardening.md)
- 활성 plan: [UltraPlan 2026-05-16 v2](../docs/plans/2026-05-16-ultraplan.md) (33 작업, 6 phase)
- DTO 마이그레이션: [docs/plans/2026-05-16-service-dto-migration.md](../docs/plans/2026-05-16-service-dto-migration.md)

## 워크스페이스 분석

전체 분석 보고서: [docs/analyze/2026-05-16-workspace/README.md](../docs/analyze/2026-05-16-workspace/README.md) (13 md / 281 KB).
