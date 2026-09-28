package com.chat;

import com.chat.model.WsEventType;
import io.vertx.core.AbstractVerticle;
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
    private ChatService chatService;
    private HttpServer server;

    @Override
    public void start() {
        repository = new ChatRepository(vertx);
        connectionManager = new ConnectionManager();
        chatService = new ChatService(repository, connectionManager);

        connectionManager.startHeartbeat(vertx, 30_000);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.post("/api/login").handler(this::handleLogin);
        router.get("/api/users").handler(this::handleGetUsers);
        router.get("/api/messages").handler(this::handleGetMessages);

        // WebSocket Routing
        router.route("/ws").handler(ctx -> {
            String username = ctx.request().getParam("username");
            if (username == null || username.isBlank()) {
                ctx.response().setStatusCode(400).end("username query param required");
                return;
            }
            ctx.request().toWebSocket()
                    .onSuccess(ws -> handleWebSocket(ws, username))
                    .onFailure(err -> ctx.response().setStatusCode(500).end("WebSocket upgrade failed"));
        });

        router.route("/*").handler(StaticHandler.create("webroot"));

        int port = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
        server = vertx.createHttpServer();
        server.requestHandler(router)
                .listen(port)
                .onSuccess(s -> log.info("Server started on port {}", port))
                .onFailure(err -> vertx.close());
    }

    @Override
    public void stop() {
        if (connectionManager != null) connectionManager.stopHeartbeat(vertx);
        if (server != null) server.close();
        if (repository != null) repository.close();
    }

    private void handleLogin(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        if (body == null || !body.containsKey("username")) {
            ctx.response().setStatusCode(400).end("JSON with username required");
            return;
        }

        chatService.authenticate(body.getString("username"))
                .onSuccess(user -> ctx.response().putHeader("Content-Type", "application/json").end(user.toJson().encode()))
                .onFailure(err -> ctx.response().setStatusCode(400).end(err.getMessage()));
    }

    private void handleGetUsers(RoutingContext ctx) {
        String username = ctx.request().getParam("username");
        repository.findUserByUsername(username)
                .compose(currentUser -> repository.findAllUsersExcept(currentUser.id()))
                .map(users -> {
                    JsonArray array = new JsonArray();
                    users.forEach(u -> array.add(u.toJsonWithOnline(connectionManager.isOnline(u.username()))));
                    return array;
                })
                .onSuccess(array -> ctx.response().putHeader("Content-Type", "application/json").end(array.encode()))
                .onFailure(err -> ctx.response().setStatusCode(500).end(err.getMessage()));
    }

    private void handleGetMessages(RoutingContext ctx) {
        String username = ctx.request().getParam("username");
        String peer = ctx.request().getParam("peer");

        repository.findUserByUsername(username)
                .compose(u1 -> repository.findUserByUsername(peer).map(u2 -> new Long[]{u1.id(), u2.id()}))
                .compose(ids -> repository.getConversation(ids[0], ids[1]))
                .map(messages -> {
                    JsonArray array = new JsonArray();
                    messages.forEach(m -> array.add(m.toJson()));
                    return array;
                })
                .onSuccess(array -> ctx.response().putHeader("Content-Type", "application/json").end(array.encode()))
                .onFailure(err -> ctx.response().setStatusCode(500).end(err.getMessage()));
    }

    private void handleWebSocket(ServerWebSocket ws, String username) {
        connectionManager.register(username, ws);
        chatService.broadcastStatus(username, WsEventType.USER_ONLINE);

        ws.textMessageHandler(raw -> {
            try {
                JsonObject json = new JsonObject(raw);
                WsEventType type = WsEventType.fromString(json.getString("type"));

                switch (type) {
                    case SEND_MESSAGE -> chatService.sendMessage(username, json.getString("recipientUsername"), json.getString("content"), ws);
                    case MARK_READ -> chatService.markAsRead(username, json.getString("peerUsername"), ws);
                    default -> chatService.sendError(ws, "Unsupported event: " + type);
                }
            } catch (Exception e) {
                chatService.sendError(ws, "Malformed message: " + e.getMessage());
            }
        });

        ws.closeHandler(v -> {
            if (connectionManager.unregister(username, ws) && !connectionManager.isOnline(username)) {
                chatService.broadcastStatus(username, WsEventType.USER_OFFLINE);
            }
        });

        chatService.sendEvent(ws, WsEventType.CONNECTED, new JsonObject().put("username", username));
    }
}