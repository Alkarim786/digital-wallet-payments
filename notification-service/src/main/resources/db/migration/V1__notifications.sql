CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    kind         VARCHAR(30)  NOT NULL,
    message      TEXT         NOT NULL,
    txn_id       VARCHAR(64),
    amount_minor BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at      TIMESTAMPTZ,
    -- Kafka delivery is at-least-once; this constraint makes consumption idempotent.
    CONSTRAINT ux_notification_event_user UNIQUE (event_id, user_id)
);
CREATE INDEX ix_notifications_user ON notifications (user_id, id DESC);
