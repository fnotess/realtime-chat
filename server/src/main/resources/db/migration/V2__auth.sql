-- Real accounts replace the dev-only auto-created users. NOT NULL with no default assumes the
-- users table is empty (the dev DB was reset for this phase); a live system would backfill first.
ALTER TABLE users ADD COLUMN password_hash text NOT NULL;

-- The application normalizes and validates usernames too; this makes the rule hold even for rows
-- written some other way. Lowercase-only means 'Alice' and 'alice' can never be two accounts.
ALTER TABLE users ADD CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9_]{3,32}$');

-- Server-side sessions. Only the SHA-256 of the token is stored: a leaked backup or a SQL
-- injection elsewhere reveals hashes, which can't be turned back into a cookie. A fast hash is
-- enough here (unlike passwords) because the token is 256 random bits, so there's nothing to
-- brute-force. The UNIQUE index is also the lookup path for every authenticated request.
CREATE TABLE sessions (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   bytea       NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- Absolute expiry, never extended, so the cookie's Max-Age and the server agree exactly.
    expires_at   timestamptz NOT NULL,
    -- Informational (e.g. a future "active sessions" page); updated at most once a minute so
    -- reads don't all turn into writes.
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
-- For deleting a user's expired sessions at login.
CREATE INDEX sessions_user_idx ON sessions (user_id);
