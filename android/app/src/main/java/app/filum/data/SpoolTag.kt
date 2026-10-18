package app.filum.data

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * RFID tag of a Creality spool, as read by the CFS.
 *
 * The tag is a MIFARE Classic 1K. Sector 1 (blocks 4–6) holds a 48-character ASCII string, AES-128-ECB encrypted:
 *
 * | chars | field | example |
 * |---|---|---|
 * | 0–4 | date code | `AB124` |
 * | 5–8 | vendor code (`0276` = Creality) | `0276` |
 * | 9–10 | batch | `A2` |
 * | 11–16 | `1` + material id from `material_database.json` | `101001` |
 * | 17–23 | `0` + color RRGGBB | `0FF6A14` |
 * | 24–27 | length, meters | `0330` |
 * | 28–33 | serial | `000001` |
 * | 34–47 | reserved | `00000000000000` |
 *
 * Sector 1 is locked with key A = key B = the first 6 bytes of AES(UID repeated to 16 bytes). The printer's
 * `box_wrapper` takes the material id from chars 12–17 and looks it up in its material library, so any material
 * the library knows works, including those added by material-sync.
 */
data class SpoolTag(
    val materialId: String,
    /** «#RRGGBB». */
    val color: String,
    val lengthM: Int,
    val serial: String = randomSerial(),
    val vendorCode: String = CREALITY_VENDOR,
    val batch: String = "A2",
    val date: String = "AB124",
) {
    val weightG: Int? get() = WEIGHTS.entries.firstOrNull { it.value == lengthM }?.key

    fun encode(): String {
        require(materialId.length == 5) { "material id must be 5 characters: $materialId" }
        val rgb = color.removePrefix("#").uppercase()
        require(rgb.length == 6 && rgb.all { it.isDigit() || it in 'A'..'F' }) { "bad color $color" }
        val s = date.padEnd(5, '0').take(5) + vendorCode.padStart(4, '0').take(4) + batch.padEnd(2, '0').take(2) +
            "1" + materialId + "0" + rgb + lengthM.coerceIn(0, 9999).toString().padStart(4, '0') +
            serial.padStart(6, '0').take(6) + "0".repeat(14)
        check(s.length == 48)
        return s
    }

    companion object {
        const val CREALITY_VENDOR = "0276"

        /** Spool weight, g → length written on Creality tags, m. */
        val WEIGHTS = linkedMapOf(1000 to 330, 750 to 247, 600 to 198, 500 to 165, 250 to 82)

        fun lengthFor(weightG: Int): Int = WEIGHTS[weightG] ?: (weightG * 0.33).toInt()

        /** Parses a decrypted 48-character payload; null if it doesn't look like a spool tag. */
        fun decode(s: String): SpoolTag? {
            if (s.length < 34 || s[11] != '1') return null
            val rgb = s.substring(18, 24)
            if (!rgb.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
            return SpoolTag(
                date = s.substring(0, 5),
                vendorCode = s.substring(5, 9),
                batch = s.substring(9, 11),
                materialId = s.substring(12, 17),
                color = "#" + rgb.uppercase(),
                lengthM = s.substring(24, 28).toIntOrNull() ?: 0,
                serial = s.substring(28, 34),
            )
        }

        private fun randomSerial() = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')

        // ключи, общие для всех катушек Creality (публично известны, см. docs/rfid.md)
        private val UID_KEY = "q3bu^t1nqfZ(pf$1".toByteArray(Charsets.US_ASCII)
        private val DATA_KEY = "H@CFkRnz@KAtBJp2".toByteArray(Charsets.US_ASCII)

        private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)

        /** Key A/B of sector 1 for a tag with this UID. */
        fun sectorKey(uid: ByteArray): ByteArray {
            require(uid.size >= 4) { "UID too short" }
            val block = ByteArray(16) { uid[it % 4] }
            return aes(Cipher.ENCRYPT_MODE, UID_KEY, block).copyOf(6)
        }

        fun encrypt(payload: String): ByteArray {
            val bytes = payload.toByteArray(Charsets.US_ASCII)
            require(bytes.size == 48)
            return aes(Cipher.ENCRYPT_MODE, DATA_KEY, bytes)
        }

        fun decrypt(blocks: ByteArray): String = String(aes(Cipher.DECRYPT_MODE, DATA_KEY, blocks.copyOf(48)), Charsets.ISO_8859_1)
    }
}
