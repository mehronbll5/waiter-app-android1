package com.waiterapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * viewModelScope использует Dispatchers.Main, которого нет в чистой JVM
 * при юнит-тестах. Это правило подставляет тестовый диспетчер на время теста.
 *
 * UnconfinedTestDispatcher выполняет корутины сразу же, синхронно
 * (в наших fake-репозиториях нет реальных долгих suspend-точек) - это
 * позволяет в тестах проверять состояние ViewModel сразу после вызова
 * метода вроде submitOrder(), не дожидаясь отдельного advanceUntilIdle().
 */
@ExperimentalCoroutinesApi
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
