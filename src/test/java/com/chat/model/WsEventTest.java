package com.chat.model;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Unit-тесты протокола и DTO WebSocket событий")
class WsEventTest {

    @Test
    @DisplayName("Корректный парсинг enum WsEventType из строки независимо от регистра")
    void parseEventTypeSuccess() {
        assertEquals(WsEventType.SEND_MESSAGE, WsEventType.fromString("send_message"));
        assertEquals(WsEventType.SEND_MESSAGE, WsEventType.fromString("SEND_MESSAGE"));
        assertEquals(WsEventType.MARK_READ, WsEventType.fromString("mark_read"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown_event", "invalid", "", "123"})
    @DisplayName("Неизвестный тип события выбрасывает IllegalArgumentException")
    void parseUnknownEventTypeThrowsException(String unknownType) {
        assertThrows(IllegalArgumentException.class, () -> WsEventType.fromString(unknownType));
    }

    @Test
    @DisplayName("Сериализация WsEvent в JsonObject формирует правильный контракт")
    void serializeWsEventToJson() {
        JsonObject payload = new JsonObject()
                .put("messageId", 42L)
                .put("status", "DELIVERED");

        WsEvent event = WsEvent.of(WsEventType.STATUS_UPDATE.getValue(), payload);
        JsonObject json = event.toJson();

        assertEquals("status_update", json.getString("type"));
        assertEquals(42L, json.getLong("messageId"));
        assertEquals("DELIVERED", json.getString("status"));
    }

    @Test
    @DisplayName("Сериализация события без payload содержит только поле type")
    void serializeWsEventWithoutPayload() {
        WsEvent event = WsEvent.of("ping");
        JsonObject json = event.toJson();

        assertEquals(1, json.size());
        assertEquals("ping", json.getString("type"));
    }
}