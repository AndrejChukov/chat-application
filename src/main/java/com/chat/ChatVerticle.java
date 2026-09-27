package com.chat;

import com.chat.model.Message;
import com.chat.model.MessageStatus;
import com.chat.model.User;
import com.chat.model.WsEvent;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ChatVerticle.class);

    private ChatRepository repository;
    private ConnectionManager connectionManager;
    private HttpServer server;

    @Override
    public void start() {
        repository = new ChatRepository(vertx);
        connectionManager = new ConnectionManager();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/api/login").handler(this::handleLogin);
        router.get("/api/users").handler(this::handleGetUsers);
        router.get("/api/messages").handler(this::handleGetMessages);

        router.route("/ws").handler(ctx -> {
            if (ctx.request().getHeader("Upgrade") == null) {
                ctx.response().setStatusCode(400).end("Expected WebSocket");
                return;
            }
            ctx.next();
        });

        router.route("/ws").handler(ctx -> {
            String username = ctx.request().getParam("username");
            if (username == null || username.isBlank()) {
                ctx.response().setStatusCode(400).end("username query param required");
                return;
            }
            ctx.request().toWebSocket()
                    .onSuccess(ws -> handleWebSocket(ws, username))
                    .onFailure(err -> {
                        log.error("WebSocket upgrade failed", err);
                        ctx.response().setStatusCode(500).end("WebSocket upgrade failed");
                    });
        });

        router.route("/*").handler(StaticHandler.create("webroot"));

        int port = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));

        server = vertx.createHttpServer();
        server.requestHandler(router)
                .listen(port)
                .onSuccess(s -> log.info("Chat server started on port {}", port))
                .onFailure(err -> {
                    log.error("Failed to start server", err);
                    vertx.close();
                });
    }

    @Override
    public void stop() {
        if (server != null) {
            server.close();
        }
        if (repository != null) {
            repository.close();
        }
    }

    private void handleLogin(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ctx.response().setStatusCode(400).end("JSON body required");
            return;
        }

        String username = body.getString("username");
        repository.findOrCreateUser(username)
                .onSuccess(user -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(user.toJson().encode()))
                .onFailure(err -> {
                    log.error("Login failed", err);
                    ctx.response().setStatusCode(500).end(err.getMessage());
                });
    }

    private void handleGetUsers(RoutingContext ctx) {
        String username = ctx.request().getParam("username");
        if (username == null || username.isBlank()) {
            ctx.response().setStatusCode(400).end("username param required");
            return;
        }

        repository.findUserByUsername(username)
                .compose(currentUser -> repository.findAllUsersExcept(currentUser.id()))
                .map(users -> {
                    JsonArray array = new JsonArray();
                    for (User user : users) {
                        array.add(user.toJsonWithOnline(connectionManager.isOnline(user.username())));
                    }
                    return array;
                })
                .onSuccess(array -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(array.encode()))
                .onFailure(err -> {
                    log.error("Failed to get users", err);
                    ctx.response().setStatusCode(500).end(err.getMessage());
                });
    }

    private void handleGetMessages(RoutingContext ctx) {
        String username = ctx.request().getParam("username");
        String peer = ctx.request().getParam("peer");

        if (username == null || peer == null) {
            ctx.response().setStatusCode(400).end("username and peer params required");
            return;
        }

        Future<User> currentUserFuture = repository.findUserByUsername(username);
        Future<User> peerUserFuture = repository.findUserByUsername(peer);

        Future.all(currentUserFuture, peerUserFuture)
                .compose(cf -> repository.getConversation(
                        currentUserFuture.result().id(),
                        peerUserFuture.result().id()))
                .map(messages -> {
                    JsonArray array = new JsonArray();
                    for (Message message : messages) {
                        array.add(message.toJson());
                    }
                    return array;
                })
                .onSuccess(array -> ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(array.encode()))
                .onFailure(err -> {
                    log.error("Failed to get messages", err);
                    ctx.response().setStatusCode(500).end(err.getMessage());
                });
    }

    private void handleWebSocket(ServerWebSocket ws, String username) {
        connectionManager.register(username, ws);
        broadcastUserStatus(username, "user_online");

        ws.textMessageHandler(raw -> handleWsMessage(ws, username, raw));

        ws.closeHandler(v -> {
            connectionManager.unregister(username);
            broadcastUserStatus(username, "user_offline");
            log.info("WebSocket closed for user: {}", username);
        });

        ws.exceptionHandler(err -> log.error("WebSocket error for user: {}", username, err));

        sendWs(ws, WsEvent.of("connected", new JsonObject().put("username", username)));
    }

    private void handleWsMessage(ServerWebSocket ws, String senderUsername, String raw) {
        JsonObject json;
        try {
            json = new JsonObject(raw);
        } catch (Exception e) {
            sendWs(ws, WsEvent.of("error", new JsonObject().put("message", "Invalid JSON")));
            return;
        }

        String type = json.getString("type");
        if ("send_message".equals(type)) {
            handleSendMessage(ws, senderUsername, json);
        } else if ("mark_read".equals(type)) {
            handleMarkRead(ws, senderUsername, json);
        } else {
            sendWs(ws, WsEvent.of("error", new JsonObject().put("message", "Unknown event type: " + type)));
        }
    }

    private void handleSendMessage(ServerWebSocket ws, String senderUsername, JsonObject json) {
        String recipientUsername = json.getString("recipientUsername");
        String content = json.getString("content");

        repository.findUserByUsername(senderUsername)
                .compose(sender -> repository.findUserByUsername(recipientUsername)
                        .compose(recipient -> repository.saveMessage(sender.id(), recipient.id(), content)
                                .map(message -> new Object[]{sender, recipient, message})))
                .onSuccess(result -> {
                    User sender = (User) result[0];
                    User recipient = (User) result[1];
                    Message message = (Message) result[2];

                    sendWs(ws, WsEvent.of("message_sent", new JsonObject().put("message", message.toJson())));

                    ServerWebSocket recipientWs = connectionManager.get(recipientUsername);
                    if (recipientWs != null && !recipientWs.isClosed()) {
                        sendWs(recipientWs, WsEvent.of("new_message", new JsonObject().put("message", message.toJson())));

                        repository.updateMessageStatus(message.id(), MessageStatus.DELIVERED)
                                .onSuccess(v -> {
                                    JsonObject statusPayload = new JsonObject()
                                            .put("messageId", message.id())
                                            .put("status", MessageStatus.DELIVERED.name());
                                    sendWs(ws, WsEvent.of("status_update", statusPayload));
                                    sendWs(recipientWs, WsEvent.of("status_update", statusPayload));
                                });
                    }
                })
                .onFailure(err -> {
                    log.error("Failed to send message from {} to {}", senderUsername, recipientUsername, err);
                    sendWs(ws, WsEvent.of("error", new JsonObject().put("message", err.getMessage())));
                });
    }

    private void handleMarkRead(ServerWebSocket ws, String readerUsername, JsonObject json) {
        String peerUsername = json.getString("peerUsername");

        repository.findUserByUsername(readerUsername)
                .compose(reader -> repository.findUserByUsername(peerUsername)
                        .compose(peer -> repository.markConversationRead(reader.id(), peer.id())
                                .map(messageIds -> new Object[]{peer, messageIds})))
                .onSuccess(result -> {
                    User peer = (User) result[0];
                    @SuppressWarnings("unchecked")
                    java.util.List<Long> messageIds = (java.util.List<Long>) result[1];

                    ServerWebSocket peerWs = connectionManager.get(peer.username());
                    for (Long messageId : messageIds) {
                        JsonObject statusPayload = new JsonObject()
                                .put("messageId", messageId)
                                .put("status", MessageStatus.READ.name());
                        sendWs(ws, WsEvent.of("status_update", statusPayload));
                        if (peerWs != null && !peerWs.isClosed()) {
                            sendWs(peerWs, WsEvent.of("status_update", statusPayload));
                        }
                    }
                })
                .onFailure(err -> {
                    log.error("Failed to mark read for {}", readerUsername, err);
                    sendWs(ws, WsEvent.of("error", new JsonObject().put("message", err.getMessage())));
                });
    }

    private void broadcastUserStatus(String username, String eventType) {
        JsonObject payload = new JsonObject().put("username", username);
        WsEvent event = WsEvent.of(eventType, payload);

        for (String onlineUser : connectionManager.getAllUsernames()) {
            if (onlineUser.equals(username)) {
                continue;
            }
            ServerWebSocket ws = connectionManager.get(onlineUser);
            if (ws != null && !ws.isClosed()) {
                sendWs(ws, event);
            }
        }
    }

    private void sendWs(ServerWebSocket ws, WsEvent event) {
        ws.writeTextMessage(event.toJson().encode());
    }
}
