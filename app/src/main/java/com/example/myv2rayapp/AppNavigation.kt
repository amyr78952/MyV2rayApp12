package com.example.myv2rayapp

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.myv2rayapp.LoginScreen
import com.example.myv2rayapp.SignUpScreen
import com.example.myv2rayapp.VerifyCodeScreen  // <-- این خط رو اضافه کن
import com.example.myv2rayapp.MainScreen
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("main") {
                        popUpTo("login") { inclusive = true }  // stack رو پاک کن
                    }
                },
                onGoToSignUp = {
                    navController.navigate("signup") {
                        popUpTo("login") { inclusive = true }  // وقتی به ثبت‌نام می‌ری، لاگین پاک بشه
                    }
                }
            )
        }
        composable("signup") {
            SignUpScreen(
                onSignUpSuccess = { email -> navController.navigate("verify/$email") },
                onBackToLogin = { navController.navigate("login") { popUpTo("signup") { inclusive = true } } }
            )
        }

        composable("verify/{email}") { backStackEntry ->
            val email = backStackEntry.arguments?.getString("email") ?: ""
            VerifyCodeScreen(
                email = email,
                onVerifySuccess = {
                    // بعد از تأیید، اتوماتیک لاگین کن یا به اصلی برو
                    navController.navigate("main") { popUpTo(0) { inclusive = true } }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("main") {
            MainScreen(onLogout = {
                navController.navigate("login") {
                    popUpTo("main") { inclusive = true }  // خروج هم stack رو پاک کنه
                }
            })
        }
    }
}