package com.chat.model;

public enum MessageStatus {
    SENT,
    DELIVERED,
    READ;

    public static MessageStatus fromString(String value) {
        return MessageStatus.valueOf(value.toUpperCase());
    }
}
