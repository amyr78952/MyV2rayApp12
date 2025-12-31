package com.example.myv2rayapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myv2rayapp.data.TokenDataStore
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoToSignUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenDataStore = remember { TokenDataStore(context) }

    val colorScheme = MaterialTheme.colorScheme

    /* strings */
    val requiredFields = stringResource(R.string.required_fields)
    val loginSuccess = stringResource(R.string.login_success)
    val invalidCredentials = stringResource(R.string.login_failed_invalid_credentials)
    val networkError = stringResource(R.string.login_failed_network_error)
    val serverError = stringResource(R.string.login_failed_server_error)

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageColor by remember { mutableStateOf(colorScheme.error) }
    var loading by remember { mutableStateOf(false) }

    /* Background using Theme */
    val backgroundBrush = Brush.verticalGradient(
        listOf(
            colorScheme.background,
            colorScheme.surface
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Scaffold(
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Text(
                    text = stringResource(R.string.login_title),
                    fontSize = 32.sp,
                    color = colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary,
                        focusedLabelColor = colorScheme.primary,
                        cursorColor = colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colorScheme.primary,
                        focusedLabelColor = colorScheme.primary,
                        cursorColor = colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) {
                            message = requiredFields
                            messageColor = colorScheme.error
                            return@Button
                        }

                        loading = true
                        message = ""

                        val request = LoginRequest(username, password)

                        RetrofitClient.apiService.login(request)
                            .enqueue(object : Callback<TokenResponse> {
                                override fun onResponse(
                                    call: Call<TokenResponse>,
                                    response: Response<TokenResponse>
                                ) {
                                    loading = false
                                    if (response.isSuccessful && response.body()?.access != null) {
                                        val body = response.body()!!
                                        scope.launch {
                                            tokenDataStore.saveTokens(
                                                accessToken = body.access,
                                                refreshToken = body.refresh
                                            )
                                            message = loginSuccess
                                            messageColor = colorScheme.tertiary
                                            onLoginSuccess()
                                        }
                                    } else {
                                        message = parseLoginError(
                                            response.errorBody(),
                                            response.code(),
                                            invalidCredentials,
                                            serverError
                                        )
                                        messageColor = colorScheme.error
                                    }
                                }

                                override fun onFailure(call: Call<TokenResponse>, t: Throwable) {
                                    loading = false
                                    message = networkError
                                    messageColor = colorScheme.error
                                }
                            })
                    },
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Logging in...")
                    } else {
                        Text(stringResource(R.string.login_button))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        color = messageColor,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                TextButton(onClick = onGoToSignUp) {
                    Text(
                        text = stringResource(R.string.signup_link),
                        color = colorScheme.primary
                    )
                }
            }
        }
    }
}

/* ================= Error Parser ================= */

private fun parseLoginError(
    errorBody: ResponseBody?,
    code: Int,
    invalidCredentials: String,
    serverError: String
): String {
    return when (code) {
        400, 401 -> invalidCredentials
        in 500..599 -> serverError
        else -> invalidCredentials
    }
}
