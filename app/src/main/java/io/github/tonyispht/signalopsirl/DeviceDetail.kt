package io.github.tonyispht.signalopsirl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Everything the device detail screen can do; the App wires each to its existing feature. */
class DeviceActions(
    val onClose: () -> Unit,
    val onLocate: () -> Unit,
    val onConnect: (() -> Unit)?,
    val onRename: (() -> Unit)?,
    val onRaw: () -> Unit,
    val onWatchFor: () -> Unit,
    val onPin: (() -> Unit)?,
    val onNameTarget: (() -> Unit)?,
    val onMacTarget: (() -> Unit)?,
    val onMine: (() -> Unit)?
)

/**
 * One screen with everything about a device: what it is, who made it, a live signal graph, the raw advert, and every
 * action (locate, connect, name, pin, targets, mark as mine). Replaces the old tap menu.
 */
@Composable
fun DeviceDetailScreen(
    d: SeenDevice,
    title: String,
    count: Int,
    linked: Int,
    sameName: Int,
    nick: String?,
    isTracked: Boolean,
    pinned: Boolean,
    pairCheck: Boolean,
    tracker: TrackerStatus?,
    nameTargetOn: Boolean,
    macTargetOn: Boolean,
    topPad: Dp,
    a: DeviceActions
) {
    val cs = MaterialTheme.colorScheme
    BackHandler { a.onClose() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(d.address) { while (true) { delay(1000); tick++ } }
    val live = remember(tick) { ScanBus.devices.value[d.address] ?: d }
    val series = remember(tick) { ScanBus.rssiSeries(d.address) }
    val vend = if (Vendors.isLocal(d.address)) null else Vendors.lookup(d.address)
    val cls = live.cls
    val tierColor = when (cls?.tier) {
        Tier.ALERT -> AlertRed
        Tier.POSSIBLE -> AlertAmber
        else -> cs.secondary
    }
    val age = ((System.currentTimeMillis() - live.lastSeen) / 1000L).coerceAtLeast(0L)

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(top = topPad).navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("// DEVICE", fontSize = 13.sp, color = cs.onSurfaceVariant, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            MiniButton("CLOSE") { a.onClose() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- identity ----
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (pinned) Text("  ★", fontSize = 18.sp, color = GameGold)
                }
                if (cls != null && cls.category != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${cls.icon} ${cls.title}".trim(), fontSize = 13.sp, color = tierColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.padding(start = 8.dp)) { RarityBadge(rarityOf(cls.category)) }
                    }
                }
                cls?.label?.takeIf { it != title }?.let { Text(it, fontSize = 13.sp, color = cs.onSurface) }
                cls?.let { Text("Why: " + it.reason, fontSize = 11.sp, color = cs.onSurfaceVariant) }
                if (cls?.tier == Tier.ALERT || cls?.tier == Tier.POSSIBLE) {
                    Text(if (cls.tier == Tier.ALERT) "ALERT TIER" else "POSSIBLE TIER", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = tierColor)
                }
                live.appleModel?.let { Text("Apple model: $it", fontSize = 12.sp, color = cs.secondary) }
                if (live.appleTypes.isNotEmpty() && 0x004C in live.companyIds) {
                    Text("Apple: " + AdDecoder.appleKinds(live.appleTypes) + (live.appleState?.let { " · $it" } ?: ""),
                        fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                nick?.let {
                    Text("Your name: $it", fontSize = 12.sp, color = cs.secondary)
                    live.name?.let { n -> Text("Advertised name: $n", fontSize = 11.sp, color = cs.onSurfaceVariant) }
                }
            }

            // ---- live signal ----
            HudCard(border = cs.outline) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SignalGauge(live.rssi)
                        Box(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(roughDistance(live.rssi), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(if (age < 3) "heard now" else "heard ${ageText(age * 1000)} ago", fontSize = 11.sp,
                                color = if (age < 5) cs.secondary else cs.onSurfaceVariant)
                        }
                    }
                    Sparkline(
                        series.map { it.second.toFloat() }, cs.primary,
                        Modifier.fillMaxWidth().height(70.dp), min = -100f, max = -30f
                    )
                    Text(
                        if (series.size < 2) "Signal graph fills in while the device is heard (last 3 minutes)."
                        else "Last ${((series.last().first - series.first().first) / 1000L).coerceAtLeast(1)} s · " +
                            "best ${series.maxOf { it.second }} · weakest ${series.minOf { it.second }} dBm",
                        fontSize = 11.sp, color = cs.onSurfaceVariant
                    )
                }
            }

            // ---- actions ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(if (isTracked) "STOP LOCATING" else "LOCATE", "Hot/cold finder", cs.secondary, Modifier.weight(1f)) { a.onLocate() }
                    a.onConnect?.let { ActionTile("CONNECT", "Read its details", cs.primary, Modifier.weight(1f)) { it() } }
                        ?: Box(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.onRename?.let { ActionTile(if (nick != null) "RENAME" else "NAME IT", "Your own name", cs.secondary, Modifier.weight(1f)) { it() } }
                        ?: Box(Modifier.weight(1f))
                    a.onPin?.let { ActionTile(if (pinned) "UNPIN" else "PIN ★", "Keep at the top", GameGold, Modifier.weight(1f)) { it() } }
                        ?: Box(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.onNameTarget?.let {
                        ActionTile(if (nameTargetOn) "- NAME TARGET" else "+ NAME TARGET", "Alert when this name appears",
                            if (nameTargetOn) cs.error else cs.primary, Modifier.weight(1f)) { it() }
                    } ?: Box(Modifier.weight(1f))
                    a.onMacTarget?.let {
                        ActionTile(if (macTargetOn) "- MAC TARGET" else "+ MAC TARGET", "Alert for this address",
                            if (macTargetOn) cs.error else cs.primary, Modifier.weight(1f)) { it() }
                    } ?: Box(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("WATCH FOR...", "Target by company, service or pattern", cs.primary, Modifier.weight(1f)) { a.onWatchFor() }
                    ActionTile("RAW DATA", "Packets and decoded fields", cs.tertiary, Modifier.weight(1f)) { a.onRaw() }
                }
                a.onMine?.let { ActionTile("MARK AS MINE", "My own tracker: stop warning about it", cs.secondary, Modifier.fillMaxWidth()) { it() } }
            }

            // ---- details ----
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("DETAILS", fontSize = 11.sp, color = cs.primary, letterSpacing = 1.sp)
                Detail("Address", d.address + if (Vendors.isLocal(d.address)) "  (random)" else "  (public)")
                vend?.let { Detail("Maker (MAC)", it) }
                live.companyIds.sorted().forEach { id -> Detail("Company ID", "0x%04X".format(id) + (Classifier.companyName(id)?.let { "  $it" } ?: "")) }
                if (live.services.isNotEmpty()) {
                    Detail("Services", live.services.sorted().take(8).joinToString("\n") { u ->
                        val s = BtDb.short16(u)
                        if (s >= 0) "0x%04X".format(s) + (BtDb.uuid16(s)?.let { "  ${it.name}" } ?: "") else u
                    })
                }
                if (live.transport and 2 != 0) {
                    Detail("Classic", (ClassicBt.text(live.cod) ?: "Classic Bluetooth") + if (live.transport == 3) " (also BLE)" else "")
                }
                ScanBus.rawAds[d.address]?.sensor?.let { sr -> Detail(sr.kind, sr.values.joinToString("   ") { "${it.first} ${it.second}" }) }
                if (count > 1) Text("$count similar unnamed devices are grouped in this row; this shows the strongest.",
                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                if (linked > 1) {
                    Text("Rotating address: $linked addresses linked by their advertising pattern. Locating follows the change.",
                        fontSize = 11.sp, color = cs.secondary)
                    ScanBus.identityAddrs[d.identity]?.take(6)?.forEach { Text(it, fontSize = 10.sp, fontFamily = Mono, color = cs.onSurfaceVariant) }
                }
                if (sameName > 0) Text("$sameName other device(s) with this exact name are hidden behind this one.",
                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                if (pairCheck && count == 1) {
                    val pv = PairingCheck.assess(live, ScanBus.rawAds[d.address])
                    Text(
                        when (pv.level) {
                            PairLevel.OPEN -> "Pairing: OPEN"
                            PairLevel.LIKELY -> "Pairing: Just Works likely (no PIN or confirmation)"
                            PairLevel.NONE -> "Pairing: no Just-Works sign"
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = when (pv.level) {
                            PairLevel.OPEN -> AlertRed
                            PairLevel.LIKELY -> AlertAmber
                            PairLevel.NONE -> cs.onSurfaceVariant
                        }
                    )
                    pv.reasons.forEach { Text("• $it", fontSize = 11.sp, color = cs.onSurfaceVariant) }
                }
                tracker?.let { t ->
                    Text("Tracker: ${t.level.name} for ${t.minutes} min", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (t.level == TrackLevel.FOLLOWING) AlertRed else AlertAmber)
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row {
        Text(label, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(0.34f))
        Text(value, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.weight(0.66f))
    }
}
