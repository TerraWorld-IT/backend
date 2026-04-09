# TerraWorld Backend API Specification v1.0

> Frontend(Figma repo) + Backend DB Schema(V1__init_schema.sql) 크로스 분석 기반
> Base URL: `http://localhost:8080/api/v1`
> Auth: JWT Bearer Token (Spring Security)

---

## 0. Frontend ↔ Backend 데이터 매핑 이슈

분석 과정에서 발견된 **프론트엔드-백엔드 간 불일치 사항**. 백엔드 구현 시 반드시 해결 필요.

| # | 이슈 | 프론트엔드 | 백엔드 DB | 해결 방향 |
|---|------|-----------|----------|----------|
| 1 | **아이템 ID 타입** | `string` ('plant-1') | `BIGSERIAL` (숫자) | 백엔드에서 `slug` 컬럼 추가하거나, 프론트를 숫자 ID로 변경 |
| 2 | **재화 구조** | `Currency` 객체 1개 (6필드) | `users.basic_coin` + `user_tokens` 테이블 분리 | API 응답에서 합쳐서 Currency 객체로 내려줌 |
| 3 | **테라리움 배치** | `slotId` (0~4 고정 슬롯) | `pos_x`, `pos_y`, `rotation`, `scale` (자유배치) | 슬롯 기반으로 단순화하거나, pos_x/y에 슬롯 좌표 매핑 |
| 4 | **보상 수치** | basicCoins: 20, tokens: 10 | base_coin_reward: 10, base_token_reward: 5 | **DB seed 수정** 필요 (프론트 기준으로 맞추기) |
| 5 | **교환 비율 불일치** | Profile: 2:1 (0.5배), Shop: 1:1 | `token_exchange_rates.rate` 테이블 | **프론트 버그**: Profile.tsx는 0.5배, Shop.tsx는 1배. 백엔드에서 통일 필요 |
| 6 | **캘린더 메모** | `DayNote` (useState만, 저장 안 됨) | 테이블 없음 | `day_notes` 테이블 추가 필요 |
| 7 | **아이템 가격 구조** | `price` + `priceType` + `tokenPrice` | `price_type` + `price_amount` (단일) | mixed 가격은 별도 컬럼 또는 JSON 처리 필요 |

---

## 1. 인증 (Auth)

### 1-1. POST /api/v1/auth/signup — 회원가입

**사용처:** Profile.tsx (placeholder 버튼)
**인증:** 불필요 (permitAll)
**DB:** `users`, `user_tokens`, `terrariums`

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "테라월드유저"
}
```

**Validation:**
- email: 이메일 형식, 255자 이내, UNIQUE
- password: 8자 이상, 영문+숫자 조합
- nickname: 2~50자

**Response 201:**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "테라월드유저",
  "accessToken": "eyJhbGciOiJIUzI1...",
  "refreshToken": "eyJhbGciOiJIUzI1..."
}
```

**Side Effects (트랜잭션):**
1. `users` INSERT (basic_coin=100, level=1, total_exp=0)
2. `user_tokens` INSERT x4 (각 카테고리별 amount=0)
3. `terrariums` INSERT (background_id=1 기본유리병)
4. `user_items` INSERT x2 (plant-1, rock-1 기본 지급)

**Response 409:**
```json
{ "code": "DUPLICATE_EMAIL", "message": "이미 사용 중인 이메일입니다" }
```

---

### 1-2. POST /api/v1/auth/login — 로그인

**사용처:** Profile.tsx '로그인' 버튼
**인증:** 불필요

**Request Body:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Response 200:**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "테라월드유저",
  "accessToken": "eyJhbGciOiJIUzI1...",
  "refreshToken": "eyJhbGciOiJIUzI1..."
}
```

**Response 401:**
```json
{ "code": "INVALID_CREDENTIALS", "message": "이메일 또는 비밀번호가 올바르지 않습니다" }
```

**토큰 스펙:**
- accessToken: 1시간 (jwt.access-expiration=3600000)
- refreshToken: 7일 (jwt.refresh-expiration=604800000)
- 서명: HS256 (jwt.secret)

---

### 1-3. POST /api/v1/auth/refresh — 토큰 갱신

**Request Body:**
```json
{ "refreshToken": "eyJhbGciOiJIUzI1..." }
```

**Response 200:**
```json
{
  "accessToken": "새로운 accessToken",
  "refreshToken": "새로운 refreshToken"
}
```

**Redis:** refreshToken은 Redis에 저장하여 만료/블랙리스트 관리

---

### 1-4. POST /api/v1/auth/logout — 로그아웃

**사용처:** Profile.tsx '로그아웃' 버튼
**인증:** 필요 (Bearer Token)

**Response 200:**
```json
{ "message": "로그아웃 되었습니다" }
```

**Side Effect:** Redis에서 refreshToken 삭제 (블랙리스트 추가)

---

## 2. 유저 (User)

### 2-1. GET /api/v1/users/me — 내 정보 조회

**사용처:** UserContext.tsx → 앱 초기화 시 호출 (현재 localStorage.getItem 대체)
**인증:** 필요
**DB:** `users` JOIN `user_tokens` JOIN `terrariums` JOIN `user_items`

**Response 200:**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "테라월드유저",
  "currency": {
    "basicCoins": 100.0,
    "specialCoins": 10.0,
    "walkTokens": 0.0,
    "readTokens": 0.0,
    "runTokens": 0.0,
    "drawTokens": 0.0
  },
  "progress": {
    "level": 1,
    "experience": 0,
    "experienceToNext": 100
  },
  "ownedItems": ["plant-1", "rock-1"],
  "placedItems": [
    { "itemId": "plant-1", "slotId": 0 }
  ],
  "terrarium": {
    "id": 1,
    "backgroundId": 1,
    "backgroundName": "기본 유리병"
  }
}
```

**매핑 로직:**
- `currency.basicCoins` ← `users.basic_coin`
- `currency.specialCoins` ← 별도 컬럼 추가 필요 (현재 스키마에 없음!)
- `currency.walkTokens` ← `user_tokens WHERE category_id=1`
- `currency.readTokens` ← `user_tokens WHERE category_id=2`
- `currency.runTokens` ← `user_tokens WHERE category_id=3`
- `currency.drawTokens` ← `user_tokens WHERE category_id=4`
- `progress.experienceToNext` ← `level_configs WHERE level = users.level + 1`
- `ownedItems` ← `user_items JOIN items` → item slug 배열
- `placedItems` ← `terrarium_items JOIN items` → slotId 매핑

> **DB 스키마 수정 필요:** `users` 테이블에 `special_coin BIGINT NOT NULL DEFAULT 0` 컬럼 추가

---

## 3. 활동 기록 (Records)

### 3-1. POST /api/v1/records — 활동 기록 생성

**사용처:** Record.tsx `handleRecord()` (핵심 기능)
**인증:** 필요
**DB:** `activity_records`, `users`, `user_tokens`, `wallet_transactions`

**Request Body:**
```json
{
  "categoryId": 1,
  "duration": 30,
  "note": "공원에서 산책했다"
}
```

**Validation:**
- categoryId: 1~4 (존재하는 categories.id)
- duration: nullable, 양수 (분 단위)
- note: nullable, 500자 이내
- 일일 제한 체크: `categories.daily_limit` (기본 5회, 러닝 3회)

**Response 201:**
```json
{
  "record": {
    "id": 1,
    "categoryId": 1,
    "categoryName": "산책",
    "categoryEmoji": "🚶‍♀️",
    "memo": "공원에서 산책했다",
    "duration": 30,
    "recordedDate": "2026-04-09",
    "createdAt": "2026-04-09T15:30:00Z"
  },
  "reward": {
    "basicCoins": 20,
    "categoryTokens": 10,
    "specialCoins": 0,
    "experienceGained": 10
  },
  "updatedCurrency": {
    "basicCoins": 120.0,
    "specialCoins": 10.0,
    "walkTokens": 10.0,
    "readTokens": 0.0,
    "runTokens": 0.0,
    "drawTokens": 0.0
  }
}
```

**비즈니스 로직 (트랜잭션):**
1. `activity_records` INSERT
2. `users.basic_coin` += reward.basicCoins (20)
3. `user_tokens` UPDATE SET amount += reward.categoryTokens (10) WHERE category_id=요청카테고리
4. `users.total_exp` += experienceGained (10)
5. 레벨업 체크: `total_exp >= level_configs.required_exp` → level++
6. `wallet_transactions` INSERT x2 (basic_coin 변동, token 변동 기록)

**Response 429:**
```json
{ "code": "DAILY_LIMIT_EXCEEDED", "message": "오늘 산책 기록 횟수(5회)를 초과했습니다" }
```

---

### 3-2. GET /api/v1/records — 기록 목록 조회

**사용처:** Record.tsx 최근기록 (`.slice(0, 5)`), Calendar.tsx 월별조회
**인증:** 필요

**Query Parameters:**
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|-------|------|
| page | int | N | 0 | 페이지 번호 |
| size | int | N | 20 | 페이지 크기 (max 50) |
| categoryId | int | N | - | 카테고리 필터 |
| dateFrom | date | N | - | 시작일 (YYYY-MM-DD) |
| dateTo | date | N | - | 종료일 (YYYY-MM-DD) |
| year | int | N | - | 연도 필터 (캘린더용) |
| month | int | N | - | 월 필터 (캘린더용) |

**Response 200:**
```json
{
  "content": [
    {
      "id": 1,
      "categoryId": 1,
      "categoryName": "산책",
      "categoryEmoji": "🚶‍♀️",
      "memo": "공원에서 산책했다",
      "duration": 30,
      "recordedDate": "2026-04-09",
      "createdAt": "2026-04-09T15:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

### 3-3. DELETE /api/v1/records/{recordId} — 기록 삭제

**인증:** 필요
**DB:** `activity_records` soft delete (`is_deleted=true`)

**Validation:**
- 본인 기록만 삭제 가능 (user_id 체크)

**Response 200:**
```json
{ "message": "기록이 삭제되었습니다" }
```

> 보상 회수 여부는 정책 결정 필요. 프론트엔드에는 삭제 기능 UI 없음.

---

### 3-4. GET /api/v1/records/statistics — 활동 통계

**사용처:** Calendar.tsx 활동 통계 카드
**인증:** 필요

**Response 200:**
```json
{
  "todayRecords": 2,
  "thisWeekRecords": 8,
  "totalRecords": 45,
  "byCategory": [
    { "categoryId": 1, "categoryName": "산책", "emoji": "🚶‍♀️", "count": 15 },
    { "categoryId": 2, "categoryName": "독서", "emoji": "📖", "count": 12 },
    { "categoryId": 3, "categoryName": "러닝", "emoji": "👟", "count": 8 },
    { "categoryId": 4, "categoryName": "낙서", "emoji": "🎨", "count": 10 }
  ]
}
```

**SQL 참고:**
```sql
-- 오늘 기록 수
SELECT COUNT(*) FROM activity_records 
WHERE user_id=? AND recorded_date=CURRENT_DATE AND is_deleted=false;

-- 이번 주 기록 수
SELECT COUNT(*) FROM activity_records 
WHERE user_id=? AND recorded_date >= CURRENT_DATE - INTERVAL '7 days' AND is_deleted=false;

-- 카테고리별
SELECT category_id, COUNT(*) FROM activity_records 
WHERE user_id=? AND is_deleted=false GROUP BY category_id;
```

---

## 4. 상점 (Shop)

### 4-1. GET /api/v1/items — 상점 아이템 목록

**사용처:** Shop.tsx (현재 SHOP_ITEMS 하드코딩 42개를 API로 대체)
**인증:** 불필요 (or 필요 — 정책에 따라)
**DB:** `items`

**Query Parameters:**
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| categoryId | int | N | 카테고리 필터 |
| rarity | string | N | COMMON, RARE, EPIC |
| layout | string | N | FOREGROUND, BACKGROUND, FIGURE |

**Response 200:**
```json
{
  "items": [
    {
      "id": 1,
      "slug": "plant-1",
      "name": "작은 선인장",
      "description": null,
      "categoryId": 1,
      "categoryName": "산책",
      "priceType": "BASIC",
      "priceAmount": 30,
      "tokenPrice": null,
      "rarity": "COMMON",
      "assetUrl": "🌵",
      "width": 512,
      "height": 512,
      "isAnimated": false,
      "layout": "FOREGROUND"
    }
  ]
}
```

> **DB 수정 필요:** `items` 테이블에 `slug VARCHAR(50) UNIQUE`, `token_price INTEGER`, `layout VARCHAR(20)`, `is_animated BOOLEAN DEFAULT FALSE` 추가

---

### 4-2. GET /api/v1/items/{itemId} — 아이템 상세

**Response 200:** 위 items 배열의 단일 객체

---

## 5. 구매 (Purchase)

### 5-1. POST /api/v1/purchases — 아이템 구매

**사용처:** Shop.tsx `handlePurchase()` (핵심 기능)
**인증:** 필요
**DB:** `items`, `users`, `user_tokens`, `user_items`, `wallet_transactions`

**Request Body:**
```json
{
  "itemId": 1
}
```

**Validation (프론트엔드 로직 기반):**
1. 아이템 존재 & 활성 여부 (`items.is_active=true`)
2. 이미 보유 여부 (`user_items` 중복 체크)
3. 재화 충분 여부:
   - `BASIC`: `users.basic_coin >= items.price_amount`
   - `SPECIAL`: `users.special_coin >= items.price_amount`
   - `MIXED`: `users.basic_coin >= items.price_amount` AND `user_tokens.amount >= items.token_price`
   - `TOKEN`: `user_tokens.amount >= items.price_amount`

**Response 200:**
```json
{
  "purchasedItem": {
    "id": 1,
    "slug": "plant-1",
    "name": "작은 선인장"
  },
  "updatedCurrency": {
    "basicCoins": 70.0,
    "specialCoins": 10.0,
    "walkTokens": 0.0,
    "readTokens": 0.0,
    "runTokens": 0.0,
    "drawTokens": 0.0
  },
  "ownedItems": ["plant-1", "rock-1", "book-1"]
}
```

**비즈니스 로직 (트랜잭션):**
1. 재화 차감 (priceType별 분기)
2. `user_items` INSERT
3. `wallet_transactions` INSERT (재화 변동 기록)

**Response 400:**
```json
{ "code": "INSUFFICIENT_FUNDS", "message": "재화가 부족합니다" }
```

**Response 409:**
```json
{ "code": "ALREADY_OWNED", "message": "이미 보유한 아이템입니다" }
```

---

## 6. 재화 교환 (Exchange)

### 6-1. POST /api/v1/exchange/special-to-basic — 스페셜→기본 코인 교환

**사용처:** Shop.tsx 환전모달
**인증:** 필요
**DB:** `users`, `wallet_transactions`

**Exchange Rate:** 1 스페셜 = 2 기본 (Shop.tsx `EXCHANGE_RATE_COIN = 2`)

**Request Body:**
```json
{
  "amount": 5
}
```

**Validation:**
- amount > 0
- `users.special_coin >= amount`

**Response 200:**
```json
{
  "exchanged": {
    "fromType": "SPECIAL_COIN",
    "fromAmount": 5,
    "toType": "BASIC_COIN",
    "toAmount": 10,
    "rate": 2.0
  },
  "updatedCurrency": {
    "basicCoins": 110.0,
    "specialCoins": 5.0,
    "walkTokens": 0.0,
    "readTokens": 0.0,
    "runTokens": 0.0,
    "drawTokens": 0.0
  }
}
```

---

### 6-2. POST /api/v1/exchange/tokens — 토큰 간 교환

**사용처:** Profile.tsx 토큰교환 다이얼로그, Shop.tsx 환전모달
**인증:** 필요
**DB:** `user_tokens`, `token_exchange_rates`, `wallet_transactions`

> **프론트 불일치 주의:** Profile.tsx는 0.5배(2:1), Shop.tsx는 1배(1:1).
> 백엔드에서 `token_exchange_rates.rate` 기준으로 통일 (DB seed: 2.0 = 2개 보내면 1개 받음)

**Request Body:**
```json
{
  "fromCategoryId": 1,
  "toCategoryId": 2,
  "amount": 10
}
```

**Validation:**
- fromCategoryId != toCategoryId
- amount > 0
- `user_tokens.amount >= amount` (from 카테고리)
- `token_exchange_rates` 활성 여부 (`is_active=true`)

**Response 200:**
```json
{
  "exchanged": {
    "fromCategory": "산책",
    "fromAmount": 10,
    "toCategory": "독서",
    "toAmount": 5,
    "rate": 0.5
  },
  "updatedCurrency": {
    "basicCoins": 100.0,
    "specialCoins": 10.0,
    "walkTokens": 0.0,
    "readTokens": 5.0,
    "runTokens": 0.0,
    "drawTokens": 0.0
  }
}
```

**비즈니스 로직:**
```
receiveAmount = floor(amount / token_exchange_rates.rate)
// rate=2.0 → 10개 보내면 5개 받음
```

---

## 7. 테라리움 (Terrarium)

### 7-1. GET /api/v1/terrarium — 테라리움 상태 조회

**사용처:** Home.tsx (테라리움 화면)
**인증:** 필요
**DB:** `terrariums`, `terrarium_items`, `items`

**Response 200:**
```json
{
  "terrariumId": 1,
  "background": {
    "id": 1,
    "name": "기본 유리병",
    "assetUrl": "/backgrounds/default.png"
  },
  "placedItems": [
    {
      "id": 1,
      "itemId": 1,
      "itemSlug": "plant-1",
      "itemImage": "🌵",
      "itemName": "작은 선인장",
      "itemLayout": "FOREGROUND",
      "isAnimated": false,
      "slotId": 2
    }
  ],
  "maxSlots": 5,
  "unlockedBackgrounds": [1]
}
```

---

### 7-2. PUT /api/v1/terrarium/placements — 아이템 배치 변경

**사용처:** Home.tsx `handlePlaceItem()`, `handleRemoveItem()`
**인증:** 필요
**DB:** `terrarium_items`

**Request Body:**
```json
{
  "placedItems": [
    { "itemId": 1, "slotId": 0 },
    { "itemId": 5, "slotId": 3 }
  ]
}
```

**Validation (Home.tsx 기준):**
- 아이템을 보유하고 있어야 함 (`user_items` 존재)
- 슬롯 규칙 검증:
  - slotId 0, 1 → `layout = BACKGROUND` 만 허용
  - slotId 3 → `layout = FIGURE` 만 허용
  - slotId 2, 4 → `layout = FOREGROUND` 만 허용
- 같은 슬롯에 중복 배치 불가
- `maxSlots` 초과 불가 (`level_configs.max_items`)

**Response 200:**
```json
{
  "placedItems": [
    { "itemId": 1, "itemSlug": "plant-1", "slotId": 0 }
  ]
}
```

**Response 400:**
```json
{ "code": "INVALID_SLOT", "message": "후경 슬롯에는 후경 아이템만 배치할 수 있습니다" }
```

---

### 7-3. POST /api/v1/terrarium/heart — 하트 클릭 보상

**사용처:** Home.tsx `handleHeartClick()` — 클릭 시 +0.1 basicCoins
**인증:** 필요

**Request Body:** 없음

**Response 200:**
```json
{
  "reward": 0.1,
  "updatedBasicCoins": 100.1
}
```

**비즈니스 로직:**
- 클릭당 +0.1 basicCoins
- Rate limiting 권장 (초당 최대 5회 등)
- `wallet_transactions` INSERT

> 프론트엔드에서 소수점 처리 (`toFixed(1)`) 사용 중이므로 백엔드도 소수점 지원 필요.
> DB: `basic_coin` 타입을 `BIGINT` → `NUMERIC(15,1)` 변경 고려. 또는 0.1을 정수 1로 저장 (x10 스케일링)

---

## 8. 캘린더 메모 (Day Notes)

### DB 마이그레이션 필요

```sql
-- V3__add_day_notes.sql
CREATE TABLE day_notes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    note_date DATE NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, note_date)
);
```

### 8-1. GET /api/v1/notes/{date} — 특정 날짜 메모 조회

**사용처:** Calendar.tsx `getNoteForDate()`
**인증:** 필요

**Path:** date = `YYYY-MM-DD`

**Response 200:**
```json
{
  "date": "2026-04-09",
  "note": "오늘 날씨가 좋았다"
}
```

**Response 404:** 메모 없음

---

### 8-2. PUT /api/v1/notes/{date} — 메모 저장/수정

**사용처:** Calendar.tsx `handleSaveNote()`
**인증:** 필요

**Request Body:**
```json
{
  "note": "오늘 날씨가 좋았다"
}
```

**Response 200:**
```json
{
  "date": "2026-04-09",
  "note": "오늘 날씨가 좋았다"
}
```

**비즈니스 로직:** UPSERT (있으면 UPDATE, 없으면 INSERT)

---

### 8-3. DELETE /api/v1/notes/{date} — 메모 삭제

**사용처:** Calendar.tsx 빈 메모 저장 시 삭제

**Response 200:**
```json
{ "message": "메모가 삭제되었습니다" }
```

---

## 9. 카테고리 (Categories)

### 9-1. GET /api/v1/categories — 카테고리 목록

**사용처:** Record.tsx, Calendar.tsx, Shop.tsx, Profile.tsx (현재 하드코딩 CATEGORIES 배열 대체)
**인증:** 불필요 (permitAll)
**DB:** `categories`

**Response 200:**
```json
{
  "categories": [
    {
      "id": 1,
      "name": "산책",
      "iconUrl": "/icons/walk.svg",
      "color": "#00A95C",
      "tokenName": "산책토큰",
      "emoji": "🚶‍♀️",
      "baseCoinReward": 20,
      "baseTokenReward": 10,
      "dailyLimit": 5
    },
    {
      "id": 2,
      "name": "독서",
      "iconUrl": "/icons/read.svg",
      "color": "#0078BF",
      "tokenName": "독서토큰",
      "emoji": "📖",
      "baseCoinReward": 20,
      "baseTokenReward": 10,
      "dailyLimit": 5
    },
    {
      "id": 3,
      "name": "러닝",
      "iconUrl": "/icons/run.svg",
      "color": "#E3505F",
      "tokenName": "러닝토큰",
      "emoji": "👟",
      "baseCoinReward": 20,
      "baseTokenReward": 10,
      "dailyLimit": 3
    },
    {
      "id": 4,
      "name": "낙서",
      "iconUrl": "/icons/draw.svg",
      "color": "#FF6C2F",
      "tokenName": "낙서토큰",
      "emoji": "🎨",
      "baseCoinReward": 20,
      "baseTokenReward": 10,
      "dailyLimit": 5
    }
  ]
}
```

> **DB seed 수정 필요:** `emoji` 컬럼 추가, base_coin_reward를 20으로, base_token_reward를 10으로 변경

---

## 10. 레벨 (Level)

### 10-1. GET /api/v1/levels — 레벨 설정 조회

**사용처:** Home.tsx 레벨업 다이얼로그 (미래 기능)
**인증:** 필요
**DB:** `level_configs`

**Response 200:**
```json
{
  "currentLevel": 1,
  "currentExp": 0,
  "levels": [
    { "level": 1, "requiredExp": 0, "rewardType": null, "maxItems": 10 },
    { "level": 2, "requiredExp": 100, "rewardType": "MAX_ITEMS_UP", "rewardValue": 5, "maxItems": 15 },
    { "level": 3, "requiredExp": 300, "rewardType": "MAX_ITEMS_UP", "rewardValue": 5, "maxItems": 20 }
  ]
}
```

---

## 11. 소셜 (Social) — Phase 2

### 11-1. POST /api/v1/invites — 친구 초대 링크 생성

**사용처:** Record.tsx `handleInviteFriend()` (placeholder)
**상태:** 미구현 (프론트엔드에서 toast만 표시)

**Response 200:**
```json
{
  "inviteCode": "ABC123",
  "inviteLink": "https://terraworld.app/invite/ABC123",
  "expiresAt": "2026-04-16T00:00:00Z"
}
```

---

### 11-2. POST /api/v1/invites/{code}/accept — 초대 수락

**Response 200:**
```json
{
  "message": "초대가 수락되었습니다",
  "reward": { "specialCoins": 5 }
}
```

---

## 12. 보상 (Rewards) — Phase 2

### 12-1. POST /api/v1/rewards/ad — 광고 시청 보상

**사용처:** Home.tsx 무료코인 다이얼로그 (placeholder)
**비즈니스 규칙:** 하루 3회 제한, 1회당 스페셜코인 5개

**Request Body:** 없음

**Response 200:**
```json
{
  "reward": { "specialCoins": 5 },
  "dailyWatchCount": 1,
  "remainingToday": 2,
  "updatedCurrency": { ... }
}
```

**Response 429:**
```json
{ "code": "DAILY_AD_LIMIT", "message": "오늘 광고 시청 횟수(3회)를 초과했습니다" }
```

---

## 13. 공유 (Share)

### 13-1. POST /api/v1/share/terrarium — 테라리움 공유 이미지 저장

**사용처:** Home.tsx `handleShare()` (현재 html2canvas로 클라이언트에서 처리)
**인증:** 불필요 (permitAll)
**상태:** 서버사이드 이미지 생성이 필요하면 구현, 아니면 클라이언트 처리 유지

---

## 14. 관리자 (Admin)

### 14-1. POST /api/v1/admin/items — 아이템 등록

**인증:** ADMIN 역할 필요
**DB:** `items`

**Request Body:**
```json
{
  "slug": "new-plant",
  "name": "새로운 식물",
  "description": "설명",
  "categoryId": 1,
  "priceType": "BASIC",
  "priceAmount": 50,
  "tokenPrice": null,
  "rarity": "COMMON",
  "assetUrl": "🌱",
  "layout": "FOREGROUND",
  "isAnimated": false
}
```

### 14-2. PUT /api/v1/admin/items/{itemId} — 아이템 수정

### 14-3. DELETE /api/v1/admin/items/{itemId} — 아이템 비활성화

---

## DB 스키마 수정 요약 (V3 마이그레이션)

```sql
-- V3__frontend_alignment.sql

-- 1. users 테이블에 special_coin 추가
ALTER TABLE users ADD COLUMN special_coin BIGINT NOT NULL DEFAULT 0;

-- 2. items 테이블 프론트 정렬
ALTER TABLE items ADD COLUMN slug VARCHAR(50) UNIQUE;
ALTER TABLE items ADD COLUMN token_price INTEGER;
ALTER TABLE items ADD COLUMN layout VARCHAR(20) NOT NULL DEFAULT 'FOREGROUND';
ALTER TABLE items ADD COLUMN is_animated BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. categories 테이블에 emoji 추가
ALTER TABLE categories ADD COLUMN emoji VARCHAR(10);

-- 4. day_notes 테이블 생성
CREATE TABLE day_notes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    note_date DATE NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, note_date)
);

-- 5. terrarium_items에 slot_id 추가 (프론트 슬롯 기반)
ALTER TABLE terrarium_items ADD COLUMN slot_id INTEGER;

-- 6. categories seed 업데이트 (프론트 보상 수치 기준)
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='🚶‍♀️' WHERE id=1;
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='📖' WHERE id=2;
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='👟' WHERE id=3;
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='🎨' WHERE id=4;
```

---

## API 엔드포인트 요약

| # | Method | Endpoint | Auth | 우선순위 | 사용처 |
|---|--------|----------|------|---------|-------|
| 1 | POST | /auth/signup | X | P1 | Profile |
| 2 | POST | /auth/login | X | P1 | Profile |
| 3 | POST | /auth/refresh | X | P1 | Auto |
| 4 | POST | /auth/logout | O | P1 | Profile |
| 5 | GET | /users/me | O | P1 | UserContext |
| 6 | POST | /records | O | **P0** | Record |
| 7 | GET | /records | O | **P0** | Record, Calendar |
| 8 | DELETE | /records/{id} | O | P2 | - |
| 9 | GET | /records/statistics | O | P1 | Calendar |
| 10 | GET | /items | X/O | **P0** | Shop |
| 11 | GET | /items/{id} | X/O | P2 | Shop |
| 12 | POST | /purchases | O | **P0** | Shop |
| 13 | POST | /exchange/special-to-basic | O | P1 | Shop |
| 14 | POST | /exchange/tokens | O | P1 | Profile, Shop |
| 15 | GET | /terrarium | O | **P0** | Home |
| 16 | PUT | /terrarium/placements | O | **P0** | Home |
| 17 | POST | /terrarium/heart | O | P1 | Home |
| 18 | GET | /notes/{date} | O | P1 | Calendar |
| 19 | PUT | /notes/{date} | O | P1 | Calendar |
| 20 | DELETE | /notes/{date} | O | P2 | Calendar |
| 21 | GET | /categories | X | **P0** | 전체 |
| 22 | GET | /levels | O | P2 | Home |
| 23 | POST | /invites | O | P3 | Record |
| 24 | POST | /rewards/ad | O | P3 | Home |

**P0** = MVP 필수 (6개)
**P1** = 핵심 기능 (8개)
**P2** = 부가 기능 (5개)
**P3** = 미래 기능 (2개+)

---

## 공통 에러 응답 형식

```json
{
  "code": "ERROR_CODE",
  "message": "사용자에게 보여줄 메시지",
  "timestamp": "2026-04-09T15:30:00Z"
}
```

| HTTP Status | Code | 설명 |
|-------------|------|------|
| 400 | BAD_REQUEST | 잘못된 요청 파라미터 |
| 400 | INSUFFICIENT_FUNDS | 재화 부족 |
| 400 | INVALID_SLOT | 잘못된 슬롯 배치 |
| 401 | UNAUTHORIZED | 인증 토큰 없음/만료 |
| 403 | FORBIDDEN | 권한 없음 |
| 404 | NOT_FOUND | 리소스 없음 |
| 409 | DUPLICATE_EMAIL | 이메일 중복 |
| 409 | ALREADY_OWNED | 이미 보유한 아이템 |
| 429 | DAILY_LIMIT_EXCEEDED | 일일 제한 초과 |
| 500 | INTERNAL_ERROR | 서버 오류 |

---

## 공통 헤더

**Request:**
```
Authorization: Bearer {accessToken}
Content-Type: application/json
```

**Response:**
```
Content-Type: application/json
```

---

## Swagger UI

`http://localhost:8080/swagger-ui` (springdoc-openapi 설정 완료)

---

## 구현 순서 제안

### Phase 0: 인프라 (1일)
1. Entity 클래스 (User, Category, Item, etc.)
2. Repository 인터페이스
3. JWT 필터 구현 (SecurityConfig에 연결)
4. Global Exception Handler
5. V3 마이그레이션 (프론트 정렬)

### Phase 1: MVP API (2~3일)
1. `GET /categories` → 가장 단순, 의존성 없음
2. `POST /auth/signup + login` → JWT 발급
3. `GET /users/me` → 프론트 UserContext 대체
4. `POST /records + GET /records` → 핵심 기능
5. `GET /items + POST /purchases` → 상점
6. `GET /terrarium + PUT /terrarium/placements` → 테라리움

### Phase 2: 확장 (2일)
1. `POST /exchange/*` → 환전
2. `GET /records/statistics` → 통계
3. `PUT/GET/DELETE /notes` → 캘린더 메모
4. `POST /terrarium/heart` → 하트 클릭

### Phase 3: 소셜 & 미래 (추후)
1. 친구 초대
2. 광고 보상
3. 레벨업 시스템
