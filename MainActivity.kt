package com.sensorhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.auth.FirebaseAuth
import com.sensorhub.app.ui.CameraScreen
import com.sensorhub.app.ui.HomeScreen
import com.sensorhub.app.ui.HwSensorScreen
import com.sensorhub.app.ui.LocationScreen
import com.sensorhub.app.ui.LoginScreen
import com.sensorhub.app.ui.MicScreen
import com.sensorhub.app.ui.SensorHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SensorHubTheme { AppNav() }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val start = if (FirebaseAuth.getInstance().currentUser != null) "home" else "login"

    NavHost(navController = nav, startDestination = start) {
        composable("login") {
            LoginScreen(onDone = {
                nav.navigate("home") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                onOpen = { nav.navigate(it) },
                onLogout = {
                    FirebaseAuth.getInstance().signOut()
                    nav.navigate("login") { popUpTo("home") { inclusive = true } }
                },
            )
        }
        composable(
            "sensor/{idx}",
            arguments = listOf(navArgument("idx") { type = NavType.IntType }),
        ) { entry ->
            HwSensorScreen(index = entry.arguments?.getInt("idx") ?: -1, onBack = { nav.popBackStack() })
        }
        composable("gps") { LocationScreen(onBack = { nav.popBackStack() }) }
        composable("mic") { MicScreen(onBack = { nav.popBackStack() }) }
        composable("camera") { CameraScreen(onBack = { nav.popBackStack() }) }
    }
}
