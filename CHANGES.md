# Что изменено в этой версии

## 0. Интеграция с новым бэкендом (RESTful, ресурсы во множественном числе)
Бэкенд заметно переработан по сравнению с предыдущей версией: ресурсы стали
множественного числа (`/api/v1/menus`, `/api/v1/tables`, `/api/v1/orders`
вместо `/api/v1/menu`, `/api/v1/table`, `/api/v1/order`), а `OrderController`
стал по-настоящему RESTful (`orderId` в пути, действие — под-ресурс, например
`PATCH /api/v1/orders/{orderId}/items/{menuId}/increase`).
- `data/network/WaiterApiService.kt` — все пути и структура запросов
  переписаны под новый бэкенд, добавлен `getMyOrder(orderId)`.
- `data/model/Menu.kt`, `data/local/MenuEntity.kt`, `AppDatabase.kt`
  (версия схемы 6 → 7) — у блюда больше нет категории: `GET /api/v1/menus`
  этого бэкенда не отдаёт её вообще (ни объектом, ни строкой). Поле
  `urlPhoto` с сервера замаплено на `imageUrl` на клиенте через `@SerializedName`.
- `viewmodel/MenuViewModel.kt` — фильтрация по категории отключена (фильтровать
  не по чему), кнопки категорий на экране "Новый заказ" теперь просто разные
  точки входа в один и тот же полный список блюд.
- `data/repository/TableRepository.kt`, `viewmodel/HallViewModel.kt` — топик
  создания стола без конечного слэша (`/topic/create/table`); топика на
  удаление стола на этом бэкенде нет вовсе (`deleteTable` ничего не
  публикует в брокер) — вместо него вернули фоновый опрос раз в 5 сек.
- `CreateOrderRequest.tableNumbers: Set<Long>` и вся логика
  `LocalOrderRepository`/`OrderRepository` не менялись — они уже были
  написаны под этот же (буквенно-числовой) контракт бэкенда.

## 1. Подключение сервера
- `ApiConfig.kt` — единое место для адреса сервера (`BASE_URL`) и mock-флагов
  (`MOCK_LOGIN_ENABLED`, `MOCK_MENU_ENABLED`, `MOCK_ORDER_ENABLED`).

## 2. Понятные ошибки сети + автовыход при истёкшей сессии
- `data/repository/SafeApiCall.kt` — общая обёртка над Retrofit-вызовом.
  Вместо `"Ошибка сети: ${e.message}"` теперь понятные сообщения:
  нет интернета / таймаут / сервер недоступен / сессия истекла и т.д.
- `data/network/SessionEvents.kt` + подписка в `AppNavHost.kt` — если сервер
  вернул 401/403, токен чистится и официанта автоматически кидает на экран входа.

## 3. Логирование HTTP только в debug
- `RetrofitProvider.kt` — тела запросов/ответов (включая пароли/токены)
  больше не пишутся в logcat в релизной сборке.

## 4. Офлайн-кэш меню (Room)
- `data/local/MenuEntity.kt`, `MenuDao.kt`, `AppDatabase.kt`
- `MenuRepository.kt` — при потере сети отдаёт последнее сохранённое меню
  с пометкой `fromCache = true`; `MenuViewModel` показывает об этом сообщение.

## 5. Столики — готовы к реальному бэкенду
- `TableRepository.kt` — по-настоящему вызывает `GET api/tables/all`.
  Пока бэкенд не реализовал эндпоинт, тихо откатывается на `MockTableData`.
  Как только бэкенд его добавит — экран "Карта зала" начнёт показывать
  реальные данные без единой правки в клиенте.
  Если бэкенд назовёт путь/поля иначе — поправь `@GET` в `WaiterApiService.kt`
  и поля `TableInfo` в `Table.kt`.

## 6. Тестируемость
- `data/local/TokenStore.kt` — интерфейс поверх `TokenManager`, чтобы
  репозитории и safeApiCall не зависели от конкретной Android-реализации
  (EncryptedSharedPreferences) и их можно было тестировать без Android Context.

## 7. Юнит-тесты (`app/src/test/...`)
- `AuthRepositoryTest`, `MenuRepositoryTest` (включая офлайн-кэш),
  `OrderRepositoryTest`, `TableRepositoryTest`
- `LoginViewModelTest`, `OrderViewModelTest` (корзина/тотал/валидация),
  `MenuViewModelTest` (загрузка mock-меню; фильтрация по категории отключена,
  т.к. бэкенд не отдаёт категорию блюда - см. пункт 0)
- Fake-двойники вместо реальной сети/БД: `FakeWaiterApiService`,
  `FakeTokenStore`, `FakeMenuDao`

Запуск тестов: `./gradlew test`

## 8. Строки вынесены в `res/values/strings.xml`
Экраны Login/Hall/Menu/NewOrder теперь берут текст из ресурсов, а не
хардкодят его в коде.

## 9. Синхронизация с REST API документацией (android_api_documentation)
Проект приведён в соответствие с присланной документацией бэкенда (v1.0).

- **Вход (`POST api/waiter/login`)**: запрос остаётся `{staffId, password}`
  (как было в приложении) — поле `"token"` в примере запроса из документации
  было опечаткой/устаревшим примером. Ответ теперь содержит и `token`,
  и `accessToken` (JWT); именно `accessToken` сохраняется и используется
  как Bearer-токен (`Waiter.kt`, `AuthRepository.kt`).
- **Меню**: путь сменён на `GET api/menu/all_dishes`. Блюдо теперь содержит
  `id`, категория — вложенный объект `{id, name}` вместо строки
  (`Menu.kt`, `MenuEntity.kt`, `MenuViewModel.kt`, `MenuScreen.kt`).
- **Столики**: путь сменён на `GET api/table/tables/`. Номер стола — строка
  (например `"T-01"`), статус — `NOT_RESERVED`/`RESERVED` (в API нет
  отдельного "занят"/количества гостей — упрощено под документацию,
  `Table.kt`, `HallScreen.kt`, `HallViewModel.kt`).
- **Заказы**: `POST api/order/create` (без `staffId` в теле — официант
  определяется по JWT), `POST api/order/add-items` (тело содержит
  `orderId`, а не путь), новые `POST api/order/increase-quantity` /
  `decrease-quantity`, `POST api/order/cancel` (без опечатки "cancla" и без
  `orderId` в пути). Поля ответа переименованы под документацию:
  `orderId`/`totalPrice`/`menu` вместо `id`/`allPrice`/`dishes` (`Order.kt`,
  `WaiterApiService.kt`, `LocalOrderRepository.kt`).
- Номера столов везде в приложении (навигация, экраны, локальный блокнот
  заказов) теперь `String`, а не `Int` — под формат `"T-01"` из документации.
- Эндпоинты, которых нет в новой документации (регистрация официанта,
  смена пароля, список заказов официанта, получение одного блюда по имени),
  оставлены как есть — они явно не отменены, просто не описаны в этой версии
  документа. Список категорий меню по-прежнему зашит на клиенте — в
  документации нет эндпоинта для его получения.
- Обновлены все юнит-тесты и fake-двойники под новые модели/эндпоинты.

---

## Важно перед первой сборкой
Этот проект собирался и проверялся **вручную построчно** (импорты, типы,
сигнатуры), но полную сборку через Gradle в среде, где готовился этот
патч, прогнать не удалось (нет доступа к Google Maven/services.gradle.org).
Поэтому при первом открытии в Android Studio:
1. Sync Gradle.
2. Build → Make Project.
3. Если Android Studio подсветит мелкую ошибку компиляции — скорее всего,
   это опечатка в одном месте, а не системная проблема; поправить будет быстро.

## Что осознанно не сделано (см. предыдущее обсуждение недостатков)
- Реальный realtime (WebSocket/push) — нужна поддержка на бэкенде.
- Refresh-токен — в API нет эндпоинта обновления токена, есть только logout
  по истечении (см. п.2 выше).
- Полноценная локализация (только строки вынесены, второй язык не добавлен).

## 10. Синхронизация с РЕАЛЬНЫМ Spring Boot бэкендом (не с текстовой документацией)
На вход дали исходники бэкенда (Spring Boot, `com.example.*`) - выяснилось,
что предыдущая версия клиента была построена по документации, которая
во многом не совпадала с реальным кодом сервера (другие пути, другие
DTO, вымышленный Supabase-адрес). Приложение приведено в соответствие
с тем, что реально возвращают контроллеры бэкенда:

- **BASE_URL** (`ApiConfig.kt`) — сервер поднимается на порту `8081`
  (`application.yml`), а не на Supabase Edge Function. Поставь свой адрес.
- **Вход**: `POST api/worker/auth/logIn/` (а не `api/waiter/login`).
  Ответ по-прежнему `{token, accessToken}`, но `token` — это именно
  refresh-токен (24ч), а не эхо чего-либо.
- **Имя официанта**: сервер не отдаёт его при логине и не имеет
  `GET api/waiter/me`. Единственный эндпоинт, который знает имя —
  `POST api/worker/auth/auto/` (принимает refresh-токен, возвращает
  `waiterName` + `staffId` + новый `accessToken`). `AuthRepository.login`
  теперь вызывает его сразу после успешного входа (см. `fetchAndSaveNickname`).
  Добавлен `AuthRepository.tryAutoLogin()` для повторного использования
  этого же эндпоинта при обычном авто-входе по сохранённому токену.
- **Меню**: путь `GET api/menu/all_dishes` остался тем же, но это сервер
  отдаёт "сырую" JPA-сущность `Menu` (не отдельный DTO) — так что
  структура (`id, name, price, quantity, category{id,name}`) в целом
  совпала с тем, что уже было в `MenuItem`/`MenuCategoryDto`.
  ⚠️ ВАЖНО: на бэкенде `Menu.orderItems` и `OrderItem.menu` — двусторонняя
  JPA-связь без `@JsonIgnore` ни на одной из сторон. Это значит, что
  `GET api/menu/all_dishes` рискует упасть с `StackOverflowError`
  при сериализации (Menu → orderItems → OrderItem → menu → orderItems → ...),
  как только у блюда появится хотя бы один заказ. Это баг бэкенда,
  не клиента — стоит попросить добавить `@JsonIgnore` на `Menu.orderItems`
  (или `OrderItem.menu`), либо завести отдельный response-DTO для меню.
- **Столы**: путь сменён на `GET api/table/all/` (было `api/table/tables/`).
  Сервер тоже отдаёт "сырую" сущность `CafeTable` — там нет поля `status`
  (оно закомментировано в `CafeTable.java`), поэтому `TableInfo.status`
  стал `nullable` (`null` = статус неизвестен, UI трактует это как
  "стол свободен"). Доступ к `api/table/**` на бэкенде — только роль ADMIN.
- **Заказы**: пути и HTTP-методы приведены в соответствие с `OrderController`:
  `POST api/order/create/`, `PATCH api/order/add_new/{orderId}/` (тело —
  список позиций напрямую, без обёртки), `PATCH api/order/increase/{orderId}/{menuId}`,
  `PATCH api/order/decrease/{orderId}/{menuId}` (без тела, только path-параметры),
  `PATCH api/order/canceled/{orderId}/` (без тела), `GET api/order/my/orders/`
  (официант определяется по JWT, `staffId` в пути не нужен).
  ⚠️ ВАЖНО: `tableNumbers` в запросе создания заказа — это `Set<Long>` на
  бэкенде, а не список строк вида `"T-01"`. При этом `CafeTable.number`
  в БД хранится как `String`. Чтобы `Set<Long>` вообще мог сматчиться
  с таким полем, номера столов должны быть чисто цифровыми строками
  (`"1"`, `"12"`...). `LocalOrderRepository.trySend` теперь сам проверяет
  это и не отправляет заказ (оставляя его `NOT_SENT` с понятной ошибкой),
  если номер стола не приводится к числу.
