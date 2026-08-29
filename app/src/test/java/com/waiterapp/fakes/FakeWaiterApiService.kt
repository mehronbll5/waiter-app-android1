package com.waiterapp.fakes

import com.waiterapp.data.model.*
import com.waiterapp.data.network.WaiterApiService
import retrofit2.Response

/**
 * Тестовый двойник WaiterApiService. Каждый метод настраивается через
 * соответствующее поле-функцию перед вызовом тестируемого кода -
 * это позволяет тестировать репозитории без реального Retrofit/сети.
 */
class FakeWaiterApiService : WaiterApiService {

    var createWaiterResult: () -> Response<CreateWaiterResponse> =
        { throw IllegalStateException("createWaiterResult не настроен в тесте") }
    var loginResult: () -> Response<LoginResponse> =
        { throw IllegalStateException("loginResult не настроен в тесте") }
    var authAutoResult: () -> Response<AuthAutoResponse> =
        { throw IllegalStateException("authAutoResult не настроен в тесте") }
    var updatePasswordResult: () -> Response<String> =
        { throw IllegalStateException("updatePasswordResult не настроен в тесте") }
    // Ключ - categoryId, с которым вызван getDishesByCategory (см. WaiterApiService -
    // единого "все блюда одним списком" эндпоинта по Swagger-схеме не существует).
    var getDishesByCategoryResult: (Long) -> Response<List<MenuItem>> =
        { throw IllegalStateException("getDishesByCategoryResult не настроен в тесте") }
    var createOrderResult: () -> Response<OrderResponse> =
        { throw IllegalStateException("createOrderResult не настроен в тесте") }
    var addItemsToOrderResult: () -> Response<OrderResponse> =
        { throw IllegalStateException("addItemsToOrderResult не настроен в тесте") }
    var increaseOrderItemQuantityResult: () -> Response<OrderResponse> =
        { throw IllegalStateException("increaseOrderItemQuantityResult не настроен в тесте") }
    var decreaseOrderItemQuantityResult: () -> Response<OrderResponse> =
        { throw IllegalStateException("decreaseOrderItemQuantityResult не настроен в тесте") }
    var cancelOrderResult: () -> Response<CancelOrderResponse> =
        { throw IllegalStateException("cancelOrderResult не настроен в тесте") }
    var getMyOrdersResult: () -> Response<WaiterOrdersResponse> =
        { throw IllegalStateException("getMyOrdersResult не настроен в тесте") }
    var getMyOrderResult: () -> Response<OrderResponse> =
        { throw IllegalStateException("getMyOrderResult не настроен в тесте") }
    var getAllTablesResult: () -> Response<List<TableInfo>> =
        { throw IllegalStateException("getAllTablesResult не настроен в тесте") }

    var getAllCategoriesResult: () -> Response<List<CategoryInfo>> =
        { throw IllegalStateException("getAllCategoriesResult не настроен в тесте") }
    var createCategoryResult: () -> Response<CategoryInfo> =
        { throw IllegalStateException("createCategoryResult не настроен в тесте") }

    override suspend fun login(request: LoginRequest) = loginResult()
    override suspend fun authAuto(request: AuthAutoRequest) = authAutoResult()
    override suspend fun createWaiter(request: CreateWaiterRequest) = createWaiterResult()
    override suspend fun updatePassword(request: UpdatePasswordRequest) = updatePasswordResult()
    override suspend fun getDishesByCategory(categoryId: Long) = getDishesByCategoryResult(categoryId)
    override suspend fun getAllTables() = getAllTablesResult()
    override suspend fun getAllCategories() = getAllCategoriesResult()
    override suspend fun createCategory(request: CreateCategoryRequest) = createCategoryResult()
    override suspend fun createOrder(request: CreateOrderRequest) = createOrderResult()
    override suspend fun addItemsToOrder(orderId: Long, items: List<OrderItemRequest>) = addItemsToOrderResult()
    override suspend fun increaseOrderItemQuantity(orderId: Long, menuId: Long) = increaseOrderItemQuantityResult()
    override suspend fun decreaseOrderItemQuantity(orderId: Long, menuId: Long) = decreaseOrderItemQuantityResult()
    override suspend fun cancelOrder(orderId: Long) = cancelOrderResult()
    override suspend fun getMyOrders() = getMyOrdersResult()
    override suspend fun getMyOrder(orderId: Long) = getMyOrderResult()
}
