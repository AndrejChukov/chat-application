package com.chat;

import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final Map<String, ServerWebSocket> sessions = new ConcurrentHashMap<>();

    public void register(String username, ServerWebSocket ws) {
        ServerWebSocket existing = sessions.put(username, ws);
        if (existing != null && existing != ws && !existing.isClosed()) {
            log.info("Closing previous session for user: {}", username);
            existing.close();
        }
        log.debug("Registered WebSocket session for user: {}", username);
    }

    public boolean unregister(String username, ServerWebSocket ws) {
        boolean removed = sessions.remove(username, ws);
        if (removed) {
            log.debug("Unregistered WebSocket session for user: {}", username);
        }
        return removed;
    }

    public ServerWebSocket get(String username) {
        return sessions.get(username);
    }

    public boolean isOnline(String username) {
        ServerWebSocket ws = sessions.get(username);
        return ws != null && !ws.isClosed();
    }

    public Iterable<String> getAllUsernames() {
        return sessions.keySet();
    }
}
