package com.momir.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.momir.android.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.syncPrinterState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBtPermissions()

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val scheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF88D4C2),
                    secondary = Color(0xFFA8C6FF),
                    tertiary = Color(0xFFFFD089),
                    background = Color(0xFF0E1625),
                    surface = Color(0xFF1A2537),
                    onPrimary = Color(0xFF06261D),
                    onSecondary = Color(0xFF0D254C),
                    onBackground = Color(0xFFE7EDF8),
                    onSurface = Color(0xFFE7EDF8),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF0B6E4F),
                    secondary = Color(0xFF1D3557),
                    tertiary = Color(0xFFFFB703),
                    background = Color(0xFFF4F8FF),
                    surface = Color(0xFFFFFFFF),
                    onPrimary = Color(0xFFFFFFFF),
                    onSecondary = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF142033),
                    onSurface = Color(0xFF142033),
                )
            }

            MaterialTheme(colorScheme = scheme) {
                MomirScreen(vm)
            }
        }
    }

    private fun requestBtPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MomirScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val darkTheme = isSystemInDarkTheme()
    val surfaceText = if (darkTheme) Color(0xFFE7EDF8) else MaterialTheme.colorScheme.onSurface
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val topSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
    val textMuted = if (darkTheme) Color(0xFFB9C7DC) else Color(0xFF475569)
    val statusText = if (darkTheme) Color(0xFFBFD9FF) else Color(0xFF3D5A80)
    val messageText = if (darkTheme) Color(0xFF9FD8C8) else Color(0xFF344E41)
    val sectionTitleText = if (darkTheme) Color(0xFFE7EDF8) else MaterialTheme.colorScheme.onSurface
    val cardBodyText = if (darkTheme) Color(0xFFDCE7F8) else MaterialTheme.colorScheme.onSurface
    val cardSurface = if (darkTheme) Color(0xFF1A2537).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f)
    val controlSurface = if (darkTheme) Color(0xFF182334).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.9f)
    val previewSurface = if (darkTheme) Color(0xFF141F31) else Color(0xFFFAFBFF)
    val previewBorder = if (darkTheme) Color(0xFF304460) else Color(0xFFD7DFEE)
    val primaryAction = if (darkTheme) Color(0xFF0A8E71) else Color(0xFF006D77)
    val primaryActionText = if (darkTheme) Color(0xFFEAFFF8) else Color.White
    val secondaryAction = if (darkTheme) Color(0xFF9FC3FF) else Color(0xFF1D3557)
    val secondaryActionText = if (darkTheme) Color(0xFFEAF2FF) else Color(0xFF1D3557)
    val headerGradient = Brush.linearGradient(
        colors = if (darkTheme) {
            listOf(Color(0xFF0B1422), Color(0xFF16233A), Color(0xFF223A5F))
        } else {
            listOf(Color(0xFF0D1B2A), Color(0xFF1B263B), Color(0xFF415A77))
        },
    )
    val pageGradient = Brush.verticalGradient(
        colors = if (darkTheme) {
            listOf(Color(0xFF0E1625), Color(0xFF132138), Color(0xFF162A2E))
        } else {
            listOf(Color(0xFFF4F8FF), Color(0xFFEDF2FF), Color(0xFFE8F5F1))
        },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("Control bootstrap and surprise level.", color = textMuted)

                    OutlinedButton(
                        onClick = {
                            vm.refreshAtomicJson()
                            scope.launch { drawerState.close() }
                        },
                        enabled = !ui.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Update Atomic JSON")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ui.includeFunny, onCheckedChange = { vm.setIncludeFunny(it) })
                        Text("Include Un-sets")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ui.showPreviewImage, onCheckedChange = { vm.setShowPreviewImage(it) })
                        Text("Show preview image")
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageGradient),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(topSafeInsets)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 8.dp,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .background(headerGradient)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Momir Printer",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            )

                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Open options menu",
                                    tint = Color.White,
                                )
                            }
                        }
                        Text(
                            text = "Roll creatures. Preview exactly what prints. Send over BLE.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (darkTheme) Color(0xFFDCE9FF) else Color(0xFFEAF2FF),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusPill(label = "Printer ${ui.printerState}")
                                if (!ui.printerName.isNullOrBlank()) {
                                    StatusPill(label = ui.printerName!!)
                                }
                                StatusPill(label = "Cards ${ui.totalCreatures}")
                            }

                        }

                        Button(
                            onClick = { vm.connectPrinter() },
                            enabled = !ui.loading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (darkTheme) Color(0xFFFFC857) else Color(0xFFFFB703),
                                contentColor = Color(0xFF111111),
                            ),
                        ) {
                            Text("Connect Printer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = controlSurface,
                    contentColor = surfaceText,
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Mana Value",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = sectionTitleText,
                        )

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0..16).toList()) { mv ->
                                FilterChip(
                                    selected = ui.selectedMv == mv,
                                    onClick = { vm.setMv(mv) },
                                    enabled = !ui.loading,
                                    label = { Text(mv.toString()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (darkTheme) Color(0xFF0A8E71) else Color(0xFF0B6E4F),
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.roll() },
                                enabled = !ui.loading,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryAction),
                            ) {
                                Text("Roll", color = primaryActionText, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { vm.printCurrentCard() },
                                enabled = ui.card != null,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, secondaryAction),
                            ) {
                                Text("Print", fontWeight = FontWeight.Bold, color = secondaryActionText)
                            }
                        }

                        Text(
                            text = ui.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = messageText,
                        )
                    }
                }
            }

                if (ui.showPreviewImage) {
                    ui.card?.let { c ->
                        item {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = cardSurface,
                                contentColor = surfaceText,
                                tonalElevation = 2.dp,
                                shadowElevation = 2.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = c.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = sectionTitleText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "MV ${c.manaValue} • ${c.type}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = statusText,
                                    )
                                    if (c.text.isNotBlank()) {
                                        Text(c.text, style = MaterialTheme.typography.bodyMedium, color = cardBodyText)
                                    }
                                    if (c.power.isNotBlank() && c.toughness.isNotBlank()) {
                                        Text(
                                            text = "${c.power} / ${c.toughness}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = sectionTitleText,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = previewSurface,
                    contentColor = surfaceText,
                    tonalElevation = 1.dp,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Print Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = sectionTitleText,
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, previewBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (!ui.showPreviewImage) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = "Preview hidden for fun",
                                        color = textMuted,
                                    )
                                }
                            } else {
                                ui.previewBitmap?.let { preview ->
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "Print preview",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp)),
                                    )
                                } ?: ui.imageUrl?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "Card image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp)),
                                    )
                                } ?: run {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 28.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "Roll a card to see preview",
                                            color = textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
            }
            }

            if (ui.bootstrapping) {
                BootstrapDialog(
                    title = if (ui.bootstrapMode == MainViewModel.BootstrapMode.INITIAL) "Preparing Database" else "Updating Database",
                    message = ui.message,
                )
            }
        }
    }
}

@Composable
private fun BootstrapDialog(title: String, message: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
