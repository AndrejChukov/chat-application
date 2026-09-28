package com.chat;

import io.vertx.core.http.ServerWebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты менеджера соединений ConnectionManager")
class ConnectionManagerTest {

    private ConnectionManager connectionManager;

    @Mock
    private ServerWebSocket socket1;

    @Mock
    private ServerWebSocket socket2;

    @BeforeEach
    void setUp() {
        connectionManager = new ConnectionManager();
    }

    @Test
    @DisplayName("Регистрация пользователя делает его онлайн")
    void registerUserMakesOnline() {
        when(socket1.isClosed()).thenReturn(false);

        connectionManager.register("alice", socket1);

        assertTrue(connectionManager.isOnline("alice"));
        assertEquals(socket1, connectionManager.get("alice"));
    }

    @Test
    @DisplayName("Регистрация новой сессии закрывает старую сессию того же пользователя")
    void reRegistrationClosesOldSocket() {
        when(socket1.isClosed()).thenReturn(false);
        when(socket2.isClosed()).thenReturn(false);

        connectionManager.register("alice", socket1);
        connectionManager.register("alice", socket2);

        // Проверяем, что у старого сокета был вызван close()
        verify(socket1).close(eq((short) 1000), anyString());
        assertEquals(socket2, connectionManager.get("alice"));
    }

    @Test
    @DisplayName("Защита от Race Condition: закрытие старого сокета не удаляет новый активный сокет")
    void unregisterOldSocketDoesNotRemoveNewSocket() {
        when(socket2.isClosed()).thenReturn(false);

        // Пользователь открыл вторую вкладку (socket2 заменил socket1)
        connectionManager.register("alice", socket1);
        connectionManager.register("alice", socket2);

        // Старый сокет socket1 пытается дерегистрироваться после своего закрытия
        boolean removed = connectionManager.unregister("alice", socket1);

        assertFalse(removed, "Попытка удалить старый сокет должна вернуть false");
        assertTrue(connectionManager.isOnline("alice"), "Пользователь должен оставаться онлайн");
        assertEquals(socket2, connectionManager.get("alice"), "Активным сокетом должен оставаться socket2");
    }

    @Test
    @DisplayName("Корректная дерегистрация активного сокета удаляет сессию")
    void unregisterCurrentSocketRemovesSession() {
        connectionManager.register("alice", socket1);

        boolean removed = connectionManager.unregister("alice", socket1);

        assertTrue(removed);
        assertFalse(connectionManager.isOnline("alice"));
        assertNull(connectionManager.get("alice"));
    }

    @Test
    @DisplayName("Пользователь офлайн, если сокет закрыт")
    void userIsOfflineIfSocketIsClosed() {
        when(socket1.isClosed()).thenReturn(true);

        connectionManager.register("alice", socket1);

        assertFalse(connectionManager.isOnline("alice"));
    }
}