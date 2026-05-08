# ADR-018 — OpenAPI tag 흡수 시 controller-service 결합 정책

**Date**: 2026-05-08
**Status**: Accepted
**Source**: `/code-review` ARCH-005 / ARCH-009

## Context

`/code-review` ARCH-005 가 발견한 결합 신호:

- `UserController` 가 `UserDeviceService` 를 직접 주입 (UserApi.registerDevice 흡수)
- `RewardController` 가 `AttendanceService` 를 직접 주입 (RewardApi.checkInAttendance / getAttendanceState 흡수)

OpenAPI spec 의 `tags: [User]`, `tags: [Reward]` 그룹핑을 따라가면 controller 가 cross-domain orchestrator 가 된다. service 패키지 (`userdevice/`, `attendance/`) 는 그대로 분리되어 있어 **"URL 그룹 ≠ 코드 도메인"** 분기 발생.

ARCH-009 가 묻는 질문: **언제 흡수를 멈출지** + **multi-tag endpoint 가 등장하면 어디에 두는지** 가이드라인.

## Decision

### 1. spec tag = primary tag

- 한 endpoint 의 OpenAPI `tags` 가 여러 개일 경우, **첫 번째 tag** (alphabetical 우선이 아닌 spec 작성 순서) 를 primary 로 한다
- primary tag 가 결정한 controller 가 endpoint 를 implement
- 보조 tag 는 redoc / Swagger UI 노출 분류 목적만

### 2. controller 흡수 허용 조건

다음 모두 충족 시 한 controller 가 다른 도메인의 service 를 직접 주입할 수 있다:

1. spec 의 같은 primary tag 로 묶임
2. 흡수되는 service 가 별도 패키지로 유지되어 도메인 경계는 코드 레벨에서 분리
3. controller KDoc 에 흡수 사유 + 코드 도메인 의존 그래프 명시
4. 흡수된 service 의 권한 / 캐시 / 트랜잭션 정책이 *흡수자 controller* 의 정책과 호환

### 3. 흡수 금지 사례

- 다른 도메인의 service 가 본인 도메인의 internal-only 메서드를 호출해야 하는 경우 → 별도 controller + RestClient
- 흡수자 controller 가 cross-domain 트랜잭션 경계를 시작하려는 경우 → application service 계층 신설
- 흡수된 service test 가 흡수자 controller test 와 함께 같은 ApplicationContext 캐시를 강제하는 경우 → split

### 4. 현재 적용 사례 (검토 후 모두 정책 부합)

| Controller | 흡수 service | 정책 부합 |
|------------|--------------|---------|
| `UserController` | `UserDeviceService` | ✓ tags=[User] primary, service 패키지 분리, KDoc 명시 |
| `RewardController` | `AttendanceService` | ✓ tags=[Reward] primary, 동일 |

## Consequences

- spec tag 변경 시 controller 책임 자동 추적 가능
- service 패키지 그대로 유지 → 코드 도메인 경계 보존
- 흡수자 controller 가 비대해지면 → spec tag 분리 PR (예: 본 sprint 의 Shop ↔ Purchase, Record ↔ Upload) 으로 해소

## Alternatives Considered

1. **service 도 controller 와 같은 패키지로 통합** — 거부. 도메인 경계 약화 + service test ripple 폭발
2. **tag 무시하고 코드 도메인별 controller 분리** — 거부. spec ↔ controller 1:1 컴파일 게이트 효과 상실 (PR #17 ~ #23 의 핵심)
3. **multi-tag 시 보조 controller 추가** — 거부. URL 라우팅 충돌 + 13 → 20+ controllers 비대화

## References

- code-review report (`/code-review` 2026-05-07): ARCH-005 / ARCH-009
- 관련 PR: backend #17 (User+UserDevice), #22 (Reward+Attendance)
- spec 변경 사례: openapi #12 (Purchase, Upload tag 분리)
