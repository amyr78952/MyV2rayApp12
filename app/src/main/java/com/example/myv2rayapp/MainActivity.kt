package com.example.myv2rayapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myv2rayapp.data.TokenDataStore
import com.example.myv2rayapp.ui.theme.MyV2rayAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tokenDataStore: TokenDataStore
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()

        tokenDataStore = TokenDataStore(this)

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted/denied */ }

        requestNotificationPermissionIfNeeded()

        setContent {
            MyV2rayAppTheme {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()

                var showSplash by remember { mutableStateOf(true) }
                var startDestination by remember { mutableStateOf("login") }

                LaunchedEffect(Unit) {
                    val accessToken = tokenDataStore.accessToken.first()
                    if (!accessToken.isNullOrBlank()) startDestination = "main"
                    delay(2000)
                    showSplash = false
                }

                if (showSplash) {
                    SplashContent()
                } else {
                    NavHost(navController = navController, startDestination = startDestination) {

                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = {
                                    navController.navigate("main") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                },
                                onGoToSignUp = { navController.navigate("signup") }
                            )
                        }

                        composable("signup") {
                            SignUpScreen(
                                onSignUpSuccess = { email -> navController.navigate("verify/$email") },
                                onBackToLogin = { navController.popBackStack() }
                            )
                        }

                        composable("verify/{email}") { backStackEntry ->
                            val email = backStackEntry.arguments?.getString("email") ?: ""
                            VerifyCodeScreen(
                                email = email,
                                onVerifySuccess = {
                                    navController.navigate("main") { popUpTo(0) { inclusive = true } }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("main") {
                            MenuScreen(
                                onLogout = {
                                    coroutineScope.launch {
                                        tokenDataStore.clearTokens()
                                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/* ================= Splash ================= */

@Composable
private fun SplashContent() {
    val darkTheme = isSystemInDarkTheme()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "App Logo",
                modifier = Modifier.size(140.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Lithium VPN",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (darkTheme) Color.White else Color.Black
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Secure & Fast Connection",
                fontSize = 18.sp,
                color = if (darkTheme) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f)
            )
        }
    }
}
