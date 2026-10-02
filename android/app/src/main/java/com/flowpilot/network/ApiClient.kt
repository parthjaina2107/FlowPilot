package com.flowpilot.network

import com.flowpilot.BuildConfig
import com.flowpilot.model.FlowGraph
import com.flowpilot.model.MatchResult
import com.flowpilot.model.RecordingTrace
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * FlowPilot Backend API interface.
 */
interface FlowPilotApi {

    // --- Health ---
    @GET("/")
    suspend fun healthCheck(): Map<String, String>

    // --- Flows CRUD ---
    @GET("/api/flows")
    suspend fun listFlows(): List<Map<String, String>>

    @GET("/api/flows/{flowId}")
    suspend fun getFlow(@Path("flowId") flowId: String): FlowGraph

    @DELETE("/api/flows/{flowId}")
    suspend fun deleteFlow(@Path("flowId") flowId: String): Map<String, Any>

    // --- Generalise ---
    @POST("/api/generalise/compile")
    suspend fun compileTrace(@Body trace: RecordingTrace): FlowGraph

    // --- Match ---
    @POST("/api/match/text")
    suspend fun matchTextCommand(@Body body: Map<String, String>): MatchResult

    @Multipart
    @POST("/api/match/audio")
    suspend fun matchAudioCommand(@Part audio: MultipartBody.Part): MatchResult

    @Multipart
    @POST("/api/match/audio/partial")
    suspend fun matchAudioPartial(@Part audio: MultipartBody.Part): Map<String, Any>
}

/**
 * Singleton Retrofit client for the FlowPilot backend.
 */
object ApiClient {

    private var customBaseUrl: String? = null
    private var cachedApi: FlowPilotApi? = null

    val gson: Gson by lazy {
        GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // Gemini calls can be slow
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()
    }

    fun setBaseUrl(newUrl: String) {
        val clean = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        customBaseUrl = clean
        cachedApi = null
    }

    fun getBaseUrl(): String = customBaseUrl ?: BuildConfig.BACKEND_URL

    val api: FlowPilotApi
        get() {
            if (cachedApi == null) {
                cachedApi = Retrofit.Builder()
                    .baseUrl(getBaseUrl())
                    .client(okHttp)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
                    .create(FlowPilotApi::class.java)
            }
            return cachedApi!!
        }
}

// ─────────────────────────────────────────────
// Phase 7: Structured Network Diagnostics
// ─────────────────────────────────────────────

enum class NetworkErrorType {
    CONNECTION_REFUSED,
    TIMEOUT,
    UNKNOWN_HOST,
    CLEAR_TEXT_BLOCKED,
    NETWORK_UNAVAILABLE,
    HTTP_4XX,
    HTTP_5XX,
    MALFORMED_RESPONSE,
    UNKNOWN
}

data class NetworkDiagnostic(
    val errorType: NetworkErrorType,
    val userMessage: String,
    val technicalDetails: String,
    val backendUrl: String,
    val endpoint: String
)

object NetworkDiagnostics {
    fun diagnose(e: Throwable, endpoint: String): NetworkDiagnostic {
        val url = ApiClient.getBaseUrl()
        val msg = e.message ?: ""
        val errorType = when {
            e is java.net.ConnectException || msg.contains("ECONNREFUSED", ignoreCase = true) ->
                NetworkErrorType.CONNECTION_REFUSED
            e is java.net.SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ->
                NetworkErrorType.TIMEOUT
            e is java.net.UnknownHostException ->
                NetworkErrorType.UNKNOWN_HOST
            msg.contains("CLEARTEXT", ignoreCase = true) || msg.contains("cleartext", ignoreCase = true) ->
                NetworkErrorType.CLEAR_TEXT_BLOCKED
            e is retrofit2.HttpException && e.code() in 500..599 ->
                NetworkErrorType.HTTP_5XX
            e is retrofit2.HttpException && e.code() in 400..499 ->
                NetworkErrorType.HTTP_4XX
            else -> NetworkErrorType.UNKNOWN
        }

        val userMessage = when (errorType) {
            NetworkErrorType.CONNECTION_REFUSED ->
                "FlowPilot cannot reach the local AI server.\nMake sure the backend is running on your computer (0.0.0.0:8000)."
            NetworkErrorType.CLEAR_TEXT_BLOCKED ->
                "Local HTTP connection is blocked by Android security settings."
            NetworkErrorType.TIMEOUT ->
                "The AI server took too long to respond."
            NetworkErrorType.UNKNOWN_HOST ->
                "Cannot resolve backend host. Check server IP configuration."
            NetworkErrorType.HTTP_5XX ->
                "The AI server returned an error (5xx)."
            NetworkErrorType.HTTP_4XX ->
                "Request was rejected by the AI server (4xx)."
            NetworkErrorType.NETWORK_UNAVAILABLE ->
                "Device has no internet or local network connection."
            else ->
                "AI server communication failed: ${e.localizedMessage ?: "Unknown error"}"
        }

        android.util.Log.e("FlowPilot", """
            [NETWORK]
            backendUrl=$url
            endpoint=$endpoint
            exceptionType=${e.javaClass.simpleName}
            exceptionMessage=${e.message}
            classifiedError=$errorType
        """.trimIndent())

        return NetworkDiagnostic(
            errorType = errorType,
            userMessage = userMessage,
            technicalDetails = "${e.javaClass.simpleName}: ${e.message}",
            backendUrl = url,
            endpoint = endpoint
        )
    }
}

// ─────────────────────────────────────────────
// Phase 9: Safe Network Retry Logic
// ─────────────────────────────────────────────

suspend fun <T> safeNetworkCall(
    endpoint: String,
    maxRetries: Int = 2,
    initialDelayMs: Long = 600,
    block: suspend () -> T
): Result<T> {
    var lastException: Throwable? = null
    var delayMs = initialDelayMs
    for (attempt in 0..maxRetries) {
        try {
            val result = block()
            return Result.success(result)
        } catch (e: Throwable) {
            lastException = e
            // Do not retry 4xx client errors
            if (e is retrofit2.HttpException && e.code() in 400..499) {
                break
            }
            if (attempt < maxRetries) {
                android.util.Log.w("FlowPilot", "[NETWORK] Attempt ${attempt + 1} failed for $endpoint, retrying in ${delayMs}ms...", e)
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
        }
    }
    return Result.failure(lastException ?: Exception("Network request failed after retries"))
}

