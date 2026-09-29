package com.chat;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    public record UserSession(ServerWebSocket socket, AtomicBoolean responsive) {}

    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    private Long heartbeatTimerId;

    public void register(String username, ServerWebSocket ws) {
        UserSession newSession = new UserSession(ws, new AtomicBoolean(true));
        UserSession oldSession = sessions.put(username, newSession);

        if (oldSession != null && oldSession.socket() != ws && !oldSession.socket().isClosed()) {
            log.info("Closing previous session for user: {}", username);
            oldSession.socket().close((short) 1000, "Replaced by new connection");
        }

        ws.pongHandler(buffer -> {
            newSession.responsive().set(true);
            log.trace("Received pong from user: {}", username);
        });

        log.debug("Registered WebSocket session for user: {}", username);
    }

    public boolean unregister(String username, ServerWebSocket ws) {
        UserSession session = sessions.get(username);
        if (session != null && session.socket() == ws) {
            sessions.remove(username, session);
            log.debug("Unregistered WebSocket session for user: {}", username);
            return true;
        }
        return false;
    }

    public ServerWebSocket get(String username) {
        UserSession session = sessions.get(username);
        return session != null ? session.socket() : null;
    }

    public boolean isOnline(String username) {
        UserSession session = sessions.get(username);
        return session != null && !session.socket().isClosed();
    }

    public Iterable<String> getAllUsernames() {
        return sessions.keySet();
    }

    /**
     * Запуск фонового мониторинга зомби-сессий
     * @param vertx экземпляр Vert.x для таймера
     * @param pingIntervalMs интервал отправки ping
     */
    public void startHeartbeat(Vertx vertx, long pingIntervalMs) {
        this.heartbeatTimerId = vertx.setPeriodic(pingIntervalMs, timerId -> {
            for (Map.Entry<String, UserSession> entry : sessions.entrySet()) {
                String username = entry.getKey();
                UserSession session = entry.getValue();
                ServerWebSocket ws = session.socket();

                if (ws.isClosed()) {
                    unregister(username, ws);
                    continue;
                }

                if (!session.responsive().getAndSet(false)) {
                    log.warn("Heartbeat timeout for user: {}. Closing zombie socket.", username);
                    ws.close((short) 1001, "Heartbeat timeout");
                } else {
                    ws.writePing(Buffer.buffer("ping"));
                }
            }
        });
        log.info("Heartbeat mechanism started with interval {} ms", pingIntervalMs);
    }

    public void stopHeartbeat(Vertx vertx) {
        if (heartbeatTimerId != null) {
            vertx.cancelTimer(heartbeatTimerId);
        }
    }
}