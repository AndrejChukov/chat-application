# Chat Application

Асинхронное чат-приложение в стиле ICQ на Java 21, Eclipse Vert.x 4 и PostgreSQL.

## Стек

- Java 21, Gradle (Kotlin DSL)
- Eclipse Vert.x (HttpServer, WebSocket, Router) — без Spring
- PostgreSQL через `vertx-pg-client` (реактивный неблокирующий доступ)
- Jackson / Vert.x JsonObject
- Web UI: HTML5, Tailwind CSS (CDN), чистый JavaScript + WebSocket
- Docker multi-stage build + docker-compose

## Функциональность

- Вход по логину (автосоздание пользователя в БД)
- Список пользователей со статусом online/offline
- 1-to-1 сообщения в реальном времени через WebSocket
- Статусы сообщений: `SENT` → `DELIVERED` → `READ`
- История сообщений в PostgreSQL
- Две вкладки браузера под разными пользователями — полноценный диалог

## Быстрый старт (Docker Compose)

```bash
docker compose up --build
```

Откройте [http://localhost:8080](http://localhost:8080).

Для теста откройте **две вкладки** браузера, войдите под разными логинами (например `alice` и `bob`) и начните переписку.

## Локальный запуск (Gradle)

### 1. PostgreSQL

```bash
docker run -d --name chat-pg \
  -e POSTGRES_DB=chatdb \
  -e POSTGRES_USER=chatuser \
  -e POSTGRES_PASSWORD=chatpass \
  -p 5432:5432 \
  postgres:16-alpine
```

### 2. Инициализация схемы

```bash
PGPASSWORD=chatpass psql -h localhost -U chatuser -d chatdb -f schema.sql
```

### 3. Запуск приложения

```bash
./gradlew run
```

Переменные окружения (значения по умолчанию):

| Переменная   | По умолчанию |
|--------------|--------------|
| `DB_HOST`    | `localhost`  |
| `DB_PORT`    | `5432`       |
| `DB_NAME`    | `chatdb`     |
| `DB_USER`    | `chatuser`   |
| `DB_PASSWORD`| `chatpass`   |
| `HTTP_PORT`  | `8080`       |

## Сборка JAR

```bash
./gradlew shadowJar
java -jar build/libs/chat-application.jar
```

## Архитектура

```
com.chat/
├── Main.java              — точка входа, deploy ChatVerticle
├── ChatVerticle.java      — HTTP/WS роуты, static UI
├── ConnectionManager.java — активные WebSocket-сессии
├── ChatRepository.java    — реактивные SQL-запросы (PgPool)
└── model/
    ├── User.java
    ├── Message.java
    ├── MessageStatus.java
    └── WsEvent.java
```

## REST API

### POST /api/login

```json
{ "username": "alice" }
```

Ответ:

```json
{ "id": 1, "username": "alice" }
```

### GET /api/users?username=alice

Список всех пользователей кроме текущего, с флагом `online`:

```json
[{ "id": 2, "username": "bob", "online": true }]
```

### GET /api/messages?username=alice&peer=bob

История диалога:

```json
[{
  "id": 1,
  "senderId": 1,
  "senderUsername": "alice",
  "recipientId": 2,
  "recipientUsername": "bob",
  "content": "Привет!",
  "status": "READ",
  "createdAt": "2026-09-27T12:00:00Z"
}]
```

## WebSocket протокол

Подключение: `ws://localhost:8080/ws?username=alice`

### Клиент → сервер

**send_message**

```json
{
  "type": "send_message",
  "recipientUsername": "bob",
  "content": "Привет!"
}
```

**mark_read**

```json
{
  "type": "mark_read",
  "peerUsername": "bob"
}
```

### Сервер → клиент

| type           | Описание                              |
|----------------|---------------------------------------|
| `connected`    | Подтверждение подключения             |
| `message_sent` | Сообщение сохранено (отправителю)     |
| `new_message`  | Новое входящее сообщение              |
| `status_update`| `{ messageId, status }`               |
| `user_online`  | `{ username }`                        |
| `user_offline` | `{ username }`                        |
| `error`        | `{ message }`                         |

## Статусы сообщений (UI)

| Статус      | Отображение |
|-------------|-------------|
| SENT        | ✓           |
| DELIVERED   | ✓✓ (серые)  |
| READ        | ✓✓ (синие)  |

## Остановка Docker

```bash
docker compose down
docker compose down -v   # с удалением volume PostgreSQL
```
