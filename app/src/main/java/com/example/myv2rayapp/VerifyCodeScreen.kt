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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyCodeScreen(
    email: String,
    onVerifySuccess: () -> Unit,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    /* strings */
    val verifyTitle = stringResource(R.string.verify_title)
    val verifyDescription = stringResource(R.string.verify_description, email)
    val codeLabel = stringResource(R.string.code_label)
    val verifyButtonText = stringResource(R.string.verify_button)
    val resendCodeText = stringResource(R.string.resend_code)
    val backToSignupText = stringResource(R.string.back_to_signup)

    val invalidCode = stringResource(R.string.invalid_code)
    val emailVerified = stringResource(R.string.email_verified)
    val codeSent = stringResource(R.string.code_sent)
    val connectionError = stringResource(R.string.connection_error)
    val pleaseWait = stringResource(R.string.please_wait)

    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageColor by remember { mutableStateOf(colorScheme.error) }
    var loading by remember { mutableStateOf(false) }
    var resendLoading by remember { mutableStateOf(false) }

    /* Theme background */
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
                    text = verifyTitle,
                    fontSize = 32.sp,
                    color = colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = verifyDescription,
                    color = colorScheme.onBackground.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(40.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        if (it.length <= 6 && it.all(Char::isDigit)) code = it
                    },
                    label = { Text(codeLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedTextFieldColors()
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        if (code.length != 6) {
                            message = invalidCode
                            messageColor = colorScheme.error
                            return@Button
                        }

                        loading = true
                        message = ""

                        val request = VerifyEmailRequest(email, code)
                        RetrofitClient.apiService.verifyEmail(request)
                            .enqueue(object : Callback<ResponseBody> {
                                override fun onResponse(
                                    call: Call<ResponseBody>,
                                    response: Response<ResponseBody>
                                ) {
                                    loading = false
                                    if (response.isSuccessful) {
                                        message = emailVerified
                                        messageColor = colorScheme.tertiary
                                        onVerifySuccess()
                                    } else {
                                        message = invalidCode
                                        messageColor = colorScheme.error
                                    }
                                }

                                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                    loading = false
                                    message = "$connectionError"
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
                        Text(pleaseWait)
                    } else {
                        Text(verifyButtonText)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                TextButton(
                    onClick = {
                        resendLoading = true
                        message = ""

                        val request = ResendVerificationRequest(email)
                        RetrofitClient.apiService.resendVerification(request)
                            .enqueue(object : Callback<ResponseBody> {
                                override fun onResponse(
                                    call: Call<ResponseBody>,
                                    response: Response<ResponseBody>
                                ) {
                                    resendLoading = false
                                    message = if (response.isSuccessful) codeSent else connectionError
                                    messageColor =
                                        if (response.isSuccessful) colorScheme.tertiary
                                        else colorScheme.error
                                }

                                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                    resendLoading = false
                                    message = connectionError
                                    messageColor = colorScheme.error
                                }
                            })
                    },
                    enabled = !resendLoading
                ) {
                    if (resendLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(resendCodeText, color = colorScheme.primary)
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

                Spacer(modifier = Modifier.height(28.dp))

                TextButton(onClick = onBack) {
                    Text(backToSignupText, color = colorScheme.primary)
                }
            }
        }
    }
}

/* ================= Helpers ================= */

@Composable
private fun themedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary
)
