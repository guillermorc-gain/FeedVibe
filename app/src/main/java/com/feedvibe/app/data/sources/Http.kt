package com.feedvibe.app.data.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object Http {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"

    /** Cookies de consentimiento para que YouTube no redirija a consent.youtube.com en la UE. */
    private val consentJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.host.endsWith("youtube.com")) return emptyList()
            return listOf(
                Cookie.Builder().domain("youtube.com").name("SOCS").value("CAI").build(),
                Cookie.Builder().domain("youtube.com").name("CONSENT").value("YES+cb").build(),
            )
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(consentJar)
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent") == null) {
                // A YouTube se le pide la web de escritorio: la móvil (m.youtube.com) tiene otro formato.
                builder.header("User-Agent", if (req.url.host.endsWith("youtube.com")) DESKTOP_UA else USER_AGENT)
            }
            if (req.header("Accept-Language") == null) builder.header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            chain.proceed(builder.build())
        }
        .build()

    data class Response(val body: String, val finalUrl: String, val contentType: String?)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw SourceException("Error ${resp.code} al abrir $url")
                    Response(resp.body?.string().orEmpty(), resp.request.url.toString(), resp.header("Content-Type"))
                }
            } catch (e: SourceException) {
                throw e
            } catch (e: Exception) {
                throw SourceException("No se pudo conectar: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw SourceException("Error ${resp.code} en $url")
                    resp.body?.string().orEmpty()
                }
            } catch (e: SourceException) {
                throw e
            } catch (e: Exception) {
                throw SourceException("No se pudo conectar: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
}
