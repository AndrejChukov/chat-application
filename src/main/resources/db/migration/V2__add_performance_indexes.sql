-- Для выборки истории чата:
CREATE INDEX idx_messages_direct_conversation
    ON messages (sender_id, recipient_id, created_at ASC);

CREATE INDEX idx_messages_reverse_conversation
    ON messages (recipient_id, sender_id, created_at ASC);

-- Для быстрого обновления статусов непрочитанных сообщений:
CREATE INDEX idx_messages_unread_status
    ON messages (recipient_id, sender_id, status);