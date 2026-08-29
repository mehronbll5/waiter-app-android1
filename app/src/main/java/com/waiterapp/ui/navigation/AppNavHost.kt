package com.waiterapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.waiterapp.AppContainer
import com.waiterapp.data.network.SessionEvents
import com.waiterapp.data.repository.ApiResult
import com.waiterapp.ui.screens.login.LoginScreen
import com.waiterapp.ui.screens.main.MainScreen
import com.waiterapp.ui.screens.menu.MenuScreen
import com.waiterapp.ui.screens.order.NewOrderScreen
import com.waiterapp.ui.screens.orders.OrderDetailScreen
import com.waiterapp.ui.screens.splash.SplashScreen
import com.waiterapp.viewmodel.EditTarget
import com.waiterapp.viewmodel.LoginViewModel
import com.waiterapp.viewmodel.MenuViewModel
import com.waiterapp.viewmodel.OrderViewModel
import com.waiterapp.viewmodel.OrdersViewModel
import com.waiterapp.viewmodel.ViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun AppNavHost(appContainer: AppContainer) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val factory = ViewModelFactory(appContainer)

    // OrderViewModel общий для экранов Меню и Новый заказ, чтобы корзина не терялась
    val orderViewModel: OrderViewModel = viewModel(factory = factory)

    // MenuViewModel тоже общий (не создаётся заново на каждом экране) - иначе
    // список категорий, загруженный на "Новый заказ" (для CategoryRow), не
    // совпадал бы с тем же списком на экране "Меню", и пришлось бы дважды
    // ходить в сеть за одним и тем же.
    val menuViewModel: MenuViewModel = viewModel(factory = factory)

    val cfg = com.waiterapp.data.network.ApiConfig

    // ВРЕМЕННО: см. ApiConfig.DEV_TOKEN_ENABLED - подставляем готовый
    // accessToken напрямую в хранилище, минуя /logIn и минуя /auth/auto:
    // это осознанный debug-обход (см. доккомментарий флага и
    // AuthRepository.injectDevAccessToken - autoLoginToken там заглушка,
    // с которой tryAutoLogin() всё равно не сработал бы), а не часть
    // обычного flow, поэтому он не переиспользует Splash-проверку ниже.
    if (cfg.DEV_TOKEN_ENABLED) {
        appContainer.authRepository.injectDevAccessToken(
            accessToken = cfg.DEV_ACCESS_TOKEN,
            staffId = cfg.DEV_STAFF_ID
        )
    }

    // Стартовый экран выбирается СИНХРОННО (NavHost требует startDestination
    // сразу), поэтому debug/demo-флаги, которые по своей документированной
    // цели обязаны обойтись без реального /auth/auto (SKIP_LOGIN_ENABLED -
    // смотреть UI без сервера вообще; DEV_TOKEN_ENABLED - см. выше;
    // MOCK_LOGIN_ENABLED/DEMO_MODE_ENABLED - полностью автономный режим без
    // единого сетевого запроса, см. ApiConfig), обрабатываются здесь, на
    // основе только локально сохранённого состояния. Обычный запуск (все
    // флаги выключены, значение по умолчанию) ВСЕГДА стартует с
    // Screen.Splash - см. её LaunchedEffect ниже: Hall для него не
    // появляется, пока не придёт результат POST /auth/auto (или пока не
    // выяснится, что сохранённого токена нет вовсе).
    val startDestination = when {
        cfg.SKIP_LOGIN_ENABLED -> Screen.Hall.route
        cfg.DEV_TOKEN_ENABLED -> Screen.Hall.route
        cfg.MOCK_LOGIN_ENABLED ->
            if (appContainer.authRepository.isLoggedIn()) Screen.Hall.route else Screen.Login.route
        else -> Screen.Splash.route
    }

    // Splash проставляет этот флаг перед переходом на Login, если редирект
    // вызван тем, что сохранённый refresh/autoLogin token оказался
    // невалиден на сервере (а не тем, что токена не было вовсе) - тогда
    // Login покажет "сессия истекла" вместо обычного пустого экрана входа.
    // Экран Login сам сбрасывает флаг сразу после прочтения.
    var sessionExpiredPending by remember { mutableStateOf(false) }

    // Если централизованный refresh подтвердил окончание/невалидность
    // 24-часовой сессии, AuthRepository очищает TokenManager и публикует
    // SessionExpired. Здесь мы переводим официанта обратно на экран входа.
    // на текущем экране с непонятной ошибкой. 403 (не хватает прав у роли,
    // токен при этом валиден) сюда больше НЕ попадает - см. SafeApiCall.kt,
    // такую ошибку экран, вызвавший запрос, покажет сам, без выхода из сессии.
    // Пока SKIP_LOGIN_ENABLED включён, это отключаем - иначе первый же запрос
    // без токена (401) тут же выкинул бы обратно на экран входа, а он скрыт.
    LaunchedEffect(Unit) {
        SessionEvents.sessionExpired.collectLatest {
            if (!cfg.SKIP_LOGIN_ENABLED) {
                // При истечении/принудительном завершении сессии также
                // удаляем локальные данные предыдущего официанта.
                appContainer.clearLocalCache()
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        // Единственное место, где решается, авторизован официант или нет -
        // см. форензик-аудит auth flow. БАГ, который чинит этот экран: раньше
        // стартовым экраном сразу становился Hall на основании ЛОКАЛЬНОГО
        // 24-часового таймера (TokenManager.checkSession), а сетевой
        // /auth/auto запускался ПОСЛЕ, отдельным LaunchedEffect(sessionState)
        // прямо в уже показанном Hall - официант успевал увидеть Карту зала
        // (и её запросы успевали словить 401) ДО того, как приходил ответ
        // /auth/auto, а при ошибке его выкидывало обратно на Login уже ПОСЛЕ
        // Hall. Теперь: сначала Splash и ответ сервера, и только потом
        // Hall или Login - Hall никогда не отображается во время проверки.
        composable(Screen.Splash.route) {
            LaunchedEffect(Unit) {
                // Наличие токена - лишь основание ПОПРОБОВАТЬ автовход, а не
                // само по себе "авторизован" (сюда сознательно НЕ подмешан
                // локальный 24-часовой таймер - решение принимает только
                // ответ сервера, см. AuthRepository.hasStoredAutoLoginToken).
                val hadToken = appContainer.authRepository.hasStoredAutoLoginToken()
                val destination = if (!hadToken) {
                    Screen.Login.route
                } else {
                    when (appContainer.authRepository.tryAutoLogin()) {
                        is ApiResult.Success -> Screen.Hall.route
                        // Любая ошибка (401/403/5xx/таймаут/сеть/и т.д.) -
                        // tryAutoLogin() уже сам очистил токены при ошибке.
                        is ApiResult.Error -> Screen.Login.route
                    }
                }
                sessionExpiredPending = hadToken && destination == Screen.Login.route
                navController.navigate(destination) {
                    popUpTo(Screen.Splash.route) { inclusive = true }
                }
            }
            SplashScreen()
        }

        composable(Screen.Login.route) {
            val loginViewModel: LoginViewModel = viewModel(factory = factory)
            LaunchedEffect(Unit) {
                // ВРЕМЕННО: приоритет между dev-режимами входа (см. ApiConfig) -
                // сначала пробуем refresh-токен (самый "настоящий" способ),
                // потом staffId+пароль, и только если ничего не включено -
                // обычный ручной вход.
                when {
                    cfg.DEV_REFRESH_TOKEN_ENABLED ->
                        loginViewModel.devAutoLoginWithRefreshToken()
                    cfg.DEV_AUTO_LOGIN_ENABLED ->
                        loginViewModel.devAutoLogin()
                    sessionExpiredPending -> {
                        loginViewModel.showSessionExpiredMessage()
                        sessionExpiredPending = false
                    }
                }
            }
            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = {
                    navController.navigate(Screen.Hall.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Hall.route) {
            MainScreen(
                factory = factory,
                onNavigateToNewOrder = { tableNumbers, editOrderId ->
                    // Сбрасываем корзину только тут - в момент РЕАЛЬНОГО перехода
                    // к новому заказу с Карты зала/раздела "Заказы". Раньше сброс
                    // висел внутри самого экрана "Новый заказ" и срабатывал ещё и
                    // при каждом возврате с "Меню" (Compose заново прогоняет
                    // LaunchedEffect при повторном входе в композицию), из-за чего
                    // только что добавленные блюда пропадали.
                    if (editOrderId == null) {
                        orderViewModel.discardEditing()
                    }
                    navController.navigate(Screen.NewOrder.createRoute(tableNumbers, editOrderId))
                },
                onOrderClick = { receipt ->
                    navController.navigate(Screen.OrderDetail.createRoute(receipt.id))
                },
                onLoggedOut = {
                    coroutineScope.launch {
                        // Сначала очищаем локальный кэш, затем показываем экран
                        // входа — старые данные не должны попасть следующему аккаунту.
                        appContainer.clearLocalCache()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        // "Чек" одного заказа - открывается тапом по заказу в разделе "Заказы".
        composable(
            route = Screen.OrderDetail.route,
            arguments = listOf(navArgument("orderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getLong("orderId") ?: -1L
            val orderDetailViewModel: OrdersViewModel = viewModel(factory = factory)
            OrderDetailScreen(
                viewModel = orderDetailViewModel,
                orderId = orderId,
                onBack = { navController.popBackStack() },
                onEdit = { receipt ->
                    navController.navigate(
                        Screen.NewOrder.createRoute(
                            receipt.tableNumbersCsv.split(",").filter { it.isNotBlank() }.map { it.trim() },
                            receipt.id
                        )
                    )
                }
            )
        }

        // "Новый заказ" - главный экран с категориями наверху и корзиной внизу.
        // Тот же экран используется и для правки существующего заказа из
        // раздела "Заказы" (тогда в маршруте передан editOrderId).
        composable(
            route = Screen.NewOrder.route,
            arguments = listOf(
                navArgument("tableNumbers") { type = NavType.StringType },
                navArgument("editOrderId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val tableNumbers = decodeTableNumbers(backStackEntry.arguments?.getString("tableNumbers"))
            val editOrderId = backStackEntry.arguments?.getLong("editOrderId")?.takeIf { it != -1L }

            // Если открыли для правки - один раз подгружаем чек в корзину.
            // Сброс корзины для ОБЫЧНОГО нового заказа теперь происходит один
            // раз в момент навигации сюда с Карты зала/"Заказы" (см. AppNavHost
            // -> onNavigateToNewOrder), а не здесь - иначе корзина стиралась бы
            // при каждом возврате с экрана "Меню" (см. комментарий там же).
            //
            // ВАЖНО: NavHost пересоздаёт композицию этого composable при
            // возврате назад (popBackStack) с экрана "Меню" - это НОВЫЙ
            // экземпляр композиции, поэтому LaunchedEffect(editOrderId) со
            // старым (тем же) editOrderId запускается заново, хотя ключ не
            // менялся. orderViewModel при этом общий на весь nav-граф и уже
            // хранит режим правки (editTarget) и только что добавленные в
            // MenuScreen блюда - если тут снова безусловно вызвать
            // startEditingOrder(order), для AppendToSent он пересоздаст cart
            // ПУСТЫМ (см. OrderViewModel.startEditingOrder) и только что
            // выбранное блюдо пропадёт. Поэтому подгружаем чек в корзину
            // только когда правка ДЕЙСТВИТЕЛЬНО начинается заново (editTarget
            // ещё не выставлен на этот же localOrderId), а не при каждом
            // повторном входе в композицию этого экрана.
            LaunchedEffect(editOrderId) {
                if (editOrderId != null) {
                    val alreadyEditingThisOrder = when (val target = orderViewModel.editTarget) {
                        is EditTarget.EditDraft -> target.localOrderId == editOrderId
                        is EditTarget.AppendToSent -> target.localOrderId == editOrderId
                        null -> false
                    }
                    if (!alreadyEditingThisOrder) {
                        appContainer.localOrderRepository.getById(editOrderId)?.let { order ->
                            orderViewModel.startEditingOrder(order)
                        }
                    }
                }
            }

            NewOrderScreen(
                viewModel = orderViewModel,
                menuViewModel = menuViewModel,
                tableNumbers = tableNumbers,
                isEditing = editOrderId != null,
                onBack = {
                    orderViewModel.discardEditing()
                    navController.popBackStack()
                },
                onCategoryClick = { category ->
                    navController.navigate(Screen.Menu.createRoute(tableNumbers, category.id))
                },
                onAddMoreClick = {
                    navController.navigate(Screen.Menu.createRoute(tableNumbers, MENU_CATEGORY_ALL_ID))
                },
                onOrderSubmitted = {
                    if (editOrderId != null) {
                        // Правка существующего заказа - просто возвращаемся туда,
                        // откуда пришли (раздел "Заказы"), не сбрасывая вкладки/выбор столов.
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Hall.route) {
                            popUpTo(Screen.Hall.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // "Меню" - список блюд конкретной категории, открывается из "Новый заказ"
        composable(
            route = Screen.Menu.route,
            arguments = listOf(
                navArgument("tableNumbers") { type = NavType.StringType },
                navArgument("categoryId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val categoryIdArg = backStackEntry.arguments?.getLong("categoryId") ?: MENU_CATEGORY_ALL_ID
            // MENU_CATEGORY_ALL_ID (-1) означает "без фильтра" - сюда попадает
            // кнопка "ДОБАВИТЬ ЕЩЁ", реальные ID категорий с сервера всегда
            // положительные, поэтому сентинел с ними не пересечётся.
            val categoryId = categoryIdArg.takeIf { it != MENU_CATEGORY_ALL_ID }
            MenuScreen(
                menuViewModel = menuViewModel,
                orderViewModel = orderViewModel,
                categoryId = categoryId,
                onBack = { navController.popBackStack() },
                onGoToCart = { navController.popBackStack() }
            )
        }
    }
}
