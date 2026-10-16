package com.knomu1.camerastream

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** PC の Blazor 画面に表示される QR の内容。 */
data class PairingPayload(val name: String, val token: String, val urls: List<String>)

object PairingParser {
    private const val PREFIX = "camerastream://pair"

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

/** 認識した 1 行。corners は画像(回転補正後)のピクセル座標で、左上から時計回りの 4 点。 */
data class OcrLine(val text: String, val corners: List<Pair<Int, Int>>)

sealed interface SendResult {
    data object Ok : SendResult
    data object Unauthorized : SendResult
    data class Failed(val reason: String) : SendResult
}

class ServerApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** 候補 URL を並列に疎通確認し、応答した最初の URL を返す。 */
    suspend fun resolve(urls: List<String>): String? = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { url.takeIf { probe(it) } } }.awaitAll().firstOrNull { it != null }
    }

    private fun probe(baseUrl: String): Boolean = try {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /** 文字認識の結果を非同期に送る。 */
    fun sendOcr(
        baseUrl: String,
        token: String,
        deviceId: String,
        width: Int,
        height: Int,
        lines: List<OcrLine>,
        onResult: (SendResult) -> Unit,
    ) {
        val json = JSONObject()
            .put("deviceId", deviceId)
            .put("imageWidth", width)
            .put("imageHeight", height)
            .put(
                "items",
                JSONArray(
                    lines.map { line ->
                        JSONObject()
                            .put("text", line.text)
                            .put("corners", JSONArray(line.corners.map { JSONObject().put("x", it.first).put("y", it.second) }))
                    },
                ),
            )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/ocr")
            .header("X-Api-Key", token)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(SendResult.Failed(e.javaClass.simpleName + ": " + e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    onResult(
                        when {
                            it.isSuccessful -> SendResult.Ok
                            it.code == 401 -> SendResult.Unauthorized
                            else -> SendResult.Failed("HTTP ${it.code}")
                        }
                    )
                }
            }
        })
    }

    /** JPEG 1 フレームを非同期に送る。結果は onResult で返る(呼び出しスレッドは問わない)。 */
    fun sendFrame(
        baseUrl: String,
        token: String,
        deviceId: String,
        width: Int,
        height: Int,
        jpeg: ByteArray,
        onResult: (SendResult) -> Unit,
    ) {
        val url = "${baseUrl.trimEnd('/')}/api/frames?device=${Uri.encode(deviceId)}&w=$width&h=$height"
        val request = Request.Builder()
            .url(url)
            .header("X-Api-Key", token)
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(SendResult.Failed(e.javaClass.simpleName + ": " + e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    onResult(
                        when {
                            it.isSuccessful -> SendResult.Ok
                            it.code == 401 -> SendResult.Unauthorized
                            else -> SendResult.Failed("HTTP ${it.code}")
                        }
                    )
                }
            }
        })
    }
}