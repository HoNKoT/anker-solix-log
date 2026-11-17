package app.ankermonitor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferTest {
    private val status = parseStatus(
        """{"devices":[
          {"id":"c2000","state":"connected","battery":80,"power_in":0,"power_out":20,
           "ports":[{"key":"usb_c1","dir":"out","w":98,"shared":true},{"key":"ac_out","dir":"out","w":20}],
           "shared":[{"key":"usb_c1","dir":"out","w":98,"peer":"c300","peer_name":"C300 DC","sent":98,"recv":92}]},
          {"id":"c300","state":"connected","battery":50,"power_in":0,"power_out":0,
           "shared":[{"key":"in_usb","dir":"in","w":92,"peer":"c2000","peer_name":"C2000","sent":98,"recv":92}]},
          {"id":"old","state":"connected","battery":50,
           "shared":[{"key":"usb_c1","dir":"out","w":40,"peer":"c300"}]},
          {"id":"none","state":"connected","battery":50}
        ]}""",
    )
    private fun dev(id: String) = status.devices.single { it.id == id }

    @Test
    fun 送り側はTransferを受け取ったWで数えロスを出す() {
        val d = dev("c2000")
        assertEquals(92, d.transferWatts)
        assertEquals(6, d.transferLossWatts)
        assertEquals("Transfer 送り出し 92 W (6 W loss)", d.transferLabel)
        // 入力・出力の W には給電ぶんが入っていない (バックエンドで引いてある)
        assertEquals(20, d.outputWatts)
        assertEquals(true, d.ports!!.first().shared)
    }

    @Test
    fun 受け側はロスを出さない() {
        val d = dev("c300")
        assertEquals("Transfer 受け取り 92 W", d.transferLabel)
        assertEquals(0, d.transferLossWatts)
    }

    @Test
    fun sentとrecvが無い古いバックエンドではwで数える() {
        assertEquals("Transfer 送り出し 40 W", dev("old").transferLabel)
    }

    @Test
    fun 給電していなければ出さない() {
        assertNull(dev("none").transferLabel)
    }
}
