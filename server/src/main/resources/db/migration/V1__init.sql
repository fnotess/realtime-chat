-- Users. For now a row is auto-created the first time a username connects (dev-only
-- ?user= identity). Phase 7 adds real sign-up with password hashes.
CREATE TABLE users (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username   text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One row per pair of users. The pair is stored in canonical order (smaller id first), so
-- Alice→Bob and Bob→Alice find the same row. The UNIQUE constraint is also what makes
-- "INSERT ... ON CONFLICT DO NOTHING" safe when both sides message each other at once.
-- last_seq is the per-conversation message counter. Incrementing it with UPDATE ... RETURNING
-- takes this row's lock, which serializes senders within one conversation only.
CREATE TABLE conversations (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_a_id  bigint      NOT NULL REFERENCES users (id),
    user_b_id  bigint      NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seq   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT conversations_canonical_order CHECK (user_a_id < user_b_id),
    CONSTRAINT conversations_pair_unique UNIQUE (user_a_id, user_b_id)
);
-- The pair index already covers lookups by user_a_id. Listing a user's conversations also
-- needs lookups by user_b_id.
CREATE INDEX conversations_user_b_idx ON conversations (user_b_id);

CREATE TABLE messages (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id bigint      NOT NULL REFERENCES conversations (id),
    seq             bigint      NOT NULL,
    sender_id       bigint      NOT NULL REFERENCES users (id),
    client_msg_id   uuid        NOT NULL,
    body            text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Ordering guarantee, and the history index: "WHERE conversation_id = ? AND seq < ?
    -- ORDER BY seq DESC LIMIT n" is a single backward index range scan.
    CONSTRAINT messages_conversation_seq_unique UNIQUE (conversation_id, seq),
    -- Idempotency: a client retrying the same message after a reconnect can't store it twice.
    CONSTRAINT messages_sender_client_msg_unique UNIQUE (sender_id, client_msg_id)
);
