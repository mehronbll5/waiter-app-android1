package com.waiterapp.data.network

import com.waiterapp.BuildConfig
import com.waiterapp.data.local.TokenStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit


object RetrofitProvider {

    fun create(
        tokenManager: TokenStore,
        refreshAccessToken: suspend (failedAccessToken: String?) -> Boolean
    ): WaiterApiService {
        // Таймауты отключены (0 = ждать сколько потребуется), чтобы запрос
        // никогда не обрывался по времени сам по себе - только по реальному
        // разрыву соединения. Это нужно, чтобы изменения на бэкенде всегда
        // доходили до приложения, даже если сеть/сервер отвечают медленно.
        val okHttpClientBuilder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenManager, refreshAccessToken))
            .addInterceptor(NgrokHeaderInterceptor())
            .connectTimeout(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)

        // Даже в DEBUG нельзя писать в Logcat пароли, токены или Authorization.
        // Заголовки/тела полностью не логируем.
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
                redactHeader("Cookie")
            }
            okHttpClientBuilder.addInterceptor(loggingInterceptor)
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttpClientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(WaiterApiService::class.java)
    }
}
