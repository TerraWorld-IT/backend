# ADR-019 — Spec ↔ DTO drift 가드 정책

**Date**: 2026-05-08
**Status**: Accepted
**Source**: `/code-review` ARCH-006 / SF-001 / SF-002 / SF-007

## Context

`*Api` interface implement (R2 POC) 가 도입한 컴파일 타임 게이트는 다음 케이스만 잡는다:

- **잡히는 경우**: spec 의 method 시그니처 변경 (path / param / required 필드 추가/제거 / return type 변경) → controller `override` 컴파일 fail
- **놓치는 경우**:
  - spec 의 optional 필드 추가 — mapper 가 새 필드를 그냥 무시 (ARCH-006)
  - spec 의 enum 값 추가 — DB 에 새 값이 들어와도 forValue 가 throw 하면 controller mapper 의 `runCatching` 이 swallow (SF-001/002)
  - spec photoUrl 의 타입/format 변경처럼 generator 가 다른 java 타입으로 emit 시 jakarta validator 와 충돌 가능 (PR #13 사례)

또한 `@Disabled` 코멘트가 PR review 후 단순 제거되면 follow-up 추적 단서 소실 (SF-007).

## Decision

### 1. mapper 에서 unknown enum 값 → WARN 로그 + null 반환 (현행 유지)

- `runCatching { Enum.forValue(it) }.onFailure { log.warn(...) }.getOrNull()` 패턴
- 사용자 응답을 막지 않으면서 운영 대시보드 / Sentry 가 spec drift 사전 감지
- 적용 위치: `LevelController.mapRewardTypeOrLogDrift`, `TerrariumController.toEvolutionStageOrNull`
- 향후 새 enum 변환이 추가될 때 동일 패턴 강제 — 본 ADR 을 PR description 에 인용해 review

### 2. service contract 위반 → fail-fast (controller 에서 `checkNotNull`)

- service 가 `null` 을 반환했지만 spec 이 required 라면 controller 가 즉시 IllegalStateException
- silent semantic shift (예: `slotId ?: 0` 으로 BACKGROUND 슬롯 둔갑) 차단
- 적용 위치: `TerrariumController.updateTerrariumPlacements` 의 `checkNotNull(detail.slotId)`

### 3. spec 의 optional 필드 추가는 service signature 마이그레이션으로 압박

- ARCH-006 의 본질적 해소: service 가 generated DTO 직접 반환 → optional 필드 누락도 컴파일러가 잡음 (ARCH-008 plan 참조)
- 그 전까지는 본 ADR + SF-007 의 PR review 가드로 보완

### 4. `@Disabled` 제거 시 PR description 에 명시 + GitHub issue 격상

- `@Disabled` 가 가리키던 followup 작업이 PR 로 해소되었다면 PR description 에 다음을 포함:
  - "Why disabled was removed" 1줄
  - 관련 commit / spec PR 링크
- 작업이 아직 남아있는 경우 GitHub issue 로 격상하고 `@Disabled` reason 에 issue 링크 추가
- SF-009 (negative case test 부재) 같은 "Out of scope" 도 동일 — PR description 에서 단순 명시 대신 GitHub issue 링크

### 5. drift-check CI 단계 (옵션)

본 ADR 제정 시점에는 미적용. 향후 다음 중 하나 도입 검토:

- **A**: openapi PR 의 `oasdiff` breaking change 감지에 spec → backend 영향 범위 자동 코멘트
- **B**: backend CI 에서 generated `*Api.kt` 의 hash 와 controller `override` 시그니처 hash 비교, 누락 알림
- **C**: 간단한 grep — generated `@get:JsonProperty(required = true)` 필드 모두가 `*Service` 또는 `*Controller` mapper 에 등장하는지 컴파일 후 reflection 검증

## Consequences

- 운영 대시보드 / Sentry 에 `spec drift:` 로그 라인이 신호. 알림 임계값 설정 필요
- service contract 가 더 엄격해짐 — service 가 nullable 반환할 때 controller fail-fast 가 사용자 응답 500 으로 이어짐 (silent semantic shift 보다 안전)
- ADR-018 과 결합: spec tag 분리 시 controller 흡수 정책도 본 가드와 함께 명시

## References

- code-review report (`/code-review` 2026-05-07): ARCH-006, SF-001, SF-002, SF-007, SF-009
- 관련 PR: backend #28 (silent-failure 5건), #25 (`@Disabled` 제거)
- 후속 plan: docs/plans/2026-05-08-service-dto-migration.md (ARCH-008)
