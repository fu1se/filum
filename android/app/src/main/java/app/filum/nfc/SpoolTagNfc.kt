package app.filum.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import app.filum.R
import app.filum.data.SpoolTag
import app.filum.tr

/** What a tag holds. */
sealed interface TagContent {
    val uid: String

    /** Factory-fresh: default keys, empty sector 1. */
    data class Blank(override val uid: String) : TagContent

    /** A Creality spool tag (written by Creality or by Filum). */
    data class Spool(override val uid: String, val tag: SpoolTag) : TagContent

    /** Readable but not a spool tag, e.g. used for something else. */
    data class Other(override val uid: String) : TagContent
}

class TagException(message: String) : Exception(message)

/**
 * MIFARE Classic I/O for Creality spool tags. All calls block: run them on the NFC reader thread.
 * Only sector 1 (blocks 4–7) is touched.
 */
object SpoolTagNfc {
    private const val SECTOR = 1
    private const val FIRST_BLOCK = 4
    private const val TRAILER = 7
    /** Default access bits (FF 07 80) + GPB 69: keys not readable, data read/write with key A or B. */
    private val ACCESS = byteArrayOf(0xFF.toByte(), 0x07, 0x80.toByte(), 0x69)

    fun read(tag: Tag): TagContent = withClassic(tag) { mfc, uid ->
        val key = SpoolTag.sectorKey(tag.id)
        when {
            mfc.authenticateSectorWithKeyA(SECTOR, key) -> {
                val text = SpoolTag.decrypt(readData(mfc))
                SpoolTag.decode(text)?.let { TagContent.Spool(uid, it) } ?: TagContent.Other(uid)
            }
            mfc.authenticateSectorWithKeyA(SECTOR, MifareClassic.KEY_DEFAULT) -> {
                val data = readData(mfc)
                if (data.all { it == 0.toByte() }) TagContent.Blank(uid)
                // незашифрованная метка — так некоторые программы пишут для проверки
                else SpoolTag.decode(String(data, Charsets.ISO_8859_1))?.let { TagContent.Spool(uid, it) } ?: TagContent.Other(uid)
            }
            else -> throw TagException(tr(R.string.rfid_err_locked))
        }
    }

    /** Writes [spool] and locks sector 1 with the Creality key, then reads it back to verify. */
    fun write(tag: Tag, spool: SpoolTag) = withClassic(tag) { mfc, _ ->
        val key = SpoolTag.sectorKey(tag.id)
        val fresh = when {
            mfc.authenticateSectorWithKeyA(SECTOR, key) -> false
            mfc.authenticateSectorWithKeyA(SECTOR, MifareClassic.KEY_DEFAULT) -> true
            else -> throw TagException(tr(R.string.rfid_err_locked))
        }
        val data = SpoolTag.encrypt(spool.encode())
        for (i in 0 until 3) mfc.writeBlock(FIRST_BLOCK + i, data.copyOfRange(i * 16, i * 16 + 16))
        if (fresh) mfc.writeBlock(TRAILER, key + ACCESS + key)
        if (!mfc.authenticateSectorWithKeyA(SECTOR, key) || !readData(mfc).contentEquals(data))
            throw TagException(tr(R.string.rfid_err_verify))
    }

    /** Back to a blank tag: zeros and default keys. */
    fun erase(tag: Tag) = withClassic(tag) { mfc, _ ->
        val key = SpoolTag.sectorKey(tag.id)
        val locked = when {
            mfc.authenticateSectorWithKeyA(SECTOR, key) -> true
            mfc.authenticateSectorWithKeyA(SECTOR, MifareClassic.KEY_DEFAULT) -> false
            else -> throw TagException(tr(R.string.rfid_err_locked))
        }
        for (i in 0 until 3) mfc.writeBlock(FIRST_BLOCK + i, ByteArray(16))
        if (locked) mfc.writeBlock(TRAILER, MifareClassic.KEY_DEFAULT + ACCESS + MifareClassic.KEY_DEFAULT)
    }

    private fun readData(mfc: MifareClassic): ByteArray =
        (0 until 3).fold(ByteArray(0)) { acc, i -> acc + mfc.readBlock(FIRST_BLOCK + i) }

    private fun <T> withClassic(tag: Tag, block: (MifareClassic, String) -> T): T {
        val mfc = MifareClassic.get(tag) ?: throw TagException(
            // SAK 0x08/0x18/0x09/0x88 — это MIFARE Classic, просто NFC-чип телефона его не поддерживает
            if (NfcA.get(tag)?.sak?.toInt()?.and(0xFF) in setOf(0x08, 0x18, 0x09, 0x88)) tr(R.string.rfid_err_phone_no_classic)
            else tr(R.string.rfid_err_wrong_type),
        )
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        return try {
            mfc.connect()
            mfc.timeout = 2000
            block(mfc, uid)
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException(tr(R.string.rfid_err_io, e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { mfc.close() }
        }
    }
}
