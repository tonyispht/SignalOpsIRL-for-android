# Signal OPS IRL — User Guide

Open this folder in Android Studio (Koala or newer, JDK 17). Let Gradle sync, plug in a phone
(a real device is required; emulators have no BLE radio), and press Run.

- Watchlist: names (partial, case-insensitive) or MAC addresses, saved on-device.
- Continuous scan: low-latency scan until you stop it.
- Burst scan: scan for 3-15 s, then pause 10-120 s, repeat.
- Alerts: synthesized beep sound, vibration, flashing red screen border (in-app), and a notification.
- Runs in a foreground service. Caveat: Android 8.1+ may stop delivering results to an unfiltered scan (this app scans
  unfiltered) while the screen is off, so test on your phone; keeping the screen on is the reliable way.
- Scan failures retry (continuous mode every 10 s), and turning Bluetooth off stops the scan and shows an error.
- The Signals list refreshes about 3 times a second rather than on every advertisement.

## Layout (1.4)
- **Tabs:** the bar at the bottom switches between **SCAN** (radar, start/stop, Signals), **DEX** (the Signal Dex),
  **TRACKERS** (trackers heard around you) and **SETTINGS**. Badges show new Dex entries, flagged trackers and updates.
- **First-run tour:** a short walkthrough on first launch; replay it in **Settings > ABOUT**.
- **Signals bar:** search by name, maker, type or address; **FILTERS** opens: new-only, sort (signal, newest, name), and
  chips for pinned, named, alert / possible tier and every device type in the list. Pinned devices (\u2605) stay at the top.
- **Device detail:** tapping a signal opens one screen with what it is and why, who made it, a live signal graph of
  the last 3 minutes, all its details, and every action: LOCATE, CONNECT, NAME IT, PIN, + NAME / + MAC TARGET,
  WATCH FOR..., RAW DATA and MARK AS MINE.
- **Locator:** LOCATE opens a full-screen hot/cold gauge with the Geiger-style ticks; **FULL SCREEN** on the tracker card
  opens it again.
- **Trackers tab:** each tracker shows how long it has been around, how far you moved, and a timeline of the minutes it
  was heard in the last hour, with LOCATE and MINE. A tracker alert banner also has MINE and VIEW.
- **Signal Dex:** every device type has a rarity (common to legendary) with progress per rarity, and locked types show
  example device names to look for.
- **Daily quests:** new quests for exact earbud/speaker models, smart-home devices, trackers and sensors.
- **Session report:** after STANDBY (scans of 20 s or more) a report of new types, rare finds, models and XP, with SHARE.
  Turn it off in **Settings > DISPLAY**.
- **Notification:** while scanning it shows live counts (devices, trackers, alerts) with **Mute** (silences alert sounds
  and vibration for this scan) and **Stop**.
- **Targets:** **PICK FROM SEEN** in the target list adds names or addresses from devices already heard.
- **Sensors:** each decoded sensor shows a small temperature (or humidity) graph of the last 2 hours.
- **Settings:** split into pages (ALERTS, SCANNING, LIST, DISPLAY, SECURITY, DATA, ABOUT, ALL) with a search box. Long
  explanations open with the \u24D8 button. **TEXT SIZE** (Settings > DISPLAY) makes all text larger.

## Terms of responsible use
The first time the app opens it shows the **Terms of Responsible Use** before anything else. Nothing scans (and the boot
sequence, auto-start scan and login calendar do not run) until you tick **I have read and agree to these terms** and press
**ACCEPT**. **DECLINE** (or Back) closes the app. Acceptance is stored on the device with a version number; raise
`TERMS_VERSION` in `Terms.kt` after changing the wording and everyone is asked to accept again. Read them again any time in
**Settings > ABOUT > TERMS OF USE**. **Settings > ABOUT > SUPPORT THE DEVELOPER** opens the Buy Me a Coffee page in your browser. Accepting also switches **AUTO-IDENTIFY UNNAMED** off (it can be turned back on).

## Draw over other apps
Turn on "Flashing border over other apps" in the Alert protocol card. Android sends you to a
settings screen to grant "Display over other apps". The overlay appears only when Signal OPS IRL is not
on screen, ignores touches, and clears itself after 10 s (or when you open the app).
Use "Test alert" to preview it.

## Target list backup / import
Open **Settings > Target list**, then use **EXPORT** to save every target to a plain text file (one per line) and
**IMPORT** to merge a file back in. Duplicates are skipped and lines starting with `#` are ignored.

## Keep screen on
**Settings > SCREEN > KEEP ON WHILE SCANNING** stops the display from sleeping while a scan is running (it is released
when you abort). Handy because Android may stop delivering scan results once the screen is off.

## Start-up scan and signal colours
- **Settings > START > SCAN WHEN APP OPENS** (on by default): the scan starts by itself when the app is opened, right after the boot sequence
  (the permission prompts appear the first time). It only happens once per launch; abort and it stays stopped until you start it again or reopen the app.
  With a USB adapter selected it still needs the USB permission popup the first time.
- **Signal bars** are coloured like a heat map: each lit bar has its own colour, from cold blue (lowest bar) through cyan, green and amber
  to hot red (the top bar), so a strong signal lights the whole spectrum and a weak one only the cool end. The hot/cold tracker uses the same colours.

## Naming devices
Tap a signal and choose **NAME IT** (or **RENAME**) to give a device your own name (up to 40 characters). The name is saved on the phone
and is still there after you restart the app. It replaces the device's own name in the Signals list (shown in the accent colour), the
hot/cold locator, the Sensors card and alert banners/notifications, and a named device is never grouped into a `×N` row or hidden by
**ONE ROW PER NAME**. The tap menu shows both your name and the advertised one. It does not change **target matching**, which still
uses the advertised name and address. The **Device names** section under **Settings** lists every saved name with its address and **EDIT** / **X** buttons, including devices that are
not around right now.
Names are saved per Bluetooth address. Devices with a fixed address keep their name forever. Devices that use a random, rotating address
keep their name while the app runs (it moves along when the address changes, see below), but a restart can give them a new address that needs naming again.

## Finding more devices: BLE 5 extended adverts
**Settings > RADIO > BLE 5 EXTENDED ADVERTS** (on by default). Android's scanner only reports legacy (BLE 4.x) advertisements unless the scan
explicitly asks for everything, so devices that advertise with BLE 5 *extended* adverts (many newer earbuds, trackers, smart-home and Matter
devices) were never heard. With this on:
- **Phone radio:** the scan uses `setLegacy(false)` with all PHYs. If the phone's Bluetooth refuses (feature unsupported) the app steps down
  automatically: first without the PHY option, then to the old legacy-only scan.
- **USB adapter:** the adapter is switched to extended scanning (1M and Coded PHY, which adds long range), the extended-report event is enabled and
  extended reports (including ones split into several pieces) are reassembled. If the adapter doesn't support it, it falls back to legacy scanning.
- A line under the status shows the mode in use (for example `PHONE: LEGACY + EXTENDED, ALL PHYS  EXT 412  CODED 3`). **EXT** counts the extended
  advertisements received; if it stays at 0 in a busy area, your radio is not delivering them.
- Restart the scan after changing the switch. Turn it off to go back to the previous behaviour.

## Rotating addresses and duplicate names
**Settings > LIST** has two switches, both on by default.
- **MERGE ROTATING ADDRESSES**: phones, watches, trackers and many other devices swap their random Bluetooth address every few
  minutes, so one device used to show up as a stream of new ones. The app fingerprints the part of each advertisement that does not
  change with the address (which fields it sends and their lengths, service UUIDs, company ID, Tx power, name, Apple message type; rolling
  bytes like counters and keys are ignored). When a new random address appears with a compatible fingerprint, a similar signal
  strength (within 15 dB) and an earlier address of that pattern has just gone quiet (within 2 minutes), the new address is treated as
  the same device: one row, marked with `↻N` (N addresses seen), inherited name, one alert per device instead of one per address,
  and LOCATE keeps following the device when its address changes. If an old address comes back while its "successor" is still around, the link is
  undone (two devices that look alike) and those two stop being linked for 10 minutes. Public addresses (known manufacturer prefix) never rotate and are left alone.
  The tap menu lists the addresses linked to a device. The grouped `×N` rows for unnamed Find My style devices now count real devices, not addresses.
  This is a best guess from advertising patterns, so in a crowd of identical devices an occasional wrong link is possible; switch it off if it hides something you need.
- **ONE ROW PER NAME**: if several devices share exactly the same name, only the strongest one currently heard is listed, marked `≡N`
  (N devices with that name in total). The tap menu says how many are hidden.

## Telling unnamed devices apart (Apple phones, Find My accessories)
**Settings > LIST > GROUP UNNAMED DEVICES (x N)** is **off** by default. Before, every unnamed device of one kind (for example all anonymous Apple
devices) was folded into a single `Apple x4` row that showed only the strongest of them, so a second phone nearby was hidden. Now:
- Every unnamed device gets **its own row**, with a short tag after the name: `#12` when its addresses are linked (the number stays the same while
  the device rotates its address) or the last bytes of the address otherwise, e.g. `Apple  #12`, `Find My  4D:C2`.
- Unnamed Apple rows get a second line listing the Apple messages the device is sending (`APPLE: NEARBY INFO \u00B7 HANDOFF`), which also helps tell a
  phone from a watch or AirPods. The app now reads **every** Apple message in a packet, not only the first.
- **Find My:** each accessory uses its own address (the address comes from its public key), so two rows means two accessories heard at the same time.
- **Rotating-address linking is stricter for look-alikes.** Apple fingerprints now include each message's type and length, and when two earlier
  addresses fit a new one about equally well the app no longer guesses: the new address is shown as its own row and the old one fades out within about a minute.
  A wrong link hides a real device, a missed link only shows one extra row for a moment.
- In a crowd this means more rows. Turn the switch on to get the old grouped `x N` rows back.

## Advertising data viewer
Tap a signal and choose **ADV DATA** to see everything the device is broadcasting, live (about 2-3 updates a second):
the raw bytes (with a "decrypt" reveal when **MOVIE FX** is on), every AD structure with its type name and decoding
(flags, service UUIDs with names, local name, Tx power, appearance, connection interval, service data, manufacturer data with the
company name), and a **COPY HEX** button. Decoded beacon formats: iBeacon (UUID/major/minor/Tx), Eddystone UID/URL/TLM, and the
Apple continuity message types (Nearby Info, Proximity Pairing, Find My, Handoff...). With a USB adapter the advert and the scan response
arrive as separate packets and are both shown.

## Sensor readouts
Sensors that broadcast their measurements are decoded from the advertisement without connecting: Ruuvi tags (formats 3 and 5),
Xiaomi thermometers running ATC1441 or pvvx firmware, Govee thermometers (H5075-style and H5074, name must start with GVH/Govee),
SwitchBot meters, and Eddystone-TLM beacons. A one-line reading (e.g. `70.5F  45%RH  BAT 87%`; temperatures are in Fahrenheit) appears under the device in
**Signals**, and the **Sensors** card lists every sensor currently heard with all its values; tap one for its raw data.
Stock Xiaomi/Mijia sensors encrypt their data and are not readable.

## New-device sounds and alert
**Settings > NEW DEVICES**:
- **NEW-DEVICE SOUND** plays a short sound the first time each device is heard (it also needs the main SOUND switch on). Pick one of four with the
  chips, and tap a chip to hear it: **PING**, **HEARTBEAT** (a low lub-dub), **SONAR** (a ping with an echo) or **DATA** (four climbing blips).
  Sounds are limited to one every 0.3 s so a crowded scan start isn't a wall of noise.
- **ALERT ON NEW DEVICE** fires the full alert (sound, vibration, border flash, overlay and notification, titled "New device" / `NEW: name`) for any
  device first heard *after* the switch was turned on. Devices already in the list at that moment never alert. If the switch is already on when a scan
  starts, or after CLEAR, the first 8 s are learned silently so the whole neighbourhood doesn't alert at once. A device that was already given a target or
  tier alert is not alerted twice. Devices whose address rotates and that are linked by MERGE ROTATING ADDRESSES count as one device.
- **SKIP ANONYMOUS RANDOM** (on by default) leaves out unnamed, random-address devices that can't be linked (Find My style), since new ones appear
  constantly. Turn it off to hear and alert on literally everything.
- Distances in the list are shown in feet and sensor temperatures in Fahrenheit.

## Pairing check (Just Works)
**Settings > SECURITY > PAIRING CHECK** (on by default) marks devices that would *likely pair as Just Works*, meaning no PIN and no number to confirm.
Rows show `PAIRING: JUST WORKS (LIKELY)` (amber) or `PAIRING: OPEN` (red); tap a signal to see the reasons.
- **How it decides:** Bluetooth picks the pairing method from what both sides can show or type, and that is not advertised, so this is an estimate
  from what the device is. A connectable device with no display and no keyboard (sensor, tracker, earbuds, band, smart plug or lock...) can only
  do Just Works. Phones, computers, watches and keyboards (confirm / passkey), non-connectable beacons and devices of unknown type are not flagged.
  It also uses the advertised appearance, the *discoverable* flag (set = taking new pairings right now), and advertised out-of-band pairing data.
- **OPEN** means Just Works is likely and something worse showed up: a pairing key (Security Manager TK value) advertised in the clear, or, after you
  press **CONNECT**, identity data that could be read without pairing together with writable characteristics (writes are **not** tested).
- Nothing is sent to the device. It is a lead for your own audit, not proof: a device can still demand a PIN or use LE Secure Connections with OOB.

## Tracker-follow detection
**Settings > SECURITY > TRACKER-FOLLOW DETECTION** (on by default). While scanning, the app follows item trackers (Apple Find My accessories such as AirTags, Tile,
SmartTag and other "valuables" and pet trackers) and warns, with the normal full alert and a notification, when one keeps turning up around you.
- **FOLLOWING:** heard on and off for 10+ minutes and you have moved about 800 ft (250 m) since it was first heard. This needs the location permission
  (asked when you switch it on; the location is kept in memory only and never saved or sent anywhere). The scan service then also runs as a location
  service so this keeps working with the screen off. **Restart the scan after changing the setting.**
- **LINGERING:** heard for a long time but you have not moved (listed only). With no location permission it can't tell, so a tracker near you for 20+ minutes
  gives a lower-level warning that says so.
- The **Trackers** card lists what is being watched, with minutes, distance moved and signal. **MINE** (card or tap menu) marks your own tracker so it is never
  warned about; this is remembered.
- Limits: a tag near its owner changes its Bluetooth address about every 15 minutes, which restarts the count; an AirTag away from its owner keeps one address
  for about a day, which is the case this is built for. Phones in a pocket may not deliver scan results with the screen off (see the top of this file).
  Use it alongside, not instead of, the Apple/Google "unknown tracker" alerts built into your phone.

## Movie FX
**Settings > SCREEN > MOVIE FX** (on by default) turns on: a boot-up text sequence (tap to skip), CRT scanlines with a drifting refresh band and
vignette, glitching title and alert text, a closing "TARGET LOCK" reticle when an alert fires, a phosphor-style radar (blips flare as
the sweep passes, matched targets ping, compass ticks) and a heartbeat pulse on the hot/cold tracker whose speed follows the signal.
It also adds:
- **Data rain**: faint columns of hex falling over the screen while scanning. Each column is the real advertising bytes of a packet your phone
  just received, so busy air means heavier rain.
- **Oscilloscope strip** under the title: a scrolling trace that spikes with every packet (taller for a stronger signal) and shows packets per second.
- **CRT power on / off**: starting a scan powers the screen up (a bright line opens into the picture); stopping powers it down to a dot, flickers
  STANDBY, then brings it back up.
- **Signals list entry and exit**: a new device's row opens up and a bright edge wipes it into view; a device that disappears breaks into scan-line
  bars and fades out before the gap closes.

Data rain, the oscilloscope and the CRT power on/off each have their own switch under **MOVIE FX** in Settings > SCREEN
(all on by default; they only show while MOVIE FX is on).

Turn it off for a plain screen and less drawing.

## Auto-identify unnamed devices
Many devices advertise no name but do expose one over GATT (it is what DEVICE INFO shows). It is **off by default**. Turn on
**Settings > NAMES > AUTO-IDENTIFY UNNAMED** and, while scanning, the app connects to one unnamed device at a time
(about every 2.5 s, max 15 s per try) and reads its Device Name into the Signals list. There is no signal-strength cutoff:
devices not tried yet go first, then connectable ones, then the strongest. A device is tried again (up to 5 times, 2 minutes apart)
if it fails; devices never seen as connectable get one try; devices that answer without a name are not retried.
A name read by the manual **CONNECT** dialog is also added to the list. Found names are remembered, count for target-list
name matching and alerts, and survive CLEAR. Rotating-address devices (the grouped x N rows) are skipped, and it pauses while
hot/cold tracking or a device dialog is open. It uses the phone's Bluetooth even in USB scan mode and costs some battery.

## Tapping a signal: locate or connect
Tapping a row in **Signals** opens a small chooser:
- **LOCATE** starts hot/cold tracking (or **STOP LOCATING** if it is already tracked).
- **CONNECT** connects to the device over GATT using the phone's Bluetooth (Android 12+ asks for the "Nearby devices"
  permission the first time). It lists the device's services and characteristics and reads a short whitelist of
  identity values (device name, manufacturer, model, serial, firmware/hardware/software revision, battery level, Tx power),
  then disconnects. Many devices are not connectable or only answer a paired phone, so a failure there is normal; values that
  need pairing show "(needs pairing)". Rows with a count (x N, rotating addresses) can only be located.

## Hot / cold tracking
Tap any row in **Signals** and choose **LOCATE** to track that device. A tracker card appears at the top with a proximity meter,
HOT/WARM/COOL/COLD, and WARMER/COLDER/STEADY. While tracking, the app ticks faster and higher-pitched as the
signal strengthens (ticks keep playing with the screen off while the scan runs). Burst mode does not pause
while a device is being tracked. Choose STOP LOCATING in the chooser, or press STOP on the tracker card, to end. Devices that rotate their random
address will drop out and need to be tapped again.

## App updates
**Settings > App updates** shows the installed version. With **CHECK FOR UPDATES AUTOMATICALLY** on (default), the
app asks GitHub for the latest release when it opens (at most every 6 hours) and shows an **UPDATE AVAILABLE** card.
**UPDATE NOW** downloads the APK, checks its SHA-256 against the release's `update.json`, and hands it to Android,
which asks you to confirm. Your data and settings are kept. The first time, Android asks you to allow Signal OPS to
install apps (**ALLOW INSTALLS**). Android only accepts an update signed with the same key as the installed app.

Publishing: first raise `appVersionCode` and `appVersionName` at the top of `app/build.gradle.kts` (for example 140 /
"1.4.0") and merge that to `main`. Then on GitHub open **Releases > Draft a new release**, create the tag `v` +
the version name (`v1.4.0`) on `main`, and press **Publish**. The workflow refuses a tag that doesn't match. It builds
the signed APK and attaches it with `update.json`.

## Bluetooth database (built in, offline)
The app ships with a complete offline Bluetooth database in `app/src/main/assets/btdb/`, so identification works
from the first launch with no download and no internet:

| File | What it holds | Used for |
|---|---|---|
| `companies.tsv` | every Bluetooth SIG company identifier (about 4,000) | naming the maker from manufacturer data |
| `uuid16.tsv` | every 16-bit service UUID: SIG services, company (member) UUIDs, standards-body UUIDs | naming services, identifying by service owner |
| `appearance.tsv` | GAP appearance values (watch, keyboard, hearing aid, tag...) | the device type a device says it is |
| `oui.tsv` | the IEEE MAC prefix registry (MA-L, MA-M, MA-S; about 54,000) | the maker of devices with a public address |
| `fastpair.tsv` | Google Fast Pair model IDs (about 450 earbuds, headphones, speakers, watches) | exact product names; IDs used only by BLE spam tools are flagged |

**Settings > Bluetooth DB** shows the counts and the date of the lists. It is Bluetooth only (no Wi-Fi tables).
**UPDATE ONLINE** (optional) fetches newer IEEE prefixes on top, **IMPORT FILE** accepts an IEEE `oui.csv` /
`mam.csv` / `oui36.csv` or a Wireshark `manuf` file, and **REMOVE UPDATES** goes back to the built-in list.

To refresh the built-in lists before a release, run `python3 tools/build_bt_db.py` (and `tools/build_fastpair.py`
for the Fast Pair models) and rebuild.

Exact product names also come from AirPods / Beats model numbers, Samsung Galaxy Buds pairing adverts and Microsoft
Swift Pair display names. Fast Pair and Galaxy Buds models are mostly visible while the earbuds are in pairing mode
(case open, not yet connected).

**EXPORT UNIDENTIFIED** (Settings > Bluetooth DB) shares a JSON file listing the devices this session could not put
in a category, grouped by advert shape, with their company IDs, service UUIDs, appearance, payload hints and raw advert
bytes. It contains no full MAC addresses (only the maker prefix of public addresses), personal names such as
"Tony's" are replaced with [NAME], and names/addresses are removed from the raw bytes. Send it to whoever maintains
`scanner_config.json` so new rules can be written.

Addresses with the locally-administered bit set are labeled RANDOM. Many BLE devices (phones, watches, trackers)
use random addresses, so they will not resolve by MAC prefix; they are identified from their company ID, service
UUIDs (including service-data UUIDs), appearance and payload instead.

## Device identification (scanner_config.json)
`app/src/main/assets/scanner_config.json` is the same device database the desktop scanner script uses. Each
signal in **Signals** gets a tag line showing what it was identified as (category and evidence). Evidence is
checked in this order: category name terms (whole-word, in file order), name regexes, GATT service rules (advertised UUID lists and service-data UUIDs),
built-in payload rules (iBeacon, Apple continuity types, Google Find Hub trackers, Microsoft Swift Pair and
Connected Devices, Tile, Ruuvi, Eddystone), the advertised appearance or classic device class (`appearance_categories`),
weak MAC-prefix hints, vendor name rules, then just the maker: SIG company name, owner of a company service UUID,
or the IEEE owner of a public MAC address.
To update it, replace the file with a newer export of the config and rebuild.

Unnamed devices that rotate their MAC (Apple Find My, bare company IDs, generic services) are grouped into one row with a count (×N).
Scan mode, Target list and Bluetooth DB now live inside the **Settings** card. Signals show one line per device (name only);
tap a row to track it and see its MAC address and identification in the tracker card.

## Choosing the scanning adapter (external USB Bluetooth)
Open **Settings > Adapter**. **PHONE** uses the built-in Bluetooth. Android only exposes that one radio to apps, so an
external USB-C/OTG Bluetooth adapter is driven directly over USB (raw HCI): plug it in, press **REFRESH**, pick it, and
allow the USB permission popup. It scans with the same classifier, alerts and hot/cold tracking as the phone radio.

Realtek adapters such as the TP-Link UB500 (RTL8761B) have no firmware after being plugged in, so the app loads it at each
scan start. Press **IMPORT FW** and choose `rtl8761bu_fw.bin` (optionally **IMPORT CFG** with `rtl8761bu_config.bin`) from
`/usr/lib/firmware/rtl_bt/` on a Linux machine; if the files end in `.zst`, run `zstd -d` on them first. Progress and errors
appear under the status line. Only Realtek "Realtech"-format firmware is supported. Notes: the phone's Bluetooth can stay off in
USB mode; scanning is active (scan responses give names) and duplicates are not filtered, so RSSI updates work for tracking.

## Leveling game
Everything you do in Signal OPS IRL now earns XP. The bar under the title shows your level, equipped title and progress; tap it for the
**Operator profile** (stats, achievements and titles). Progress is saved on the phone and survives restarts.
- **XP:** +10 for each new device, +25 the first time a kind of device is identified, +50
  for a target-list hit, +30 for locating a device (tracked until it is stronger than -50 dBm), +15 for decoding a sensor or reading a
  name over GATT, +10 for naming a device, and +1 per minute of scanning. Level N needs 50 x N x (N-1) XP in total (100, 300, 600, 1000...).
- **Counting is fair:** a device with a public address counts once per address. Devices that rotate their random address count once per
  name, and unnamed random-address devices give no device XP, so changing addresses can't be farmed.
- **Achievements:** 20 of them (device counts, kinds identified, locating, naming, sensors, scan time). Each gives bonus
  XP and some unlock a title.
- **Titles:** six rank titles unlock at levels 1, 5, 10, 15, 25 and 40; eight more come from achievements. Equip one from the **Titles** tab
  (or tap the title banner when it unlocks). The locked ones show how to get them.
- **Effects:** level-ups, achievements, title unlocks, perfect days and streak milestones take over the whole screen: a flash, a glow,
  rotating rays, shock rings, confetti cannons from both bottom corners, a confetti rain, sparkles and a rising fanfare. The level number
  flips from the old level to the new one with a bump and the XP bar fills toward the next level. Achievements are colour-coded by
  value (gold, cyan = rare, magenta = legendary) and bigger ones throw more confetti. Finished quests and ordinary streak days are a
  card at the top with a smaller burst; it lets taps through. When several celebrations pile up they play shorter and **SKIP ALL**
  appears. Sounds and buzz start together with the animation. **Settings > SCREEN > GAME EFFECTS** turns the animations and their
  sounds off (XP still counts; unlocks are marked NEW in the profile). The sounds also follow the SOUND switch, the buzz follows VIBRATE.
- **Almost there:** every bar (XP, quests, achievements, streak milestone) starts to glow, shine and pulse at its leading edge once it is
  80% full. The XP bar also says `ALMOST! n XP TO LV x` and then `SO CLOSE!`. The Achievements tab has an **ALMOST THERE** list of the
  three closest achievements, and every unfinished counting achievement shows its own bar.
- **Reset:** the profile's PROFILE tab has RESET PROGRESS (tap twice).

**Not seeing the effects?** **Settings > TEST GAME FX** plays a sample achievement, level-up and quest card. The celebrations (and the target-lock
reticle) now run at normal speed even when Android's animation scale is off or "remove animations" is on; before, that setting made them finish instantly.
The radar sweep and pulsing bars still follow the system setting.

## Daily quests and streaks
The **DAILY QUESTS** card under the XP bar shows your streak, three progress bars (one per quest) and the quest that is closest to done.
Tap it for the **DAILY** tab of the profile.
- **Quests:** every day brings 3 quests, one easy (+40 XP), one medium (+80) and one hard (+150), picked from a pool by the date (the same
  all day, never the same quest two days in a row). Goals are things like finding devices, scanning for a number of minutes, identifying
  kinds of device, locating, naming, decoding sensors and target list hits, all counted for that day only (a device
  you found yesterday counts again today). A quest completes by itself the moment you reach the goal and pays right away.
- **Perfect day:** finishing all 3 pays +150 XP more.
- **Streak:** the first quest you finish each day counts the day, and the streak just adds up. **It can never be lost:** skipping days
  changes nothing (like the login days in RAID: Shadow Legends). Each streak day adds 2% to quest and perfect-day XP (max +50%).
  Milestones are at 3, 7, 14, 30 and 100 days, then every 100. A streak saved by an older version keeps its highest value.
- **Day change:** a day runs from **04:00 to 04:00** local time, so scanning after midnight counts for the day it started in.
- **Daily achievements:** 11 more achievements for daily play (quests done, perfect days, streak days 3, 7, 14, 30 and 100) and 6 more
  titles (DAILY GRINDER, QUEST MASTER, PERFECTIONIST, WEEK WARRIOR, IRON HABIT, CENTURION).
- **Reset:** RESET PROGRESS in the profile also clears the streak and today's quests.

## Login calendar and compact top card
- **Login calendar** (the gift button on the top card, and it opens by itself when the app starts with a reward waiting): a 28-day
  calendar like the login rewards in RAID: Shadow Legends. Tap COLLECT for today's reward; milestone days 7, 14, 21 and 28 pay
  +150, +300, +500 and +1,000 XP, ordinary days +25 to +40. Skipped days never reset it, and a new calendar starts after day 28.
  Login achievements: SHOWING UP (7 days), REGULAR (28, title) and LOYAL OPERATOR (100, title). The day changes at 04:00.
- The level / XP bar and the daily quests are now one two-line card at the top. Tap the top line for the profile, the second line
  for the DAILY tab.

## Alert sounds
**Settings > ALERT** has a row of sounds for target (and other full) alerts: BEEPS (the original), POLICE CHIRP (two quick siren yelps),
SIREN WAIL, KLAXON and RAPID PULSE. Tap one to hear it; it is also what TEST ALERT plays. All sounds are synthesized, no audio files.

## Battery percent and fullscreen
A tiny battery readout (for example `87%`, with a bolt while charging, amber at 30% or less, red at 15% or less) sits in the top right
corner of the main screen. **Settings > SCREEN > FULLSCREEN** hides the status and navigation bars; swipe from the screen edge to peek at
them. The battery readout stays visible either way.

## Removing targets from the main screen
On a signal row, a device that is already a target shows **-N** and/or **-MAC** (in red) instead of **+N** / **+MAC**. Tap one to remove
that target right there: -N removes the name target(s) matching this device's name, -MAC removes its exact MAC target. Devices matched
only by a MAC prefix still show +MAC; remove prefixes in Settings > Target list.

## Header, exit button, battery
The title row (SIGNAL OPS IRL, battery percent, red **X**) is pinned at the top and no longer scrolls with the list, so the battery readout never
covers the level card. **X** closes the app; while a scan is running it asks first, because exiting stops the scan.

## Target types (find devices by more than name and MAC)
**Settings > Target list** accepts these, one per line or comma-separated (type them or build them with **tap a signal > WATCH FOR...**,
which lists the name, MAC, prefix, company ID, service UUIDs, Apple message types, data patterns and appearance that device is actually
advertising, so one tap adds a target that also catches its siblings). Invalid entries are rejected with a message. The list shows each
entry's type on the right.

| Entry | Matches |
|---|---|
| `Some Name` | name contains the text (any case). `*` and `?` make it a whole-name wildcard: `Tile*`, `*cam?` |
| `AA:BB:CC:DD:EE:FF` / `00:25:DF` | exact address / address prefix |
| `00:25:??` , `MAC:*4D:C2` | address with `?` wildcard digits; a leading `*` matches the END of the address |
| `CID:004C` | company ID in the manufacturer data, as the Adv viewer shows it (0x004C = Apple). Catches every device of that company, rotating addresses included |
| `COMPANY:espressif` | company by name (from the built-in company table); `COMPANY:0x02E5` also works |
| `UUID:FE9F` | service UUID (4 hex = 16-bit, 8 = 32-bit, 32 = 128-bit, dashes allowed). Matches UUID lists, solicitations and service data. `UUID:0000FE*` = prefix wildcard |
| `MFG:004C:1005` | manufacturer data of that company starting with these bytes (after the company ID). `??` = any byte, `4?` = any 0x40-0x4F. A leading `*` finds the bytes anywhere in the payload. `MFG:*:AABB` = any company |
| `SVCDATA:FEAA` , `SVCDATA:FEAA:10??` | service data for that UUID, optionally starting with a byte pattern |
| `ADV:0201061AFF4C00` | raw advertising bytes contain this pattern anywhere (start with `^` to anchor at the beginning); catches anything the other types can't |
| `APPLE:10` , `APPLE:AIRPODS` | Apple message type seen (hex, or AIRPODS, NEARBY, HANDOFF, FINDMY, IBEACON, AIRDROP, AIRPLAY, HOMEKIT...) |
| `APPEARANCE:0340` | GAP appearance value in the advert (`034?` wildcards allowed) |
| `VENDOR:espressif` | address owner from the OUI database, or the advertised company name, contains the text |
| `CLASS:flipper` | what the built-in device database identified it as (label, category or reason contains the text) |
| `NAME:` `EXACT:` `STARTS:` `ENDS:` `REGEX:` | name rules: contains / whole name / starts with / ends with / regular expression (no commas or semicolons in a regex) |

**Combine with `&`** (all must match, spaces around it) and **negate with `!`**: `CID:004C & APPLE:10 & !NAME:iPhone`.
**Signal gate**: add `@-60` at the end to match only when the signal is at least that strong (closer than about -60 dBm):
`CID:004C @-60`. A bare `@-40` alerts for any device that close.

Notes
- The scan is unfiltered, so every advertisement is already received; these rules only decide what counts as a target and alerts.
- Byte-pattern rules (`MFG:` with bytes, `SVCDATA:`, `ADV:`, `APPEARANCE:`) match against the packets heard from a device; a hit is kept for 20 s
  because the next packet from the same device may not repeat those bytes. Company ID, UUID, Apple type and name rules use everything heard so far.
- Entries that contain `*` or `?` and are not a MAC pattern are now whole-name wildcards (before they were matched as literal text).
- Hex patterns need whole bytes (an even number of digits); spaces, `:` and `-` are ignored.

## Classic Bluetooth discovery
**Settings > RADIO > CLASSIC BLUETOOTH DISCOVERY** (on by default). The BLE scan only hears devices that *advertise*. Headsets, speakers, car kits,
older phones and PCs often use classic (BR/EDR) Bluetooth and only answer an inquiry, so they never showed up. With this on, the scan service
also runs the phone's classic discovery (about 12 s per round, restarted while scanning goes on) and feeds the results into the same Signals list:
- Rows are tagged `[BT]` (classic only) or `[LE+BT]` (the same address was heard both ways). The scan line shows `CLASSIC n` (a `*` while a round is running).
- The tap menu shows the device's class (for example `AUDIO/VIDEO · HEADPHONES`). CONNECT is hidden for classic-only devices (it is a BLE/GATT feature);
  LOCATE (hot/cold) works from the signal strength.
- Targets: `TRANSPORT:CLASSIC` (or `BLE` / `BOTH`), `COD:HEADPHONES`, `COD:AUDIO`, `COD:PHONE`, `COD:KEYBOARD`, or hex such as `COD:0418` / `COD:04??`.
  WATCH FOR... offers them for classic devices.
- Costs: more battery, and the BLE scan can be a little slower while a round runs. A device must be *discoverable* to answer; many are only
  discoverable in pairing mode. In burst mode it only runs during scan windows, so use a scan time of 15 s or more. It uses the phone's own radio, also with a USB adapter selected.
- Restart the scan after changing the switch.

## Hidden devices counter and RAW mode
Under **Signals** a line shows `HEARD n · SHOWN n · HIDDEN n` with the reasons: `MERGED` (addresses linked as one rotating device), `GROUPED`
(unnamed devices in an `x N` row), `SAME NAME` (hidden behind a stronger device with the same name), `NEW FILTER`, `LIST LIMIT` (the list is capped at 40 rows),
and `CLEANED UP` (old devices the service dropped to save memory; these are gone, not just hidden). The Signals header also shows `HIDDEN n` when collapsed.

**RAW** (button in the Signals header, or **Settings > LIST > SHOW EVERYTHING RAW**) turns all of that off: no address merging, no grouping, no
one-row-per-name, no NEW filter, no clean-up of old devices (a memory cap of 5000 addresses remains), and up to 500 rows. Every address heard gets its own row, so
a rotating phone shows up as a stream of rows; that is the point of the mode. Turn it off again for the tidy list.

Still not controllable from the app: on Android 12+ the manifest marks `BLUETOOTH_SCAN` as `neverForLocation`, and Android then filters some BLE
beacon adverts (iBeacon / Eddystone style) out of scan results before the app sees them.

## PIN lock for the target list
**Settings > Target list** can be locked with a 4-8 digit PIN (**SET PIN** inside the section). With a PIN set, opening the section asks for it,
and the list, its import/export and the PIN controls stay hidden until it is entered. It locks again when Settings is closed. Wrong guesses are
slowed down (3 tries, then a growing wait up to 5 minutes). Only a salted hash of the PIN is stored. The lock hides the list inside the app; it does
not encrypt the phone's storage. If the PIN is forgotten, only clearing the app's data removes it (and the list with it).

## Scanning with the screen off
**Settings > SCREEN > SCAN WITH SCREEN OFF** (on by default). Android 8.1+ stops delivering results of an unfiltered scan when the screen turns off,
so when the screen goes off the scan is restarted with filters built from your targets, and a partial wake lock keeps the CPU running while scanning.
When the screen turns on again it returns to the full scan.
- Filters can only match an exact address, exact name, company ID or service UUID. MAC targets, `CID:`/`MFG:`/`COMPANY:`/`APPLE:`, `UUID:`/`SVC:`/`SVCDATA:`, `EXACT:` map directly.
  A name-part target (`tile`) uses every full name already heard that contains it, and a MAC prefix uses every address already heard with it,
  so a device that was never seen with the screen on cannot be found by a name part alone. `ADV:` byte patterns and `REGEX:` cannot be filtered.
- Devices that already matched one of your targets are included too.
- The app's own matcher still decides every packet, so alerts behave as before. New-device alerts cannot work with the screen off.
- The status shows `SCREEN OFF: N FILTERS`, or a warning if nothing could be filtered.
- Also use **ALLOW** under the switch so Android's battery optimization does not stop the service. USB adapters are not affected (they scan the same way either way).

## Compact status card and speed readout
- **Status card:** the radar, status, counts and the scan button now take about half the height. Line 1 shows `SCANNING / STANDBY / OFFLINE`
  and the tracked / target counts. Tap it (the small triangle) to show or hide the full scan description. Line 2 holds the radio counters
  (`EXT`, `CODED`, `CLASSIC`). Errors and notes still appear in full.
- **Signal counters:** under the Signals header the old two-line amber text is one line, `HEARD 14 · SHOWN 7 · HIDDEN 7`. Tap it to see what hid
  the rest (merged, grouped, same name, new filter, list limit), the clean-up count and the usage hint.
- **Speed (Settings > SHOW SPEED (MPH), on by default):** the current speed in mph is always shown on line 2 of the status card (`-- MPH` until the
  GPS has a speed, `0 MPH` when standing still, dimmed unless you are moving). **Tap it** for a full-screen seven-segment speed display; **tap
  anywhere** (or Back) to close it. The screen stays on while it is open. Around the digits it also shows the scan state (top left), the phone's
  battery percent, a battery icon and charging state (top right: charging with AC / USB / wireless, full, on battery), and live counts:
  **ACTIVE 10S** (devices heard in the last 10 seconds), **TRACKED**, **TARGETS** (target-list devices heard in the last minute, red when any) and
  **PKT/S**. A tracker warning line appears when tracker-follow has flagged something.
  **Swipe left or right** to change the color of the digits (cyan, green, red, yellow, magenta, blue, white, orange; one dot per color shows
  where you are). Keep the finger moving to step through several. The choice is remembered. A plain tap closes the screen.
  The speed comes from the GPS (smoothed, ignored when the GPS says it is unreliable; under 2 mph reads as 0). It needs the location permission
  (the switch asks for it, the same one tracker-follow uses) and a running scan. To save battery the GPS is asked once a second only while the
  screen is on; tracker-follow keeps its own slower updates. The speed is never stored.
