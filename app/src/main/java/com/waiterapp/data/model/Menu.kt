package com.waiterapp.data.model

import com.google.gson.annotations.SerializedName

// --- 3. Каталог меню (GET /api/v1/menus) ---
//
// После правки MenuMapperImpl на бэкенде ответ теперь содержит категорию
// блюда: categoryId/categoryName. Раньше здесь был длинный комментарий про
// то, что категория теряется при маппинге на сервере и фильтрация по ней
// декоративная - это исправлено, см. MenuViewModel.filteredItems.
data class MenuItem(
    val id: Long,
    val name: String,
    val price: Double,
    val quantity: Double,
    // На сервере поле называется "urlPhoto" (MenuGetResponse.urlPhoto) -
    // оставляем имя imageUrl на клиенте (так исторически называлось раньше
    // и так его используют экраны меню), просто подсказываем Gson, под каким
    // именем реально придёт JSON-поле.
    @SerializedName("urlPhoto")
    val imageUrl: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null
)

