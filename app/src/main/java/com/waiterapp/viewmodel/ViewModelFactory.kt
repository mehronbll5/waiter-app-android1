package com.waiterapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.waiterapp.AppContainer

class ViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LoginViewModel::class.java) ->
                LoginViewModel(appContainer.authRepository) as T

            modelClass.isAssignableFrom(HallViewModel::class.java) ->
                HallViewModel(appContainer.tableRepository) as T

            modelClass.isAssignableFrom(MenuViewModel::class.java) ->
                MenuViewModel(appContainer.menuRepository) as T

            modelClass.isAssignableFrom(OrderViewModel::class.java) ->
                OrderViewModel(appContainer.localOrderRepository, appContainer.authRepository) as T

            modelClass.isAssignableFrom(OrdersViewModel::class.java) ->
                OrdersViewModel(appContainer.localOrderRepository) as T

            modelClass.isAssignableFrom(ProfileViewModel::class.java) ->
                ProfileViewModel(appContainer.authRepository) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
