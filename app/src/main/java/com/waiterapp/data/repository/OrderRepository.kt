package com.waiterapp.data.repository

import com.waiterapp.data.local.TokenStore
import com.waiterapp.data.model.CreateOrderRequest
import com.waiterapp.data.model.OrderItemRequest
import com.waiterapp.data.model.OrderResponse
import com.waiterapp.data.model.WaiterOrdersResponse
import com.waiterapp.data.network.WaiterApiService

/**
 * Прямые обёртки над OrderController бэкенда. Используется как напрямую
 * (если понадобится вызвать сервер без локального "блокнота"), так и
 * изнутри LocalOrderRepository для реальных попыток отправки.
 */
class OrderRepository(
    private val api: WaiterApiService,
    private val tokenManager: TokenStore
) {

    suspend fun createOrder(request: CreateOrderRequest): ApiResult<OrderResponse> =
        safeApiCall(tokenManager) { api.createOrder(request) }

    suspend fun addItemsToOrder(orderId: Long, items: List<OrderItemRequest>): ApiResult<OrderResponse> =
        safeApiCall(tokenManager) { api.addItemsToOrder(orderId, items) }

    suspend fun increaseQuantity(orderId: Long, menuId: Long): ApiResult<OrderResponse> =
        safeApiCall(tokenManager) { api.increaseOrderItemQuantity(orderId, menuId) }

    suspend fun decreaseQuantity(orderId: Long, menuId: Long): ApiResult<OrderResponse> =
        safeApiCall(tokenManager) { api.decreaseOrderItemQuantity(orderId, menuId) }

    suspend fun cancelOrder(orderId: Long) =
        safeApiCall(tokenManager) { api.cancelOrder(orderId) }

    suspend fun getMyOrders(): ApiResult<WaiterOrdersResponse> =
        safeApiCall(tokenManager) { api.getMyOrders() }
}
