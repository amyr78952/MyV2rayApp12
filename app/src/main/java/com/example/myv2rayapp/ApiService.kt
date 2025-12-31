package com.example.myv2rayapp

import com.google.gson.JsonElement
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    @POST("accounts/register/")
    fun register(@Body request: RegisterRequest): Call<ResponseBody>

    @POST("accounts/verify-email/")
    fun verifyEmail(@Body request: VerifyEmailRequest): Call<ResponseBody>

    @POST("accounts/resend-verification/")
    fun resendVerification(@Body request: ResendVerificationRequest): Call<ResponseBody>

    @POST("api/token/")
    fun login(@Body request: LoginRequest): Call<TokenResponse>

    @POST("api/token/refresh/")
    fun refreshToken(@Body request: RefreshTokenRequest): Call<TokenResponse>

    // ✅ Get user status (includes config_codes)
    @GET("accounts/status/")
    fun getStatus(@Header("Authorization") bearerToken: String): Call<StatusResponse>
}

/* ================= Data Classes ================= */

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val password2: String
)

data class VerifyEmailRequest(
    val email: String,
    val code: String
)

data class ResendVerificationRequest(
    val email: String
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access: String?,
    val refresh: String?
)

data class RefreshTokenRequest(
    val refresh: String
)

/* ===== Status response ===== */

data class StatusResponse(
    val username: String?,
    val email: String?,
    val coin_count: Long?,
    val config_codes: List<ConfigCodeItem>?
)

data class ConfigCodeItem(
    val config_code: String?,
    val server: String?,
    val gb_left: String?,
    val days_left: JsonElement? // ممکنه عدد یا "N/A" باشه
)
