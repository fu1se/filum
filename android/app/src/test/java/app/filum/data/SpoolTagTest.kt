package app.filum.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpoolTagTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // эталоны посчитаны независимо: openssl enc -aes-128-ecb -nopad
    @Test fun sectorKeyFromUid() {
        val uid = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals("f6a06716fd3c", hex(SpoolTag.sectorKey(uid)))
    }

    @Test fun encodeAndEncrypt() {
        val tag = SpoolTag(materialId = "01001", color = "#ff6a14", lengthM = 330, serial = "000042")
        val text = tag.encode()
        assertEquals("AB1240276A21010010FF6A14033000004200000000000000", text)
        assertEquals(
            "57f25b78076d4c1797b1be35ca269540301222ac8182fba9f64ae1429416f404cefae28957f93f68897987d2d7c63fe8",
            hex(SpoolTag.encrypt(text)),
        )
        assertEquals(text, SpoolTag.decrypt(SpoolTag.encrypt(text)))
    }

    @Test fun decode() {
        val t = SpoolTag.decode("AB1240276A21U19130112233024700012300000000000000")!!
        assertEquals("U1913", t.materialId)
        assertEquals("#112233", t.color)
        assertEquals(750, t.weightG)
        assertEquals("000123", t.serial)
        assertEquals("0276", t.vendorCode)
        assertNull(SpoolTag.decode("\u0000".repeat(48)))
    }
}
