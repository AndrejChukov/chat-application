package com.chat.model;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

public record Message(
        long id,
        long senderId,
        String senderUsername,
        long recipientId,
        String recipientUsername,
        String content,
        MessageStatus status,
        Instant createdAt
) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("senderId", senderId)
                .put("senderUsername", senderUsername)
                .put("recipientId", recipientId)
                .put("recipientUsername", recipientUsername)
                .put("content", content)
                .put("status", status.name())
                .put("createdAt", createdAt.toString());
    }
}
