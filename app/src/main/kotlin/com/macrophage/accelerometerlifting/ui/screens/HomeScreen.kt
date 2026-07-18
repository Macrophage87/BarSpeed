package com.macrophage.accelerometerlifting.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.macrophage.accelerometerlifting.ble.ConnectionState
import com.macrophage.accelerometerlifting.data.SessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, viewModel: HomeViewModel = viewModel()) {
    val sessions by viewModel.sessions.collectAsState()
    val imuState by viewModel.imuState.collectAsState()
    val hrmState by viewModel.hrmState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bar Speed") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionChip("IMU", imuState)
                ConnectionChip("HRM", hrmState)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { navController.navigate("record") }, modifier = Modifier.fillMaxWidth()) {
                Text("Record session")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { navController.navigate("devices") }, modifier = Modifier.weight(1f)) {
                    Text("Devices")
                }
                OutlinedButton(onClick = { navController.navigate("plans") }, modifier = Modifier.weight(1f)) {
                    Text("Plans")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions) { session ->
                    SessionCard(session) { navController.navigate("session/${session.id}") }
                }
            }
        }
    }
}

@Composable
fun ConnectionChip(label: String, state: ConnectionState) {
    val text =
        when (state) {
            is ConnectionState.Connected ->
                "$label ✓" + (state.batteryPct?.let { " $it%" } ?: "")
            is ConnectionState.Connecting -> "$label …"
            else -> "$label ✗"
        }
    AssistChip(onClick = {}, label = { Text(text) })
}

@Composable
private fun SessionCard(session: SessionEntity, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
    val started = Instant.ofEpochMilli(session.startedAtMs).atZone(ZoneId.systemDefault())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                session.planSessionName ?: "Ad-hoc session",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(formatter.format(started), style = MaterialTheme.typography.bodySmall)
            session.hrAvgBpm?.let {
                Text("HR avg $it bpm, max ${session.hrMaxBpm} bpm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
