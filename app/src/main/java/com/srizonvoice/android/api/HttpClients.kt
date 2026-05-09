package com.srizonvoice.android.api

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
