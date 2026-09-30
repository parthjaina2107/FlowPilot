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
