package com.chat.model;

public enum MessageStatus {
    SENT,
    DELIVERED,
    READ;

    public static MessageStatus fromString(String value) {
        return MessageStatus.valueOf(value.toUpperCase());
    }

    public boolean canTransitionTo(MessageStatus target) {
        if (target == null) return false;
        return switch (this) {
            case SENT -> target == DELIVERED || target == READ;
            case DELIVERED -> target == READ;
            case READ -> false;
        };
    }
}
