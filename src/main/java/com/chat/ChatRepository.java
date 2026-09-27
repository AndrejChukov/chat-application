package com.chat;

import com.chat.model.Message;
import com.chat.model.MessageStatus;
import com.chat.model.User;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ChatRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatRepository.class);

    private final PgPool pool;

    public ChatRepository(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(getEnv("DB_HOST", "localhost"))
                .setPort(Integer.parseInt(getEnv("DB_PORT", "5432")))
                .setDatabase(getEnv("DB_NAME", "chatdb"))
                .setUser(getEnv("DB_USER", "chatuser"))
                .setPassword(getEnv("DB_PASSWORD", "chatpass"));

        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);
        this.pool = PgPool.pool(vertx, connectOptions, poolOptions);
    }

    public Future<User> findOrCreateUser(String username) {
        return pool.preparedQuery("SELECT id, username FROM users WHERE username = $1")
                .execute(Tuple.of(username))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        Row row = rows.iterator().next();
                        return Future.succeededFuture(mapUser(row));
                    }
                    return pool.preparedQuery(
                                    "INSERT INTO users (username) VALUES ($1) RETURNING id, username")
                            .execute(Tuple.of(username))
                            .map(insertRows -> mapUser(insertRows.iterator().next()));
                })
                .onFailure(err -> log.error("Failed to find or create user: {}", username, err));
    }

    public Future<User> findUserByUsername(String username) {
        return pool.preparedQuery("SELECT id, username FROM users WHERE username = $1")
                .execute(Tuple.of(username))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.succeededFuture(mapUser(rows.iterator().next()));
                    }
                    return Future.failedFuture("User not found: " + username);
                });
    }

    public Future<List<User>> findAllUsersExcept(long excludeUserId) {
        return pool.preparedQuery("SELECT id, username FROM users WHERE id != $1 ORDER BY username")
                .execute(Tuple.of(excludeUserId))
                .map(rows -> {
                    List<User> users = new ArrayList<>();
                    for (Row row : rows) {
                        users.add(mapUser(row));
                    }
                    return users;
                });
    }

    public Future<Message> saveMessage(long senderId, long recipientId, String content) {
        return pool.preparedQuery(
                        """
                        INSERT INTO messages (sender_id, recipient_id, content, status)
                        VALUES ($1, $2, $3, $4)
                        RETURNING id, sender_id, recipient_id, content, status, created_at
                        """)
                .execute(Tuple.of(senderId, recipientId, content, MessageStatus.SENT.name()))
                .compose(rows -> enrichMessage(mapMessageRow(rows.iterator().next())));
    }

    public Future<List<Message>> getConversation(long userId, long peerId) {
        return pool.preparedQuery(
                        """
                        SELECT m.id, m.sender_id, m.recipient_id, m.content, m.status, m.created_at,
                               su.username AS sender_username, ru.username AS recipient_username
                        FROM messages m
                        JOIN users su ON su.id = m.sender_id
                        JOIN users ru ON ru.id = m.recipient_id
                        WHERE (m.sender_id = $1 AND m.recipient_id = $2)
                           OR (m.sender_id = $2 AND m.recipient_id = $1)
                        ORDER BY m.created_at ASC
                        """)
                .execute(Tuple.of(userId, peerId))
                .map(rows -> {
                    List<Message> messages = new ArrayList<>();
                    for (Row row : rows) {
                        messages.add(mapMessageRowWithUsernames(row));
                    }
                    return messages;
                });
    }

    public Future<Void> updateMessageStatus(long messageId, MessageStatus status) {
        return pool.preparedQuery("UPDATE messages SET status = $1 WHERE id = $2")
                .execute(Tuple.of(status.name(), messageId))
                .mapEmpty();
    }

    public Future<List<Long>> markConversationRead(long recipientId, long senderId) {
        return pool.preparedQuery(
                        """
                        UPDATE messages SET status = $1
                        WHERE recipient_id = $2 AND sender_id = $3 AND status != $1
                        RETURNING id
                        """)
                .execute(Tuple.of(MessageStatus.READ.name(), recipientId, senderId))
                .map(rows -> {
                    List<Long> ids = new ArrayList<>();
                    for (Row row : rows) {
                        ids.add(row.getLong("id"));
                    }
                    return ids;
                });
    }

    public void close() {
        pool.close();
    }

    private Future<Message> enrichMessage(Message message) {
        return Future.all(
                        findUsernameById(message.senderId()),
                        findUsernameById(message.recipientId()))
                .map(cf -> new Message(
                        message.id(),
                        message.senderId(),
                        cf.resultAt(0),
                        message.recipientId(),
                        cf.resultAt(1),
                        message.content(),
                        message.status(),
                        message.createdAt()));
    }

    private Future<String> findUsernameById(long userId) {
        return pool.preparedQuery("SELECT username FROM users WHERE id = $1")
                .execute(Tuple.of(userId))
                .map(rows -> rows.iterator().next().getString("username"));
    }

    private User mapUser(Row row) {
        return new User(row.getLong("id"), row.getString("username"));
    }

    private Message mapMessageRow(Row row) {
        return new Message(
                row.getLong("id"),
                row.getLong("sender_id"),
                null,
                row.getLong("recipient_id"),
                null,
                row.getString("content"),
                MessageStatus.fromString(row.getString("status")),
                row.getOffsetDateTime("created_at").toInstant());
    }

    private Message mapMessageRowWithUsernames(Row row) {
        return new Message(
                row.getLong("id"),
                row.getLong("sender_id"),
                row.getString("sender_username"),
                row.getLong("recipient_id"),
                row.getString("recipient_username"),
                row.getString("content"),
                MessageStatus.fromString(row.getString("status")),
                row.getOffsetDateTime("created_at").toInstant());
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
