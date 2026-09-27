package com.chat.model;

import io.vertx.core.json.JsonObject;

public record WsEvent(String type, JsonObject payload) {

    public JsonObject toJson() {
        JsonObject json = new JsonObject().put("type", type);
        if (payload != null) {
            payload.forEach(entry -> json.put(entry.getKey(), entry.getValue()));
        }
        return json;
    }

    public static WsEvent of(String type) {
        return new WsEvent(type, null);
    }

    public static WsEvent of(String type, JsonObject payload) {
        return new WsEvent(type, payload);
    }
}
