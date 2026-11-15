package app.ankermonitor.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerUrlTest {
    @Test
    fun amon_remoteと同じ補完をする() {
        assertEquals("http://100.64.1.2:19877", normalizeServerUrl("100.64.1.2"))
        assertEquals("http://my-mac.tailnet.ts.net:19877", normalizeServerUrl(" my-mac.tailnet.ts.net/ "))
        assertEquals("http://100.64.1.2:8080", normalizeServerUrl("100.64.1.2:8080"))
        assertEquals("https://mac.example:19877", normalizeServerUrl("https://mac.example"))
        assertEquals("http://100.64.1.2:19877", normalizeServerUrl("http://100.64.1.2:19877/"))
        assertEquals("", normalizeServerUrl("  "))
    }

    @Test
    fun ホストを取り出す() {
        assertEquals("100.64.1.2", hostOf("http://100.64.1.2:19877"))
        assertEquals("my-mac", hostOf("http://my-mac:19877"))
    }
}
