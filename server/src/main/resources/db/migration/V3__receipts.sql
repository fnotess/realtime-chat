-- Delivered/read receipts as cumulative watermarks: "this user has received (read) every message
-- in this conversation up to seq N". Seqs are gapless and ordered per conversation, so one number
-- per member describes the state of every message.
--
-- One row per message per recipient would be ~2 writes per message and a table growing as fast as
-- messages, and "mark 200 messages read" would be 200 row updates. A watermark is one
-- conditional UPDATE however many messages it covers, and it can't go backwards (GREATEST in the
-- update). The cost is that a message can't be "read" while an earlier one isn't, which matches
-- how a chat is actually read, top to bottom.
--
-- Rows are created by the first receipt (upsert), so a missing row means 0/0.
CREATE TABLE conversation_receipts (
    conversation_id    bigint NOT NULL REFERENCES conversations (id),
    user_id            bigint NOT NULL REFERENCES users (id),
    last_delivered_seq bigint NOT NULL DEFAULT 0,
    last_read_seq      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id),
    -- Read implies delivered. The upsert keeps this true; the constraint makes a bug loud instead
    -- of showing a blue tick on a message that was never delivered.
    CONSTRAINT receipts_read_le_delivered CHECK (last_read_seq <= last_delivered_seq)
);
