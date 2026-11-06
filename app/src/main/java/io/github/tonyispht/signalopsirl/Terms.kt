package io.github.tonyispht.signalopsirl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Terms of responsible use. Shown before anything else on first launch; nothing scans until they are accepted.
 * Raise [TERMS_VERSION] whenever the wording changes so every user is asked to accept the new version.
 */
const val TERMS_VERSION = 1

/** Opened by Settings > ABOUT > SUPPORT THE DEVELOPER. */
const val SUPPORT_URL = "https://buymeacoffee.com/Tonyispht"

private const val TERMS_TITLE = "SIGNAL OPS IRL — TERMS OF RESPONSIBLE USE"

private const val TERMS_INTRO =
    "Signal OPS IRL is an entertainment application that turns nearby Bluetooth activity into a game. " +
        "It is intended for recreational, educational, and personal-security use only. " +
        "Please read the following before continuing."

private val TERMS_SECTIONS = listOf(
    "1. Intended use" to
        "You agree to use this app only as a game and as a tool for learning about Bluetooth and checking your own " +
        "surroundings and devices. It is not designed or certified for professional, investigative, or " +
        "safety-critical purposes.",
    "2. Respect for others' privacy" to
        "You will not use this app to locate, follow, monitor, or identify any person without their knowledge and " +
        "consent, or to harass, intimidate, or harm anyone. Device names and signals may reveal information about " +
        "their owners; treat anything you observe with discretion.",
    "3. Devices you do not own" to
        "Features such as Connect, Auto-Identify, Device Info, and Pairing Check interact with or assess other " +
        "devices. You agree to connect to, probe, or test only devices you own or have explicit permission to " +
        "examine, and you will never attempt to pair with, access, or interfere with another person's device.",
    "4. Compliance with the law" to
        "You are solely responsible for making sure your use of this app complies with all laws and regulations " +
        "that apply where you are, including those on privacy, wireless communications, and computer access. " +
        "You will not use the app to obstruct, evade, or interfere with law enforcement, emergency services, or " +
        "any lawful activity.",
    "5. Accuracy and limitations" to
        "Device identification, classifications, distance estimates, and security indicators are approximations " +
        "based on publicly broadcast data and may be incomplete or incorrect. Do not rely on them for decisions " +
        "affecting your safety or anyone else's. If you believe you are being tracked or are in danger, contact " +
        "local authorities.",
    "6. Safe play" to
        "Stay aware of your surroundings. Do not use the app while driving, or in any way that puts you or others " +
        "at risk.",
    "7. No warranty" to
        "This app is provided \"as is,\" without warranty of any kind. To the fullest extent permitted by law, the " +
        "developer is not liable for any misuse of the app or any damages arising from its use."
)

private const val TERMS_CLOSING =
    "By selecting ACCEPT, you confirm that you have read, understood, and agree to follow these terms. " +
        "If you do not agree, select DECLINE and the app will close."

/** The terms text itself, shared by the first-launch gate and the Settings viewer. */
@Composable
private fun TermsBody(showClosing: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(TERMS_INTRO, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurface)
        TERMS_SECTIONS.forEach { (heading, body) ->
            Column {
                Text(
                    heading.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, color = cs.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurface)
            }
        }
        if (showClosing) {
            Text(TERMS_CLOSING, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, color = cs.secondary)
        }
    }
}

/**
 * Full-screen first-launch gate. Covers the whole app and swallows touches so nothing underneath can be used.
 * ACCEPT only works once the box is ticked; DECLINE (or Back) closes the app.
 */
@Composable
fun TermsGate(onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var agreed by remember { mutableStateOf(false) }
    BackHandler { onDecline() }
    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            // Blocks taps from reaching the screen behind the gate.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "// $TERMS_TITLE", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = cs.primary
            )
            Text(
                "PLEASE REVIEW AND ACCEPT TO CONTINUE", fontSize = 9.sp, letterSpacing = 1.sp,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, cs.outline), HudShape)
                    .padding(12.dp)
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TermsBody(showClosing = true)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckBoxHud(agreed)
                Text(
                    "I have read and agree to these terms.",
                    fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.padding(start = 10.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GateButton("DECLINE", cs.error, enabled = true, modifier = Modifier.weight(1f), onClick = onDecline)
                GateButton("ACCEPT", cs.primary, enabled = agreed, modifier = Modifier.weight(1f), onClick = onAccept)
            }
        }
    }
}

/** Read-only copy of the terms, opened from Settings > ABOUT > TERMS OF USE. */
@Composable
fun TermsDialog(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = cs.surface,
        shape = HudShape,
        title = {
            Text("// TERMS OF RESPONSIBLE USE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TermsBody(showClosing = false)
                Text(
                    "ACCEPTED VERSION $TERMS_VERSION", fontSize = 9.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = { MiniButton("CLOSE") { onClose() } }
    )
}

@Composable
private fun CheckBoxHud(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    val shape = CutCornerShape(3.dp)
    Box(
        Modifier
            .size(18.dp)
            .background(if (on) cs.primaryContainer else Color.Transparent, shape)
            .border(1.dp, if (on) cs.primary else cs.outline, shape),
        contentAlignment = Alignment.Center
    ) {
        if (on) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.primary)
    }
}

@Composable
private fun GateButton(text: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = if (enabled) color else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .border(BorderStroke(1.dp, c), BtnShape)
            .background(if (enabled) c.copy(alpha = 0.12f) else Color.Transparent, BtnShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = c)
    }
}
