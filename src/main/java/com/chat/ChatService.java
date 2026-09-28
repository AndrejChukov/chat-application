package com.chat;

import com.chat.model.Message;
import com.chat.model.MessageStatus;
import com.chat.model.User;
import com.chat.model.WsEvent;
import com.chat.model.WsEventType;
import io.vertx.core.Future;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 4096;
    private static final int MAX_USERNAME_LENGTH = 32;

    private final ChatRepository repository;
    private final ConnectionManager connectionManager;

    public ChatService(ChatRepository repository, ConnectionManager connectionManager) {
        this.repository = repository;
        this.connectionManager = connectionManager;
    }

    public void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Username exceeds maximum length of " + MAX_USERNAME_LENGTH);
        }
        if (!username.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("Username contains invalid characters");
        }
    }

    public void validateMessageContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Message content cannot be empty");
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message exceeds maximum length of " + MAX_MESSAGE_LENGTH);
        }
    }

    public Future<User> authenticate(String username) {
        try {
            validateUsername(username);
            return repository.findOrCreateUser(username);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Отправка сообщения с гарантией статуса DELIVERED
     */
    public Future<Void> sendMessage(String senderUsername, String recipientUsername, String content, ServerWebSocket senderWs) {
        try {
            validateUsername(recipientUsername);
            validateMessageContent(content);
        } catch (IllegalArgumentException e) {
            sendError(senderWs, e.getMessage());
            return Future.failedFuture(e);
        }

        return repository.findUserByUsername(senderUsername)
                .compose(sender -> repository.findUserByUsername(recipientUsername)
                        .compose(recipient -> repository.saveMessage(sender.id(), recipient.id(), content.trim())
                                .map(message -> new Object[]{sender, recipient, message})))
                .compose(result -> {
                    Message message = (Message) result[2];

                    // 1. Уведомляем отправителя
                    JsonObject messagePayload = new JsonObject().put("message", message.toJson());
                    sendEvent(senderWs, WsEventType.MESSAGE_SENT, messagePayload);

                    ServerWebSocket recipientWs = connectionManager.get(recipientUsername);

                    // 2. Если получатель в сети — пишем в сокет
                    if (recipientWs != null && !recipientWs.isClosed()) {
                        String rawEvent = WsEvent.of(WsEventType.NEW_MESSAGE.getValue(), messagePayload).toJson().encode();

                        return recipientWs.writeTextMessage(rawEvent)
                                .compose(v -> repository.updateMessageStatus(message.id(), MessageStatus.DELIVERED))
                                .onSuccess(v -> {
                                    // ВОТ ЗДЕСЬ БЫЛА ОШИБКА (создаем JsonObject):
                                    JsonObject statusPayload = new JsonObject()
                                            .put("messageId", message.id())
                                            .put("status", MessageStatus.DELIVERED.name());

                                    sendEvent(senderWs, WsEventType.STATUS_UPDATE, statusPayload);
                                    sendEvent(recipientWs, WsEventType.STATUS_UPDATE, statusPayload);
                                })
                                .onFailure(err -> log.warn("Failed to write to WS for {}. Message remains SENT.", recipientUsername, err))
                                .mapEmpty();
                    }

                    return Future.succeededFuture();
                })
                .onFailure(err -> {
                    log.error("Failed to process message from {} to {}", senderUsername, recipientUsername, err);
                    sendError(senderWs, err.getMessage());
                })
                .mapEmpty();
    }

    public Future<Void> markAsRead(String readerUsername, String peerUsername, ServerWebSocket readerWs) {
        return repository.findUserByUsername(readerUsername)
                .compose(reader -> repository.findUserByUsername(peerUsername)
                        .compose(peer -> repository.markConversationRead(reader.id(), peer.id())
                                .map(messageIds -> new Object[]{peer, messageIds})))
                .onSuccess(result -> {
                    User peer = (User) result[0];
                    @SuppressWarnings("unchecked")
                    List<Long> messageIds = (List<Long>) result[1];

                    ServerWebSocket peerWs = connectionManager.get(peer.username());
                    for (Long messageId : messageIds) {
                        JsonObject statusPayload = new JsonObject()
                                .put("messageId", messageId)
                                .put("status", MessageStatus.READ.name());
                        sendEvent(readerWs, WsEventType.STATUS_UPDATE, statusPayload);
                        if (peerWs != null && !peerWs.isClosed()) {
                            sendEvent(peerWs, WsEventType.STATUS_UPDATE, statusPayload);
                        }
                    }
                })
                .onFailure(err -> sendError(readerWs, err.getMessage()))
                .mapEmpty();
    }

    public void broadcastStatus(String username, WsEventType type) {
        JsonObject payload = new JsonObject().put("username", username);
        WsEvent event = WsEvent.of(type.getValue(), payload);

        for (String onlineUser : connectionManager.getAllUsernames()) {
            if (onlineUser.equals(username)) continue;
            ServerWebSocket ws = connectionManager.get(onlineUser);
            if (ws != null && !ws.isClosed()) {
                ws.writeTextMessage(event.toJson().encode());
            }
        }
    }

    public void sendEvent(ServerWebSocket ws, WsEventType type, JsonObject payload) {
        if (ws != null && !ws.isClosed()) {
            ws.writeTextMessage(WsEvent.of(type.getValue(), payload).toJson().encode());
        }
    }

    public void sendError(ServerWebSocket ws, String message) {
        sendEvent(ws, WsEventType.ERROR, new JsonObject().put("message", message));
    }
}