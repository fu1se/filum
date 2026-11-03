package app.filum.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    @Test fun versions() {
        assertTrue(Versions.isNewer("0.2.2", "0.2.1"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("1.4", "1.3.9"))
        assertFalse(Versions.isNewer("0.2.1", "0.2.1"))
        assertFalse(Versions.isNewer("0.2", "0.2.0"))
        assertFalse(Versions.isNewer("1.0-beta", "1.0"))
        assertTrue(Versions.isNewer("1.0", ""))
    }

    @Test fun githubRelease() {
        val r = AppRelease.fromGitHub(JSONObject("""
            {"tag_name":"v0.2.2","body":"## Notes","html_url":"https://github.com/fu1se/filum/releases/tag/v0.2.2",
             "assets":[{"name":"checksums.txt","browser_download_url":"x"},
                       {"name":"filum-v0.2.2.apk","size":12350010,"browser_download_url":"https://github.com/fu1se/filum/releases/download/v0.2.2/filum-v0.2.2.apk"}]}
        """))!!
        assertEquals("0.2.2", r.version)
        assertEquals(12350010, r.apkSize)
        assertTrue(r.apkUrl.endsWith("filum-v0.2.2.apk"))
        assertNull(AppRelease.fromGitHub(JSONObject("""{"tag_name":"v1","assets":[]}""")))
    }

    @Test fun catalogFromRepository() {
        val list = CatalogPlugin.parseCatalog(java.io.File("../../plugins/catalog.json").readText())
        assertTrue(list.isNotEmpty())
        for (p in list) {
            assertTrue(p.id, Regex("^https://raw\\.githubusercontent\\.com/[^/]+/[^/]+/[0-9a-f]{7,40}/").containsMatchIn(p.url))
            assertEquals(64, p.sha256.length)
            assertTrue(p.version.isNotBlank())
        }
        // записи без https или с неверной суммой отбрасываются
        assertTrue(CatalogPlugin.parseCatalog("""{"plugins":[{"id":"a","url":"http://x","sha256":"${"0".repeat(64)}"},{"id":"b","url":"https://x","sha256":"zz"}]}""").isEmpty())
    }

    @Test fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }
}
