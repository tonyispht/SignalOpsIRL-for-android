# Optional target lists

Ready-made target lists for Signal OPS IRL. None of them are loaded by default. Import the ones you want.

| List | What it finds | Typical use |
|---|---|---|
| [`security_sweep_devices.txt`](security_sweep_devices.txt) | Hacking gadgets (Flipper Zero, Proxmark, HackRF...), ESP32 attack firmware, BLE spam, and the Bluetooth modules often hidden in card skimmers | Sweeping your home, car, office or event space; auditing your own gear |
| [`tracker_devices.txt`](tracker_devices.txt) | AirTag, SmartTag, Tile, Chipolo and other item trackers | Checking your car and belongings for trackers you didn't put there |

## How to import

1. Download the `.txt` file to your phone. On GitHub, open the file and use **Download raw file**.
2. In the app, open **Settings > Target list** and press **IMPORT**, then pick the file.
3. Entries are merged into your list. Duplicates are skipped and lines starting with `#` are ignored.

Use **EXPORT** first if you want a backup of your current list.

## Good to know

- **A match is a lead, not proof.** Names match partially and case-insensitively, so common words can match unrelated
  devices, and many of these chips (ESP32, HC-05) are used in harmless hobby projects too.
- **Skimmers:** if you think you've found one at a gas pump or ATM, don't touch it. Tell the business and local police.
- **Trackers:** your own will match too, so use **MARK AS MINE** on them. If you find one you don't recognise and you feel
  unsafe, contact local authorities.
- Every list falls under the app's [Terms of Responsible Use](../README.md#terms-of-responsible-use). Never use them to
  obstruct, evade or interfere with law enforcement, or to track anyone.

Got a device that should be on a list? Open an issue or a pull request.
