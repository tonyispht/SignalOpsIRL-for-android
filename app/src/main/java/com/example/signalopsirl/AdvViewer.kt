package com.example.signalopsirl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Live view of everything a device is advertising: decoded sensor values, the raw bytes, and each AD structure
 * with its decoding. Updates a few times a second while the dialog is open.
 */
@Composable
fun AdvDialog(addr: String, title: String, fx: Boolean, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val devices by ScanBus.devices.collectAsState()
    var ad by remember { mutableStateOf(ScanBus.rawAds[addr]) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(addr) {
        while (true) {
            ad = ScanBus.rawAds[addr]
            now = System.currentTimeMillis()
            delay(400)
        }
    }
    val clipboard = LocalClipboardManager.current
    val dev = devices[addr]
    val current = ad
    val allHex = current?.packets?.joinToString("\n") { AdDecoder.hex(it, 0, it.size) } ?: ""

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = cs.surface,
        shape = HudShape,
        title = {
            Text("// ADVERTISING DATA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(addr, fontSize = 10.sp, color = cs.onSurfaceVariant, fontFamily = Mono)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dev != null) {
                        SignalBars(dev.rssi, cs.primary)
                        Text("  ${dev.rssi} DBM", fontSize = 10.sp, color = cs.onSurfaceVariant)
                    }
                    if (current != null) {
                        val age = ((now - current.time) / 100L) / 10.0
                        Text(
                            "   PKTS ${current.count}   AGE ${String.format(Locale.US, "%.1f", age)}s",
                            fontSize = 10.sp, color = cs.onSurfaceVariant
                        )
                    }
                }

                if (current == null) {
                    Text(
                        "NO ADVERTISING DATA CAPTURED YET. KEEP SCANNING; THIS DEVICE MAY NOT HAVE BEEN HEARD " +
                            "SINCE THE LAST CLEAR.",
                        fontSize = 10.sp, color = cs.tertiary
                    )
                } else {
                    // ---- sensor readout ----
                    current.sensor?.let { sr ->
                        Column(
                            Modifier.fillMaxWidth()
                                .border(BorderStroke(1.dp, cs.secondary.copy(alpha = 0.7f)), BtnShape)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("SENSOR READOUT  \u00B7  ${sr.kind}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = cs.secondary, letterSpacing = 1.sp)
                            sr.values.forEach { (k, v) ->
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(k, fontSize = 10.sp, color = cs.onSurfaceVariant,
                                        modifier = Modifier.weight(0.38f))
                                    Text(v, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                                        color = cs.primary, modifier = Modifier.weight(0.62f))
                                }
                            }
                        }
                    }

                    // ---- raw bytes ----
                    Text("RAW DATA (${current.packets.size} PACKET${if (current.packets.size == 1) "" else "S"})",
                        fontSize = 10.sp, color = cs.primary, letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    current.packets.forEach { pk ->
                        DecryptText(
                            AdDecoder.hex(pk, 0, pk.size), animate = fx, color = cs.onSurface, fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Text("${pk.size} bytes", fontSize = 8.sp, color = cs.onSurfaceVariant)
                    }

                    // ---- decoded structures ----
                    Text("STRUCTURES", fontSize = 10.sp, color = cs.primary, letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    current.packets.forEachIndexed { idx, pk ->
                        if (current.packets.size > 1) {
                            Text("PACKET ${idx + 1}", fontSize = 8.sp, color = cs.onSurfaceVariant)
                        }
                        AdDecoder.fields(pk).forEach { f ->
                            Column(Modifier.padding(top = 3.dp)) {
                                Text("0x%02X  ${f.typeName.uppercase()}".format(f.type), fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, color = cs.secondary)
                                Text(f.hex, fontSize = 9.sp, fontFamily = Mono, color = cs.onSurfaceVariant)
                                f.decoded?.let { Text(it, fontSize = 10.sp, color = cs.onSurface) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (allHex.isNotEmpty()) {
                    MiniButton("COPY HEX", cs.secondary) { clipboard.setText(AnnotatedString(allHex)) }
                }
                MiniButton("CLOSE") { onClose() }
            }
        }
    )
}
