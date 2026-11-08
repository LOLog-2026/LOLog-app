package com.lolog.app.data.remote

import com.lolog.app.BuildConfig
import com.lolog.app.data.repo.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

val LologJson = Json {
    ignoreUnknownKeys = true   // 서버가 필드를 더 줘도 괜찮게
    coerceInputValues = true   // 모르는 상태값은 UNKNOWN으로
    explicitNulls = false      // PATCH에서 바꾸지 않은 값은 빼고 보냄
    encodeDefaults = true
}

/**
 * 서버 통신은 이것 하나만 써요(약속: 서현 쪽 통신 코드 하나, 지원의 녹음 업로드도 이걸 씀).
 * 통신 라이브러리·서버 주소(BuildConfig.LOLOG_BASE_URL)·토큰 붙이기·갱신이 모두 여기 있어요.
 */
class ApiClient(private val tokens: TokenStore) {
    private val jsonType = "application/json".toMediaType()
    private val baseHost = BuildConfig.LOLOG_BASE_URL.toHttpUrl().host

    /** /auth/signup·login·social·refresh 말고는 모두 토큰을 붙여요 */
    private val authHeader = Interceptor { chain ->
        val req = chain.request()
        val path = req.url.encodedPath
        val public = path.startsWith("/auth/") && !path.startsWith("/auth/me") && !path.startsWith("/auth/logout")
        // 만화 이미지(SeaweedFS 서명 주소)처럼 우리 서버가 아닌 곳에는 토큰을 붙이지 않아요
        val ours = req.url.host == baseHost
        // 로그인 전에는 local.properties의 시험용 토큰을 써요(있을 때만)
        val token = tokens.accessToken ?: BuildConfig.LOLOG_TEST_TOKEN.ifBlank { null }
        val next = if (ours && !public && token != null) {
            req.newBuilder().header("Authorization", "Bearer $token").build()
        } else req
        chain.proceed(next)
    }

    /**
     * 401 처리 (FR-AUTH-03, api-dto.md "TOKEN_EXPIRED: 앱은 이때만 갱신")
     *  - 401 TOKEN_EXPIRED → /auth/refresh로 새 토큰을 받아 한 번 더 시도. 갱신도 실패하면 "다시 로그인해 주세요"
     *  - 401 INVALID_TOKEN·UNAUTHORIZED(토큰이 틀림) → 갱신하지 않고 "다시 로그인해 주세요"
     *  - 로그인·가입·소셜·갱신 주소의 401(예: INVALID_CREDENTIALS)은 화면에서 그대로 보여 줘요
     */
    private val refresher = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val path = response.request.url.encodedPath
            val public = path.startsWith("/auth/") && !path.startsWith("/auth/me") && !path.startsWith("/auth/logout")
            if (public) return null
            if (response.request.header("X-Retried") != null) { tokens.expire(); return null }
            val code = runCatching {
                LologJson.decodeFromString(ErrorDto.serializer(), response.peekBody(4096).string()).code
            }.getOrDefault("")
            if (code != "TOKEN_EXPIRED") {
                if (code == "INVALID_TOKEN" || code == "UNAUTHORIZED") tokens.expire()
                return null
            }
            val usedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            synchronized(this) {
                // 다른 요청이 먼저 갱신했으면 그 새 토큰으로 다시 시도 (리프레시 토큰은 한 번 쓰면 바뀌어요)
                val now = tokens.accessToken
                if (now != null && now != usedToken) {
                    return response.request.newBuilder().header("Authorization", "Bearer $now").header("X-Retried", "1").build()
                }
                val refresh = tokens.refreshToken ?: run { tokens.expire(); return null }
                val body = LologJson.encodeToString(RefreshDto.serializer(), RefreshDto(refresh))
                val call = plainClient.newCall(
                    Request.Builder()
                        .url(BuildConfig.LOLOG_BASE_URL + "auth/refresh")
                        .post(body.toRequestBody(jsonType))
                        .build()
                )
                val res = runCatching { call.execute() }.getOrNull() ?: return null // 인터넷 끊김: 로그아웃하지 않음
                res.use {
                    if (!it.isSuccessful) { tokens.expire(); return null }
                    val t = LologJson.decodeFromString(TokenDto.serializer(), it.body!!.string())
                    tokens.save(t.accessToken, t.refreshToken)
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer ${t.accessToken}")
                        .header("X-Retried", "1")
                        .build()
                }
            }
        }
    }

    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val client = plainClient.newBuilder()
        .addInterceptor(authHeader)
        .authenticator(refresher)
        // 디버그에서도 주소·상태 코드·헤더까지만 남겨요. 본문에는 대화 글·카드 대사가 들어 있고,
        // 토큰은 로그에 남지 않게 가려요(logcat은 다른 앱·버그 리포트로 나갈 수 있어요).
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.LOLOG_BASE_URL)
        .client(client)
        .addConverterFactory(LologJson.asConverterFactory(jsonType))
        .build()

    val auth: AuthApi = retrofit.create(AuthApi::class.java)
    val main: MainApi = retrofit.create(MainApi::class.java)

    /** 만화 이미지 받기(저장·공유용). imageUrl은 10분짜리 서명 주소라 토큰을 붙이지 않아도 돼요 */
    fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).build()
        return runCatching { client.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }
}
