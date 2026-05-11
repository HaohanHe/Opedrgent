package top.hsyscn.opedrgent.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

