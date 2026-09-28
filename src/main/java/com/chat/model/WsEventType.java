package com.chat.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum WsEventType {
    SEND_MESSAGE("send_message"),
    MARK_READ("mark_read"),

    CONNECTED("connected"),
    MESSAGE_SENT("message_sent"),
    NEW_MESSAGE("new_message"),
    STATUS_UPDATE("status_update"),
    USER_ONLINE("user_online"),
    USER_OFFLINE("user_offline"),
    ERROR("error");

    private final String value;

    WsEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static WsEventType fromString(String text) {
        for (WsEventType b : WsEventType.values()) {
            if (b.value.equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + text);
    }
}