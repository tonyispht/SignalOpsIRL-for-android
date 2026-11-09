package io.github.tonyispht.signalopsirl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The TRACKERS tab: item trackers heard around you, with a sighting timeline and MINE / LOCATE buttons. */
@Composable
fun TrackersContent(
    trackerOn: Boolean,
    locationOk: Boolean,
    running: Boolean,
    trackers: List<TrackerStatus>,
    mineCount: Int,
    onEnable: () -> Unit,
    onGrantLocation: () -> Unit,
    onMine: (TrackerStatus) -> Unit,
    onLocate: (TrackerStatus) -> Unit,
    onClearMine: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Warns when an AirTag, Tile, SmartTag or other item tracker keeps showing up around you, which is how " +
                    "someone could follow you with a tag slipped into a bag or car. FOLLOWING = still around after you " +
                    "moved 800 ft or more. LINGERING = near you for 20+ minutes. Mark your own tags as MINE.",
                fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (!trackerOn) {
            item {
                HudCard(border = cs.tertiary) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tracker-follow detection is off.", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.tertiary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            MiniButton("TURN ON", cs.tertiary) { onEnable() }
                        }
                    }
                }
            }
        } else if (!locationOk) {
            item {
                HudCard(border = AlertAmber) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Location is off, so it can only flag trackers that stay near you for 20+ minutes.",
                            fontSize = 12.sp, color = AlertAmber, modifier = Modifier.weight(1f))
                        MiniButton("ALLOW") { onGrantLocation() }
                    }
                }
            }
        }
        if (trackerOn && trackers.isEmpty()) {
            item {
                Text(
                    if (running) "No trackers have been around you for long. Keep scanning; trackers show up here after a couple of minutes."
                    else "Start a scan on the SCAN tab to watch for trackers.",
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
        items(trackers, key = { it.key }) { t -> TrackerCard(t, onMine, onLocate) }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Marked as mine: $mineCount", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (mineCount > 0) MiniButton("UNMARK ALL", cs.error) { onClearMine() }
            }
        }
    }
}

@Composable
private fun TrackerCard(t: TrackerStatus, onMine: (TrackerStatus) -> Unit, onLocate: (TrackerStatus) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lc = when (t.level) {
        TrackLevel.FOLLOWING -> AlertRed
        TrackLevel.LINGERING -> AlertAmber
        else -> cs.secondary
    }
    HudCard(border = lc) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(t.level.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = lc)
            }
            Text(
                "Around you for ${t.minutes} min · " +
                    (if (t.meters >= 0) "you moved ${distanceText(t.meters)}" else "no location") +
                    " · ${t.rssi} dBm",
                fontSize = 12.sp, color = cs.onSurfaceVariant
            )
            SightingTimeline(t, lc)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MiniButton("LOCATE", cs.secondary) { onLocate(t) }
                MiniButton("MINE", cs.primary) { onMine(t) }
            }
        }
    }
}

/** The last 60 minutes as a strip: a tick for every minute the tracker was heard. */
@Composable
private fun SightingTimeline(t: TrackerStatus, color: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    val nowMin = System.currentTimeMillis() / 60_000L
    val marks = t.minuteMarks.filter { nowMin - it in 0..59 }.toSet()
    Column {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val w = size.width / 60f
            drawRect(cs.outline.copy(alpha = 0.25f), size = size)
            for (m in marks) {
                val x = (59 - (nowMin - m)) * w
                drawRect(color, topLeft = Offset(x + 0.5f, 2f), size = Size((w - 1f).coerceAtLeast(1f), size.height - 4f))
            }
        }
        Row {
            Text("60 min ago", fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("heard in ${marks.size} of the last 60 min", fontSize = 10.sp, color = cs.onSurfaceVariant)
            Box(Modifier.weight(1f)) { Text("now", fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterEnd)) }
        }
    }
}
