# Plan: Service 계층 generated DTO 마이그레이션 (ARCH-008)

**Date**: 2026-05-08
**Status**: Draft
**Source**: `/code-review` ARCH-008 / SF-007 (followup tracking)

## Context

PR #27 (CurrencyMappers 추출) 과 PR #30 (dto 패키지 정리) 후 backend 의 13 controllers 는 모두 generated `*Api` 를 implement 한다. 그러나 service 는 여전히 **local DTO** (`com.terraworld.api.*.dto.*`) 를 반환하고 controller 가 매번 generated DTO 로 변환한다.

이 보일러플레이트가 한 PR 에서 다 정리되지 못한 이유:
- service test (ExchangeServiceTest, RewardServiceTest, PurchaseServiceTest 등) 가 local DTO 로 검증
- service signature 변경 = test 시그니처 변경 = ripple

본 plan 은 ripple 영향이 작은 도메인부터 단계적으로 service 를 generated DTO 직접 반환으로 전환한다.

## Migration Order (ripple 적은 순)

| Phase | Domain | Service test 수 | Controller mapper 수 | Risk |
|-------|--------|----------------|---------------------|------|
| 1 | Level | 0 | 0 (이미 generated 직접) | trivial |
| 2 | Note | 0 | 0 | trivial |
| 3 | Category | 0 | 0 | trivial |
| 4 | Item / PhotoUpload | 1 (PhotoUpload) | 0 / 1 | low — single test |
| 5 | Reward (claimAdReward) | 1 (RewardService) | 1 (AdRewardResponsePayload) | medium |
| 6 | Attendance | 0 | 1 (Attendance toApi) | medium |
| 7 | Purchase | 1 (PurchaseService) | 1 | medium |
| 8 | Exchange | 1 (ExchangeService) | 1 | medium |
| 9 | Ranking | 1 (RankingService) | 1 | medium |
| 10 | Social (Invite) | 1 (InviteService) | 1 | medium |
| 11 | Record (createRecord/list/stat) | 0 (no service test, but PR #28 의 photoUrl 이미 wired) | 큰 mapper | high |
| 12 | Terrarium (4 endpoints + EvolutionStage enum) | 0 | 매우 큰 mapper | high |
| 13 | User (getMe + UserMeResponse 의 nested 5종) | 0 | 가장 큰 mapper | high |

## Per-phase 작업 패턴

각 phase 는 다음 단계로 단일 PR 처리:

1. service signature 변경: local DTO → generated `io.terraworld.api.model.*`
2. service 내부 로직 그대로, 반환 시점에서만 generated 생성자 호출
3. service test 의 assertion 갱신 (local DTO field name 그대로면 mostly compatible)
4. controller 의 mapper / import alias 제거
5. local DTO 파일 삭제 (해당 도메인 한정)
6. ktlint + test 검증
7. CHANGELOG entry: `refactor(service): {domain} 가 generated DTO 직접 반환 (ARCH-008-phase-N)`

## Cross-cutting Migration

phase 5+ 에서 모든 controller 가 generated CurrencyResponse 를 반환하면 `shared/dto/CurrencyMappers.kt` 도 obsolete. 그 시점에:

- `shared/dto/CurrencyMappers.kt` 삭제
- `user/dto/CurrencyResponse.kt`, `CategoryTokenAmount.kt` 도 generated 사용 후 삭제
- `CurrencyBuilder` (이름 그대로 두거나 `WalletBuilder` 로 rename) 가 generated 직접 반환

이 cleanup 은 별도 PR — phase 13 후 또는 phase 5 / 7 / 8 묶음 후.

## Risks

- generated DTO 의 enum (e.g. `LevelConfigResponse.RewardType`, `EvolutionStage`, `ItemResponse.PriceType`) 이 spec 에 없는 DB 값을 만나면 throw — service 가 직접 forValue 호출하면 validation chain 에 노출. 현재 controller mapper 의 `runCatching + warn log` 패턴 (PR #28 SF-001/002) 을 service 로 이동하거나 mapper helper 로 추출.
- PagedRecordResponse 같은 wrapper 가 service 시그니처에 들어오면 Spring Data Page 사용 패턴이 깨짐. controller 에서 PageImpl ↔ PagedRecordResponse 변환 패턴 유지하거나 service 가 PagedRecordResponse 직접 반환.

## Out of scope

- generated DTO 의 자체 변경 (spec 영향)
- service test 인프라 마이그레이션 (`@Mock` → `@MockK` 등)
- repository 계층 마이그레이션 (별도 plan)
