-- V5: Add foreign key from public.users.id → auth."user"(id).
--
-- SEC-004: before this migration, public.users had no hard FK to the
-- better-auth owned auth.user table. Orphan profiles and id-reuse
-- scenarios were possible.
--
-- Prerequisite: the `auth` schema must exist with the better-auth table
-- `"user"` (note quoting — better-auth uses the SQL reserved word). This
-- is created by `frontend/server/db/migrations/001_better_auth_init.sql`
-- before Spring first boots. If the schema is missing we fail fast with
-- a clear error message rather than skipping silently.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.schemata WHERE schema_name = 'auth'
    ) THEN
        RAISE EXCEPTION
            'V5 requires the `auth` schema. Run '
            '`frontend/server/db/migrations/001_better_auth_init.sql` first.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'auth' AND table_name = 'user'
    ) THEN
        RAISE EXCEPTION
            'V5 requires `auth."user"` table from better-auth. Run the better-auth '
            'init SQL first.';
    END IF;
END $$;

-- Any public.users row that doesn't have a matching auth.user row is an
-- orphan from the pre-V5 world. Refuse to add the FK if orphans exist so
-- that operators notice and reconcile by hand.
DO $$
DECLARE orphan_count INT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
    FROM public.users u
    WHERE NOT EXISTS (SELECT 1 FROM auth."user" a WHERE a.id = u.id);

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V5 refuses: % public.users rows have no matching auth."user" row. '
            'Reconcile first, then retry.', orphan_count;
    END IF;
END $$;

ALTER TABLE public.users
    ADD CONSTRAINT users_id_fkey_auth
    FOREIGN KEY (id)
    REFERENCES auth."user" (id)
    ON DELETE CASCADE;

-- Note: the CASCADE already wired in V4 (children → public.users) chains
-- correctly with this new FK, so deleting auth."user"(id) removes the
-- profile row AND every dependent activity_record, wallet_transaction,
-- terrarium, day_note, etc. in a single transaction.
