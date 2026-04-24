-- V6: Phase 2 scope — 광고 보상(/rewards/ad) 및 초대(/invites/*) 지원.
--
-- 추가 테이블:
--   public.ad_watch_logs — 일일 광고 시청 이력 (rate limit 산정용)
--   public.invites       — 초대 코드 발급/수락 이력
--
-- 둘 다 public.users.id(TEXT) 를 FK로 참조. V5 의 auth.user cascade 체인과
-- 일관되도록 ON DELETE CASCADE 로 묶는다.

-- ─── 광고 보상 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.ad_watch_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT        NOT NULL,
    watched_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reward_special_coins INT NOT NULL DEFAULT 1,
    CONSTRAINT ad_watch_logs_user_fk
        FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ad_watch_logs_user_day
    ON public.ad_watch_logs (user_id, watched_at);

COMMENT ON TABLE public.ad_watch_logs
    IS 'POST /rewards/ad 시청 이력. 일일 5회 제한 산정 근거.';

-- ─── 초대 ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.invites (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(20) NOT NULL UNIQUE,
    inviter_user_id TEXT        NOT NULL,
    invitee_user_id TEXT,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP   NOT NULL,
    accepted_at     TIMESTAMP,
    CONSTRAINT invites_inviter_fk
        FOREIGN KEY (inviter_user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT invites_invitee_fk
        FOREIGN KEY (invitee_user_id) REFERENCES public.users (id) ON DELETE SET NULL,
    CONSTRAINT invites_not_self
        CHECK (invitee_user_id IS NULL OR invitee_user_id <> inviter_user_id)
);

CREATE INDEX IF NOT EXISTS idx_invites_code   ON public.invites (code);
CREATE INDEX IF NOT EXISTS idx_invites_inviter ON public.invites (inviter_user_id);

COMMENT ON TABLE public.invites
    IS 'POST /invites / POST /invites/{code}/accept 백킹 테이블. 7일 만료.';
