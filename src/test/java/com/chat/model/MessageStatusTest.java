package com.chat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Unit-тесты State Machine статусов сообщений")
class MessageStatusTest {

    @Test
    @DisplayName("SENT может переходить в DELIVERED и READ")
    void sentTransitions() {
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.DELIVERED));
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.READ));
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.SENT), "Переход в самого себя запрещен");
    }

    @Test
    @DisplayName("DELIVERED может переходить только в READ")
    void deliveredTransitions() {
        assertTrue(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.READ));
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.SENT), "Даунгрейд статуса запрещен");
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.DELIVERED));
    }

    @Test
    @DisplayName("READ — терминальное состояние, из него нет выходов")
    void readIsTerminalState() {
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.SENT));
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.DELIVERED));
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.READ));
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    @DisplayName("Переход в null всегда возвращает false для любого статуса")
    void transitionToNullReturnsFalse(MessageStatus status) {
        assertFalse(status.canTransitionTo(null));
    }
}