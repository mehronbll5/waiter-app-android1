package com.waiterapp.data.model

// --- Категории (GET/POST /api/v1/categories) ---
//
// Раньше здесь был захардкоженный enum MenuCategory с тремя фиксированными
// категориями (Завтрак/Пицца/Напитки) и своими PNG-иконками - декоративный
// список, никак не связанный с реальными данными на сервере. После правки
// MenuMapperImpl на бэкенде GET /api/v1/menus начал реально отдавать
// категорию блюда, а GET /api/v1/categories - список всех категорий
// (созданных, например, через Swagger или админку). Enum убран целиком -
// категории теперь произвольные и приходят с сервера, поэтому у них нет и
// не может быть заранее подготовленной иконки под каждое название.
data class CategoryInfo(
    val id: Long,
    val name: String
)

// Тело запроса POST /api/v1/categories - CategoryRequest{name} на бэкенде.
data class CreateCategoryRequest(
    val name: String
)
