package com.knomu1.webrtccam

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** PC の Blazor 画面に表示される QR の内容。 */
data class PairingPayload(val name: String, val token: String, val urls: List<String>)

object PairingParser {
    private const val PREFIX = "webrtccam://pair"

    fun parse(raw: String): PairingPayload? {
        if (!raw.startsWith(PREFIX)) return null
        val uri = Uri.parse(raw)
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
        val urls = uri.getQueryParameters("u").filter { it.startsWith("http://") || it.startsWith("https://") }
        if (urls.isEmpty()) return null
        return PairingPayload(uri.getQueryParameter("name").orEmpty().ifBlank { "PC" }, token, urls)
    }
}

/** 保存済みの接続先。 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server_link", Context.MODE_PRIVATE)

    fun load(): PairingPayload? {
        val token = prefs.getString("token", null) ?: return null
        val urls = prefs.getString("urls", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (urls.isEmpty()) return null
        return PairingPayload(prefs.getString("name", "PC").orEmpty(), token, urls)
    }

    fun save(payload: PairingPayload) {
        prefs.edit()
            .putString("name", payload.name)
            .putString("token", payload.token)
            .putString("urls", payload.urls.joinToString("\n"))
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

object ServerProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** 候補 URL を並列に疎通確認し、応答した最初の URL を返す。 */
    suspend fun resolve(urls: List<String>): String? = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { url.takeIf { probe(it) } } }.awaitAll().firstOrNull { it != null }
    }

    private fun probe(baseUrl: String): Boolean = try {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}