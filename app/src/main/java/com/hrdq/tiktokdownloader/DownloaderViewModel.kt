package com.hrdq.tiktokdownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val success: Boolean = false,
    val usedApi: String? = null
)

class DownloaderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun updateUrl(newUrl: String) {
        _uiState.value = _uiState.value.copy(
            url = newUrl,
            error = null,
            success = false,
            usedApi = null
        )
    }

    fun download(context: Context) {
        val link = _uiState.value.url.trim()
        if (link.isEmpty() || !isTikTokUrl(link)) {
            _uiState.value = _uiState.value.copy(
                error = context.getString(R.string.invalid_url_error)
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                success = false,
                progress = context.getString(R.string.trying_api_progress)
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    getNoWatermarkUrlWithFallback(link)
                }

                if (result == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.all_apis_failed_error)
                    )
                    return@launch
                }

                val (videoUrl, apiName) = result
                _uiState.value = _uiState.value.copy(
                    progress = context.getString(R.string.downloading_via_progress, apiName),
                    usedApi = apiName
                )

                startDownload(context, videoUrl)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    success = true,
                    progress = context.getString(R.string.download_started_progress, apiName)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: context.getString(R.string.unknown_error, e.localizedMessage ?: "")
                )
            }
        }
    }

    private fun isTikTokUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") ||
                lower.contains("vm.tiktok.com") ||
                lower.contains("vt.tiktok.com") ||
                lower.contains("tiktokv.com")
    }

    private fun getNoWatermarkUrlWithFallback(tiktokUrl: String): Pair<String, String>? {
        val apis = listOf(
            ::tryTikwm,
            ::tryTikwmGet,
            ::trySlBjsWorker,
            ::tryAlternativeTikwm
        )

        for (api in apis) {
            try {
                val result = api(tiktokUrl)
                if (result != null) return result
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun tryTikwm(url: String): Pair<String, String>? {
        val formBody = FormBody.Builder()
            .add("url", url)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url("https://www.tikwm.com/api/")
            .post(formBody)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optInt("code") != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val video = data.optString("hdplay").ifEmpty {
                data.optString("play")
            }
            if (video.isNotBlank()) return video to "tikwm.com"
        }
        return null
    }

    private fun tryTikwmGet(url: String): Pair<String, String>? {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://www.tikwm.com/api/?url=$encoded&hd=1")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optInt("code") != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val video = data.optString("hdplay").ifEmpty { data.optString("play") }
            if (video.isNotBlank()) return video to "tikwm.com (GET)"
        }
        return null
    }

    private fun trySlBjsWorker(url: String): Pair<String, String>? {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url("https://tdownv4.sl-bjs.workers.dev/?down=$encoded")
            .get()
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val video = json.optString("download_url")
                .ifEmpty { json.optString("video") }
                .ifEmpty { json.optString("play") }
                .ifEmpty { json.optString("url") }

            if (video.isNotBlank() && video.startsWith("http")) {
                return video to "sl-bjs worker"
            }
        }
        return null
    }

    private fun tryAlternativeTikwm(url: String): Pair<String, String>? {
        val formBody = FormBody.Builder()
            .add("url", url)
            .add("count", "12")
            .add("cursor", "0")
            .add("web", "1")
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url("https://tikwm.com/api/")
            .post(formBody)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Referer", "https://www.tikwm.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optInt("code") != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val video = data.optString("hdplay").ifEmpty { data.optString("play") }
            if (video.isNotBlank()) return video to "tikwm mirror"
        }
        return null
    }

    private fun startDownload(context: Context, videoUrl: String) {
        val fileName = "TikTok_${System.currentTimeMillis()}.mp4"
        val request = DownloadManager.Request(Uri.parse(videoUrl))
            .setTitle(context.getString(R.string.notification_download_title))
            .setDescription(context.getString(R.string.notification_download_desc))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "TikTokDownloader/$fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "Mozilla/5.0")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }
}
