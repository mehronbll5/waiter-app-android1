package com.waiterapp.data.model

// --- Регистрация официанта (POST api/v1/worker/create) ---
// ВАЖНО: судя по SecurityConfig бэкенда, "/api/v1/worker/**" целиком доступен
// только роли ADMIN. Обычный официант создать нового работника не может -
// этот метод остаётся в API-сервисе для полноты, но в официантском флоу
// (LoginScreen и т.п.) не должен вызываться.
data class CreateWaiterRequest(
    val name: String,
    val password: String,
    // Роль работника: ADMIN, WAITER, KITCHEN, CASH (см. Role.java на бэкенде).
    val role: String = "WAITER"
)

data class CreateWaiterResponse(
    val name: String,
    val staffId: Long
)

// --- Вход (POST api/v1/worker/auth/logIn) ---
data class LoginRequest(
    val staffId: Long,
    val password: String
)

// Бэкенд возвращает ДВА токена:
// - token       - refresh-токен (WorkerServiceImpl.lonIn), живёт 24ч
//                 (jwt.refresh-expiration в application.yml), используется
//                 ТОЛЬКО для авто-входа через api/v1/worker/auth/auto.
// - accessToken - JWT для заголовка "Authorization: Bearer ..." на каждый
//                 запрос, срок определяется бэкендом (jwt.access-expiration).
data class LoginResponse(
    val token: String,
    val accessToken: String
)

// --- Авто-вход по refresh-токену (POST api/v1/worker/auth/auto) ---
// Единственный эндпоинт, который реально возвращает имя официанта -
// используем его сразу после логина, чтобы подставить имя без ручного ввода
// (см. AuthRepository.fetchAndSaveNickname).
data class AuthAutoRequest(
    val token: String
)

data class AuthAutoResponse(
    val waiterName: String,
    val staffId: Long,
    val accessToken: String
)

// --- Смена пароля (PATCH api/v1/worker/update_password, ADMIN) ---
data class UpdatePasswordRequest(
    val staffId: Long,
    val password: String
)
