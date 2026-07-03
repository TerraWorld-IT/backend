# Code Review — 화폐 substrate cutover + 구 컬럼 드롭 (V32) finale

낙서장 리팩토링 P1 finale: 지갑 read/write 를 신 substrate(`user_currency_balances`)로 완전 이전하고
구 저장(`users.basic_coin`/`special_coin`, `user_tokens` 테이블)을 드롭한 슬라이스에 대한 적대적 정적 분석.

## 범위

- `PurchaseService` (debit cutover), `RecordService`/`AttendanceService`/`RewardService`/`InviteService`/`TerrariumService` (credit cutover)
- `WalletBuilder` (read cutover → `currencyService.currencyResponse`)
- `UserBootstrapService` (초기 seed cutover)
- `User` 엔티티 필드 제거, `UserToken`/`UserTokenRepository` 삭제
- `V32__drop_legacy_wallet_columns.sql`

## 검출·조치 (4건 — 전건 수정 + 회귀 테스트)

| # | 심각도 | 위치 | 결함 | 조치 |
|---|--------|------|------|------|
| 1 | HIGH | `V32` | 구 컬럼→신 substrate backfill 없이 드롭 | **의도적 no-backfill** 로 확정·문서화. prototype·prod 데이터 없음, canonical 경로는 빈 DB 순차 실행이라 손실 대상 없음. 리팩토링 슬라이스에서 이미 신 substrate 로 income/spend 기록되어 backfill 시 이중 계상 위험 → 수행 안 함 (V32 주석에 근거 명시) |
| 2 | HIGH | `PurchaseService` TOKEN 분기 | `category==null` 인 TOKEN 아이템이 debit 없이 `UserItem` 저장 → **무료 지급** | TOKEN 분기에서 category 필수 fail-fast(`INTERNAL_ERROR`). admin item 생성 시에도 검증(방어) |
| 3 | HIGH | `PurchaseService.tokenCurrencyOf` | 매핑 안 된 카테고리(커스텀 5+)를 silent COIN 으로 청구 | 단일 SoT `CurrencyCode.elementTokenForCategory` 도입, 매핑 부재 시 fail-fast(토큰가 아이템의 코인 오청구 차단) |
| 4 | MED | `RecordService.applyCategoryReward` | 커스텀 카테고리 토큰 보상을 COIN 으로 지급하면서 응답은 `categoryTokens>0` 로 표기 → 지갑·응답 divergence | 커스텀은 토큰분을 COIN 으로 fold + 응답 `categoryTokens=0` 정직 표기 |

## Clean (정적 분석 통과)

- credit/debit 호출부가 모두 서비스 `@Transactional` 경계 내 (원자성)
- 구매 debit 이 `UserItem` 저장보다 선행 (실패 시 미지급)
- 드롭된 `User.basicCoin`/`specialCoin`/`UserToken` 을 읽는 production Kotlin 코드 잔존 0
- BASIC→COIN, SPECIAL→RUBY, MIXED→COIN+토큰 매핑 정확
- credit 은 `Math.addExact` 오버플로 가드, debit 은 잔액 부족(`INSUFFICIENT_FUNDS`)·음수 가드

## 단일 SoT 도입

카테고리→원소 토큰 매핑을 `CurrencyCode.CATEGORY_ELEMENT_TOKEN`(1→DEW/2→SUN/3→BOLT/4→WIND) +
`elementTokenForCategory()` 로 통일. PurchaseService(debit)·RecordService(credit)·AdminService(검증)가
동일 맵을 참조 → paired-consumer drift 차단. 확장(+@ 카테고리) 시 본 맵만 갱신.

## 검증 (evidence)

- `./gradlew compileKotlin compileTestKotlin` — BUILD SUCCESSFUL
- `./gradlew test` — **318 tests, 0 failures / 0 errors / 0 skipped**
- `./gradlew ktlintCheck` — BUILD SUCCESSFUL
- 신규 회귀 테스트 6건: TOKEN category 누락→fail-fast·무료 지급 차단(Purchase), TOKEN 커스텀 카테고리→fail-fast(Purchase),
  커스텀 카테고리 보상 COIN fold + categoryTokens=0(Record), admin TOKEN 생성 3-case(category 누락/커스텀/시스템 성공)
- **실 Postgres 런타임 검증 (2026-07-02, Definition of Done 실환경 게이트)**: Docker postgres:16-alpine 에
  better-auth `auth` 스키마 선행 생성 후 V1→V32 32개 마이그레이션 **순차 clean 적용(exit 0, 오류 0)**.
  실증: `users.basic_coin`/`special_coin` 드롭됨 · `user_tokens` 테이블 드롭됨 · `user_currency_balances` 존재 ·
  `users` 잔여 컬럼 = `id, nickname, role, created_at, updated_at, version` (신 엔티티와 정확 일치) · `currencies` seed 7종.
  testcontainers 스모크(`FlywayMigrationSmokeTest`)는 auth prereq self-contained 로 CI(Linux)용 추가 —
  로컬 Windows 는 testcontainers 가 Gradle JVM 에서 Docker 미탐지로 `assumeTrue` skip(`docker_unavailable`),
  대신 docker CLI + psql 로 위 검증 수행.
- **FE 계약 정합**: `nuxi typecheck` PASS(exit 0) — 신 경제/티어/습관/육성 API 생성 클라이언트와 FE 타입 정합.
- **미검증(human-gate)**: 실 IAP/외부키 런타임, e2e 전수, Figma 픽셀·UI 깨짐 시각 QA, habit/tier FE 페이지(미구현), 다중 화폐 표시 UI
