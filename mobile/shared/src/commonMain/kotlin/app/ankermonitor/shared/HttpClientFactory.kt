package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/** プラットフォームのエンジン (Android: OkHttp / iOS: Darwin) で HttpClient を作る */
expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

expect fun currentTimeMillis(): Long
