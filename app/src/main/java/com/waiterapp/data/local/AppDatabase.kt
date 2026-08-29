package com.waiterapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MenuEntity::class, LocalOrderEntity::class, TableEntity::class, OldOrderCacheEntity::class],
    // v8: обратно добавлены categoryId/categoryName в MenuEntity - после
    // правки MenuMapperImpl на бэкенде GET /api/v1/menus снова отдаёт
    // категорию блюда (см. комментарий в data/model/Menu.kt).
    // v9: добавлено поле kitchenStatus в LocalOrderEntity - для событий
    // готовности заказа, приходящих с топика "/topic/order/waiter"
    // (см. LocalOrderRepository.applyKitchenWaiterEvent).
    // v10: добавлена таблица old_orders_cache - 24-часовой локальный кэш
    // вкладки "Старые" в разделе "Заказы" (см. OldOrderCacheEntity,
    // LocalOrderRepository.getOldOrders). Не влияет на существующую
    // local_orders (блокнот) - отдельная таблица специально, чтобы не
    // трогать уже рабочую логику черновиков/отправки заказов.
    // v11: добавлено поле backendStatus в LocalOrderEntity - точный статус,
    // который вернул backend после PATCH api/v1/orders/{orderId}/cancel
    // (см. LocalOrderRepository.cancelOrder, "замена удаления заказа на
    // отмену через CANCEL API").
    // v12: в кэш старых заказов добавлен orderNumber — UI показывает номер ордера, а не технический orderId.
    // v13: orderNumber может быть строковым/алфавитно-цифровым (например 3Df), поэтому тип изменён Int -> String.
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun localOrderDao(): LocalOrderDao
    abstract fun tableDao(): TableDao
    abstract fun oldOrderCacheDao(): OldOrderCacheDao

    /**
     * Полностью очищает локальные данные официанта при выходе из аккаунта.
     * Серверные заказы не затрагиваются — удаляются только локальные Room-кэши.
     */
    suspend fun clearLocalCache() {
        menuDao().clear()
        localOrderDao().clear()
        tableDao().clear()
        oldOrderCacheDao().clear()
    }

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "waiterapp.db"
                )
                    // Приложение ещё в разработке, реальных пользователей с
                    // накопленными данными нет - при смене схемы (добавление
                    // таблицы local_orders) просто пересоздаём базу, а не
                    // пишем миграцию вручную.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
