package com.knomu1.qrscanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** PC の Blazor 画面に表示される接続用 QR の内容。 */
data class PairingPayload(val name: String, val token: String, val urls: List<String>)

object PairingParser {
    private const val PREFIX = "scanmonitor://pair"

    fun isPairing(raw: String) = raw.startsWith(PREFIX)

    fun parse(raw: String): PairingPayload? {
        if (!isPairing(raw)) return null
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

/** アップロード結果。 */
sealed interface UploadResult {
    data object Ok : UploadResult
    data object Unauthorized : UploadResult
    data class Failed(val reason: String) : UploadResult
}

/** サーバーとの HTTP 通信（疎通確認・アップロード）。 */
class ServerApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val probeClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /** 候補 URL を並列に疎通確認し、応答した最初（候補順）の URL を返す。 */
    suspend fun resolve(urls: List<String>): String? = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { url.takeIf { probe(it) } } }.awaitAll().firstOrNull { it != null }
    }

    private fun probe(baseUrl: String): Boolean = try {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    suspend fun upload(
        baseUrl: String,
        token: String,
        deviceId: String,
        capturedAt: String,
        imageWidth: Int,
        imageHeight: Int,
        jpeg: ByteArray,
        codes: List<Detection>,
    ): UploadResult = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("deviceId", deviceId)
            .put("capturedAt", capturedAt)
            .put("imageWidth", imageWidth)
            .put("imageHeight", imageHeight)
            .put(
                "codes",
                JSONArray(
                    codes.map { code ->
                        JSONObject()
                            .put("value", code.value)
                            .put("format", formatLabel(code.format))
                            .put(
                                "corners",
                                JSONArray(code.corners.map { JSONObject().put("x", it.x).put("y", it.y) }),
                            )
                    },
                ),
            )

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadata.toString())
            .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/scans")
            .header("X-Api-Key", token)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadResult.Ok
                    response.code == 401 -> UploadResult.Unauthorized
                    else -> UploadResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            UploadResult.Failed(e.javaClass.simpleName + ": " + e.message)
        }
    }
}
