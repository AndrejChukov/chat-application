package com.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Unit-тесты валидации входящих данных ChatService")
class ChatServiceValidationTest {

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(null, null);
    }

    @Test
    @DisplayName("Корректный username проходит валидацию")
    void validUsernamePasses() {
        assertDoesNotThrow(() -> chatService.validateUsername("alice"));
        assertDoesNotThrow(() -> chatService.validateUsername("bob_123"));
        assertDoesNotThrow(() -> chatService.validateUsername("user-name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "al ice", "user@name", "user!", "юзер"})
    @DisplayName("Некорректный username выбрасывает исключение")
    void invalidUsernameThrowsException(String invalidUsername) {
        assertThrows(IllegalArgumentException.class, () -> chatService.validateUsername(invalidUsername));
    }

    @Test
    @DisplayName("Username с длиной null выбрасывает исключение")
    void nullUsernameThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> chatService.validateUsername(null));
    }

    @Test
    @DisplayName("Слишком длинный username (>32 символов) выбрасывает исключение")
    void tooLongUsernameThrowsException() {
        String longUsername = "a".repeat(33);
        assertThrows(IllegalArgumentException.class, () -> chatService.validateUsername(longUsername));
    }

    @Test
    @DisplayName("Корректный текст сообщения проходит валидацию")
    void validMessageContentPasses() {
        assertDoesNotThrow(() -> chatService.validateMessageContent("Hello world!"));
        assertDoesNotThrow(() -> chatService.validateMessageContent("a".repeat(4096)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Пустое сообщение выбрасывает исключение")
    void blankMessageContentThrowsException(String blankContent) {
        assertThrows(IllegalArgumentException.class, () -> chatService.validateMessageContent(blankContent));
    }

    @Test
    @DisplayName("Сообщение длиннее 4096 символов выбрасывает исключение")
    void tooLongMessageContentThrowsException() {
        String longContent = "a".repeat(4097);
        assertThrows(IllegalArgumentException.class, () -> chatService.validateMessageContent(longContent));
    }
}