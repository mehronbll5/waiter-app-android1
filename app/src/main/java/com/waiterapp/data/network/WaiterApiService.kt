package com.waiterapp.data.network

import com.waiterapp.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Сверено напрямую со Swagger UI (/v3/api-docs) ЭТОГО бэкенда - у всех
 * методов ниже путь/метод/тело запроса взяты из реальной OpenAPI-схемы,
 * а не из предположений по исходникам. Три расхождения, найденные при
 * сверке (и теперь исправленные здесь):
 *  1) GET "api/v1/menus" (плоский список всего меню) в схеме ВООБЩЕ
 *     ОТСУТСТВУЕТ - его не существует, это всегда был бы 404. Разработчик
 *     меню-контроллера явно не сделал такой эндпоинт: есть только поиск
 *     одного блюда по имени ("api/v1/menus/{name}") и список блюд ОДНОЙ
 *     категории ("api/v1/menus/all/menu/category/{categoryId}"). Поэтому
 *     getAllMenuItems() убран отсюда - собирать "все блюда" теперь нужно
 *     на уровне репозитория: сначала список категорий, потом эти блюда по
 *     каждой категории (см. getDishesByCategory() ниже и
 *     MenuRepository.getAllMenuItems()).
 *  2) getAllCategories() ходил на "api/v1/categories" (это путь ТОЛЬКО для
 *     POST/создания) - в схеме список категорий отдаётся на
 *     "api/v1/categories/all". Исправлено.
 *  3) getMyOrders() ходил на "api/v1/orders/my" - такого пути в схеме нет.
 *     Правильный путь для списка ВСЕХ заказов текущего официанта (тот же
 *     формат ответа orders:[{orderId,orderNumber,status}]) -
 *     "api/v1/orders/my/all". Исправлено.
 *
 * ВАЖНО про createOrder() и getAllTables(): действие вынесено в отдельный
 * сегмент пути ("/create/" с завершающим слэшем и "/all" соответственно) -
 * это НЕ корневые GET/POST на "/api/v1/orders" и "/api/v1/tables", и в схеме
 * подтверждено именно так (см. комментарии у каждого метода ниже).
 *
 * Про роли/403: сама Swagger-схема ролей не показывает (это в
 * SecurityConfig на сервере, не в OpenAPI), так что комментарий из
 * предыдущей версии про "только ADMIN на /menus и /tables" тут убран как
 * непроверенный по схеме - ориентируйтесь на реальные ответы сервера
 * (403 = не хватает прав, см. SafeApiCall.kt).
 */
interface WaiterApiService {

    // ===== Авторизация: WorkerController, "/api/v1/worker" (пути не менялись) =====

    // Вход по staffId + паролю. permitAll в SecurityConfig - токен не нужен.
    @POST("api/v1/worker/auth/logIn")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // Авто-вход по refresh-токену (полученному при логине как "token").
    // Единственный эндпоинт, который отдаёт имя официанта.
    @POST("api/v1/worker/auth/auto")
    suspend fun authAuto(@Body request: AuthAutoRequest): Response<AuthAutoResponse>

    // Регистрация нового официанта. По SecurityConfig весь "/api/v1/worker/**"
    // кроме auth/** доступен только роли ADMIN - обычный официант этот
    // метод вызвать не сможет (сервер ответит 403).
    @POST("api/v1/worker/create")
    suspend fun createWaiter(@Body request: CreateWaiterRequest): Response<CreateWaiterResponse>

    // Смена пароля - тоже только ADMIN.
    @PATCH("api/v1/worker/update_password")
    suspend fun updatePassword(@Body request: UpdatePasswordRequest): Response<String>

    // ===== Меню: MenuController, "/api/v1/menus" =====

    // MenuController.getAllDishes() - GET /api/v1/menus/all/menu/category/{categoryId},
    // отдаёт блюда ОДНОЙ категории (List<MenuGetResponse>: id, name, price,
    // quantity, urlPhoto - без categoryId/categoryName в теле ответа, см.
    // комментарий у MenuRepository.getAllMenuItems() про то, как категория
    // прикрепляется на клиенте). Общего "плоского" списка всего меню в
    // схеме нет (см. п.1 в шапке файла) - собирать полный каталог нужно
    // циклом по всем категориям этим методом.
    @GET("api/v1/menus/all/menu/category/{categoryId}")
    suspend fun getDishesByCategory(@Path("categoryId") categoryId: Long): Response<List<MenuItem>>

    // ===== Категории: CategoryController, "/api/v1/categories" =====

    // CategoryController.getCategories() - GET /api/v1/categories/all
    // (не голый "/api/v1/categories" - это путь только для POST/create).
    @GET("api/v1/categories/all")
    suspend fun getAllCategories(): Response<List<CategoryInfo>>

    // CategoryController.create() - тело просто {"name": "..."}.
    @POST("api/v1/categories")
    suspend fun createCategory(@Body request: CreateCategoryRequest): Response<CategoryInfo>

    // ===== Столы: TableController, "/api/v1/tables" =====

    // TableController.getAllTables() - отдаёт List<CafeTable> напрямую
    // (та же JPA-сущность, только у неё реально сериализуются id и number).
    //
    // ВАЖНО про путь: как и у orders/create, это не корневой GET класса, а
    // отдельный метод @GetMapping("/all") поверх @RequestMapping("/api/v1/tables") -
    // реальный путь "/api/v1/tables/all". Голый "api/v1/tables" ни на что
    // не замаплен на сервере и вернёт 404.
    @GET("api/v1/tables/all")
    suspend fun getAllTables(): Response<List<TableInfo>>

    // ===== Заказы: OrderController, "/api/v1/orders" (роль WAITER/ADMIN) =====

    // Создание заказа. Тело - CreateAndUpdateOrderRequest(tableNumbers: Set<Long>,
    // items, comment) - да, именно Set<Long>, а не список строк номеров
    // (см. подробный комментарий у CreateOrderRequest в Order.kt).
    //
    // ВАЖНО про путь: OrderController на бэкенде объявлен как
    // @RequestMapping("/api/v1/orders") на классе + @PostMapping("create/")
    // на самом методе createOrder(). Spring склеивает это в
    // "/api/v1/orders/create/" - ИМЕННО С ЗАВЕРШАЮЩИМ СЛЭШЕМ, а на Spring
    // Boot 4 (см. pom.xml) trailing slash больше не матчится автоматически
    // (useTrailingSlashMatch выключен по умолчанию с Spring Framework 6),
    // так что "api/v1/orders" или даже "api/v1/orders/create" без слэша на
    // конце отдадут 404. Слэш на конце пути ниже - не опечатка.
    @POST("api/v1/orders/create/")
    suspend fun createOrder(@Body request: CreateOrderRequest): Response<OrderResponse>

    // Добавление новых позиций в уже существующий заказ. Тело - СПИСОК позиций
    // напрямую (без обёртки), как и объявлено в
    // OrderController.updateOrder(@PathVariable orderId, @RequestBody List<OrderItemRequest>).
    @PATCH("api/v1/orders/{orderId}/items")
    suspend fun addItemsToOrder(
        @Path("orderId") orderId: Long,
        @Body items: List<OrderItemRequest>
    ): Response<OrderResponse>

    // Увеличение количества блюда на +1. Без тела запроса - оба id в пути.
    @PATCH("api/v1/orders/{orderId}/items/{menuId}/increase")
    suspend fun increaseOrderItemQuantity(
        @Path("orderId") orderId: Long,
        @Path("menuId") menuId: Long
    ): Response<OrderResponse>

    // Уменьшение количества блюда на -1 (при 0 позиция удаляется на сервере;
    // если это была последняя позиция - весь заказ переходит в CANCELLED).
    @PATCH("api/v1/orders/{orderId}/items/{menuId}/decrease")
    suspend fun decreaseOrderItemQuantity(
        @Path("orderId") orderId: Long,
        @Path("menuId") menuId: Long
    ): Response<OrderResponse>

    // Отмена заказа. Без тела запроса.
    @PATCH("api/v1/orders/{orderId}/cancel")
    suspend fun cancelOrder(@Path("orderId") orderId: Long): Response<CancelOrderResponse>

    // Список заказов ТЕКУЩЕГО официанта - определяется по JWT в заголовке,
    // staffId никуда передавать не нужно. Путь по схеме - "my/all"
    // ("my" само по себе на сервере не замаплено, 404). ВАЖНО: это список
    // "коротких" сводок (только orderId, orderNumber и status, без
    // стола/блюд/суммы) - AllMyOrdersResponse/OrderSummary на бэкенде
    // специально урезаны. Полные данные по каждому заказу приложение и так
    // хранит локально (см. LocalOrderRepository), поэтому этот метод
    // используется скорее для сверки статусов, чем как основной источник
    // данных.
    @GET("api/v1/orders/my/all")
    suspend fun getMyOrders(): Response<WaiterOrdersResponse>

    // Полная информация по ОДНОМУ заказу текущего официанта - в отличие от
    // getMyOrders(), возвращает тот же формат, что и создание/правка заказа
    // (стол, блюда, сумма, статус). Пока не вызывается из UI напрямую (весь
    // экран "Заказы" работает с локальным блокнотом), но пригодится, если
    // понадобится сверка/восстановление чека с сервера.
    @GET("api/v1/orders/my/{orderId}")
    suspend fun getMyOrder(@Path("orderId") orderId: Long): Response<OrderResponse>
}
