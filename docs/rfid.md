# RFID tags for spools

Creality spools carry an RFID tag. When a spool goes into a CFS box, the CFS reads the tag and fills in the slot
by itself: material, vendor, color and length. Filum can write such tags. Stick one on any spool and the CFS
recognizes it like a Creality spool.

## What you need

- **Tags:** MIFARE Classic 1K, 13.56 MHz, the common cheap kind sold as "MIFARE S50", "UID / CUID" stickers, cards
  or key fobs. Thin stickers (25–30 mm) fit the side of a spool best.
  - **NTAG** stickers (NTAG213/215/216) **won't work**: the CFS reads only MIFARE Classic.
- **Phone:** Android with NFC that supports MIFARE Classic. Not every NFC chip does. Phones with NXP chips do,
  including recent Pixels (Pixel 8 confirmed: it reports `com.nxp.mifare`). Some Samsung and other phones don't.
  If yours doesn't, Filum says so when you tap a tag.

## Writing a tag

1. **Turn on NFC** on the phone.
2. **Open the dialog.** Go to Control → Spools → **RFID tag**. Or open a slot and tap **RFID tag**: the dialog
   then starts with that slot's filament and color.
3. **Choose the spool settings:** the vendor, type and filament from the printer's library, then the color and the
   spool weight.
4. **Write the tag.** Tap **Write tag** and hold the tag to the back of the phone, near the NFC antenna. The tag is
   written, locked with the Creality key and read back to check.
5. **Stick it on the spool.** Put the tag on the side of the spool, near the rim, so it passes the CFS reader when
   the spool turns.

You can rewrite a tag any number of times. **Read tag** shows what a tag holds, and **Use these settings** copies it
into the editor. **Erase tag** makes it blank again with factory keys.

## Which filaments work

The CFS takes only the **material id** from the tag. The printer then looks up vendor, name, type and temperatures
in its own material library. So:

- **Library filaments.** Anything in the printer's library works: Creality, Generic, Polymaker, eSUN and the
  others.
- **Your own filaments.** These work once [material-sync](../printer/material-sync/README.md) has added them to the
  library. Filum shows them with ★.
- **Other printers.** A tag for a filament that another printer doesn't have in its library shows up there as an
  unknown material.

## Tag format

For reference, and for people building their own tools:

- **Chip.** MIFARE Classic 1K. Only sector 1 is used (blocks 4–6 data, block 7 trailer).
- **Data.** 48 ASCII characters, AES-128-ECB encrypted with the key `H@CFkRnz@KAtBJp2`:

  | Chars | Field | Example |
  |---|---|---|
  | 0–4 | date code | `AB124` |
  | 5–8 | vendor code (`0276` = Creality) | `0276` |
  | 9–10 | batch | `A2` |
  | 11–16 | `1` + material id (`base.id` in `material_database.json`) | `101001` |
  | 17–23 | `0` + color `RRGGBB` | `0FF6A14` |
  | 24–27 | length in meters: 330 = 1 kg, 247 = 750 g, 198 = 600 g, 165 = 500 g, 82 = 250 g | `0330` |
  | 28–33 | serial | `000042` |
  | 34–47 | reserved | `00000000000000` |

- **Keys.** Key A and key B of sector 1 are the first 6 bytes of AES-128-ECB(`q3bu^t1nqfZ(pf$1`, UID bytes 0–3
  repeated 4 times). Access bits are the default `FF 07 80 69`.
- **Firmware side.** In the firmware, `box_wrapper` takes characters 12–17 as the material id and checks it against
  the material library.

The format was worked out by the community, notably [DnG-Crafts/K2-RFID](https://github.com/DnG-Crafts/K2-RFID).
Filum's implementation is in [`SpoolTag.kt`](../android/app/src/main/java/app/filum/data/SpoolTag.kt) and
[`SpoolTagNfc.kt`](../android/app/src/main/java/app/filum/nfc/SpoolTagNfc.kt).
