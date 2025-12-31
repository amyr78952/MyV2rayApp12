package com.example.myv2rayapp

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*





sealed class MenuItem(val route: String, val titleRes: Int, val iconRes: Int) {
    object Profile : MenuItem("profile", R.string.menu_profile, R.drawable.ic_profile)
    object Help : MenuItem("help", R.string.menu_help, R.drawable.ic_help)
    object Plans : MenuItem("plans", R.string.menu_plans, R.drawable.ic_plans)
    object Coin : MenuItem("coin", R.string.menu_coin, R.drawable.ic_coin)
    object VPN : MenuItem("vpn", R.string.menu_vpn, R.drawable.ic_vpn)
}

private val menuItems = listOf(
    MenuItem.Profile,
    MenuItem.Help,
    MenuItem.Plans,
    MenuItem.Coin,
    MenuItem.VPN
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current

    // If permission needed, request once when entering VPN tab
    var vpnPermissionGranted by remember { mutableStateOf(false) }
    var askedOnce by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vpnPermissionGranted = (result.resultCode == Activity.RESULT_OK)
    }

    // observe current route
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // When route becomes VPN, request permission if needed
    LaunchedEffect(currentRoute) {
        if (currentRoute != MenuItem.VPN.route) return@LaunchedEffect

        val intent = VpnService.prepare(context)
        if (intent == null) {
            vpnPermissionGranted = true
            askedOnce = true
        } else {
            if (!askedOnce) {
                askedOnce = true
                vpnPermissionLauncher.launch(intent)
            }
        }
    }

    Scaffold(
        bottomBar = { BottomNavigationMenu(navController, isDarkTheme) },
        containerColor = Color.Transparent
    ) { paddingValues ->

        NavHost(
            navController = navController,
            startDestination = MenuItem.VPN.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(MenuItem.VPN.route) {
                // If permission not granted yet, show a simple placeholder
                if (!vpnPermissionGranted) {
                    PermissionGate(
                        onRetry = {
                            val intent = VpnService.prepare(context)
                            if (intent == null) {
                                vpnPermissionGranted = true
                            } else {
                                vpnPermissionLauncher.launch(intent)
                            }
                        }
                    )
                } else {
                    VPNConnectionScreen()
                }
            }

            composable(MenuItem.Coin.route) { CoinScreen() }
            composable(MenuItem.Plans.route) { PlansScreen() }
            composable(MenuItem.Help.route) { HelpScreen() }
            composable(MenuItem.Profile.route) { ProfileScreen(onLogout) }
        }
    }
}

@Composable
private fun BottomNavigationMenu(navController: NavHostController, isDarkTheme: Boolean) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val barColor = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFF2F2F2)

    NavigationBar(containerColor = barColor) {
        menuItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Image(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = stringResource(item.titleRes),
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (currentRoute == item.route)
                            ColorFilter.tint(Color(0xFF2196F3))
                        else null
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.titleRes),
                        textAlign = TextAlign.Center,
                        color = if (currentRoute == item.route) Color(0xFF2196F3) else Color.Gray
                    )
                }
            )
        }
    }
}

@Composable
private fun PermissionGate(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VPN Permission Required", fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Grant Permission") }
        }
    }
}

/* Temp pages */
@Composable
fun CoinScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Coin Balance Page", fontSize = 24.sp)
    }
}

@Composable
fun PlansScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Plans Page", fontSize = 24.sp)
    }
}

@Composable
fun HelpScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Help Page", fontSize = 24.sp)
    }
}

@Composable
fun ProfileScreen(onLogout: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Profile Page", fontSize = 24.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onLogout) { Text("Logout") }
        }
    }
}
