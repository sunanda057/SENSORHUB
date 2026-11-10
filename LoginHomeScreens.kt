package com.sensorhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.sensorhub.app.data.Cloud
import com.sensorhub.app.data.SensorRegistry

@Composable
fun LoginScreen(onDone: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var register by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sensor Hub", style = MaterialTheme.typography.headlineLarge)
        Text(
            if (register) "Create your account" else "Sign in to continue",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min 6 characters)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val e = email.trim()
                    if (e.isEmpty() || password.length < 6) {
                        error = "Enter a valid email and a password of at least 6 characters."
                        return@Button
                    }
                    error = null
                    loading = true
                    val auth = FirebaseAuth.getInstance()
                    val task = if (register) auth.createUserWithEmailAndPassword(e, password)
                    else auth.signInWithEmailAndPassword(e, password)
                    task.addOnCompleteListener {
                        loading = false
                        if (it.isSuccessful) {
                            Cloud.saveProfile()
                            onDone()
                        } else {
                            error = it.exception?.localizedMessage ?: "Authentication failed"
                        }
                    }
                },
            ) { Text(if (register) "Register" else "Login") }
            TextButton(onClick = { register = !register; error = null }) {
                Text(if (register) "Already have an account? Login" else "New user? Register")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val sensors = remember { SensorRegistry.load(ctx) }
    val connected by CloudStatus()
    val email = FirebaseAuth.getInstance().currentUser?.email ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensors") },
                actions = { TextButton(onClick = onLogout) { Text("Logout") } },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                InfoCard {
                    KeyValue("Signed in as", email)
                    KeyValue("Cloud database", if (connected) "Connected (live sync)" else "Offline (will sync later)")
                    KeyValue("Hardware sensors found", sensors.size.toString())
                }
            }
            item { SectionTitle("Phone inputs") }
            item { NavCard("GPS / Location", "Latitude, longitude, altitude, speed, accuracy") { onOpen("gps") } }
            item { NavCard("Microphone", "Live sound level in dB") { onOpen("mic") } }
            item { NavCard("Camera", "Preview, capture and store photos for every camera") { onOpen("camera") } }
            item { SectionTitle("Hardware sensors (${sensors.size})") }
            itemsIndexed(sensors) { i, s ->
                NavCard(s.title, "${s.meta.category} · ${s.sensor.vendor}") { onOpen("sensor/$i") }
            }
        }
    }
}
