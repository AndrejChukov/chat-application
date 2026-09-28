package com.chat;

import com.chat.model.MessageStatus;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test_chatdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private static ChatRepository repository;
    private static ChatService chatService;
    private static ConnectionManager connectionManager;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext testContext) {
        // 1. Пробрасываем параметры Testcontainers в приложение
        System.setProperty("DB_HOST", postgres.getHost());
        System.setProperty("DB_PORT", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("DB_NAME", postgres.getDatabaseName());
        System.setProperty("DB_USER", postgres.getUsername());
        System.setProperty("DB_PASSWORD", postgres.getPassword());
        System.setProperty("HTTP_PORT", "8888");

        // 2. Накатываем миграции Flyway на контейнер
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();

        // 3. Инициализируем слои
        repository = new ChatRepository(vertx);
        connectionManager = new ConnectionManager();
        chatService = new ChatService(repository, connectionManager);

        // 4. Разворачиваем Verticle
        vertx.deployVerticle(new ChatVerticle())
                .onComplete(testContext.succeedingThenComplete());
    }

    @AfterAll
    static void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @Order(1)
    void testUserAuthentication(VertxTestContext testContext) {
        chatService.authenticate("alice")
                .compose(alice -> {
                    assertEquals("alice", alice.username());
                    assertTrue(alice.id() > 0);
                    return chatService.authenticate("bob");
                })
                .onComplete(testContext.succeeding(bob -> {
                    assertEquals("bob", bob.username());
                    testContext.completeNow();
                }));
    }

    @Test
    @Order(2)
    void testStatusStateMachineTransitions(VertxTestContext testContext) {
        // Создаем сообщение от Alice к Bob
        repository.findUserByUsername("alice")
                .compose(alice -> repository.findUserByUsername("bob")
                        .compose(bob -> repository.saveMessage(alice, bob, "Hello Bob!")))
                .compose(message -> {
                    assertEquals(MessageStatus.SENT, message.status());

                    // ПРОВЕРКА 1: Допустимый переход: SENT -> DELIVERED
                    return repository.transitionMessageStatus(message.id(), MessageStatus.SENT, MessageStatus.DELIVERED)
                            .compose(updated -> {
                                assertTrue(updated, "Status should transition from SENT to DELIVERED");

                                // ПРОВЕРКА 2: Недопустимый откат: DELIVERED -> SENT (должен вернуть false!)
                                return repository.transitionMessageStatus(message.id(), MessageStatus.DELIVERED, MessageStatus.SENT);
                            })
                            .compose(illegalDowngrade -> {
                                assertFalse(illegalDowngrade, "Downgrade from DELIVERED to SENT must fail");

                                // ПРОВЕРКА 3: Допустимый переход: DELIVERED -> READ
                                return repository.transitionMessageStatus(message.id(), MessageStatus.DELIVERED, MessageStatus.READ);
                            })
                            .compose(readUpdated -> {
                                assertTrue(readUpdated, "Status should transition from DELIVERED to READ");

                                // ПРОВЕРКА 4: Недопустимый переход из терминального состояния: READ -> DELIVERED
                                return repository.transitionMessageStatus(message.id(), MessageStatus.READ, MessageStatus.DELIVERED);
                            });
                })
                .onComplete(testContext.succeeding(forbiddenTransition -> {
                    assertFalse(forbiddenTransition, "Terminal status READ must not be overwritten");
                    testContext.completeNow();
                }));
    }

    @Test
    @Order(3)
    void testMessagePagination(VertxTestContext testContext) {
        repository.findUserByUsername("alice")
                .compose(alice -> repository.findUserByUsername("bob")
                        .compose(bob -> {
                            // Отправляем несколько сообщений
                            return repository.saveMessage(alice, bob, "Msg 1")
                                    .compose(m -> repository.saveMessage(alice, bob, "Msg 2"))
                                    .compose(m -> repository.saveMessage(alice, bob, "Msg 3"))
                                    .compose(m -> chatService.getConversation("alice", "bob", 2, 0)); // Limit 2, Offset 0
                        }))
                .onComplete(testContext.succeeding(messages -> {
                    // Пагинация должна вернуть последние 2 сообщения
                    assertEquals(2, messages.size());
                    assertEquals("Msg 2", messages.get(0).content());
                    assertEquals("Msg 3", messages.get(1).content());
                    testContext.completeNow();
                }));
    }

    @Test
    @Order(4)
    void testHttpRestLoginEndpoint(Vertx vertx, VertxTestContext testContext) {
        WebClient client = WebClient.create(vertx);

        client.post(8888, "localhost", "/api/login")
                .sendJsonObject(new JsonObject().put("username", "charlie"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals("charlie", body.getString("username"));
                    testContext.completeNow();
                }));
    }
}