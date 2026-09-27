package com.chat.model;

import io.vertx.core.json.JsonObject;

public record User(long id, String username) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("username", username);
    }

    public JsonObject toJsonWithOnline(boolean online) {
        return toJson().put("online", online);
    }
}
