package app.ankermonitor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// anker_monitor.py の Manager.snapshot() が返す形。Store.get() が足す via / channels / updated、
// BLE の _read() が足す solar_in / temperature / firmware、net_of_sharing() の shared なども含める
private const val STATUS_JSON = """
{
  "ok": true,
  "now": 1763200000.123456,
  "devices": [
    {
      "id": "c1000-1", "name": "リビングの C1000", "model": "c1000", "model_label": "C1000(X)",
      "icon": "box", "experimental": false, "source": "auto", "address": "AA:BB:CC:DD:EE:FF",
      "cloud_sn": "AZV1234", "via": "ble",
      "channels": {"ble": {"state": "connected", "detail": ""}, "cloud": {"state": "offline", "detail": "x"}},
      "state": "connected", "battery": 36.0, "power_in": 212.5, "power_out": 48,
      "ports": [
        {"key": "solar", "label": "ソーラー", "dir": "in", "w": 200},
        {"key": "usb_c1", "label": "USB-C1", "dir": "out", "w": 30, "shared": true},
        {"key": "other_out", "label": "出力 その他", "dir": "out", "w": 18}
      ],
      "charging": "CHARGING", "detail": "", "solar_in": 200, "temperature": 27,
      "remaining_h": 3.4, "firmware": "1.2.3", "updated": 1763199999.5,
      "switches": {"ac_out": null, "dc_out": true},
      "shared": [{"key": "usb_c1", "dir": "out", "w": 30, "peer": "c300-1", "peer_name": "C300"}]
    },
    {
      "id": "c300-1", "name": "C300", "model": "c300", "model_label": "C300(X)",
      "icon": "compact", "experimental": false, "source": "ble", "address": "11:22:33:44:55:66",
      "cloud_sn": null, "via": null, "channels": {},
      "state": "searching", "battery": null, "power_in": null, "power_out": null,
      "ports": [], "charging": "UNKNOWN", "detail": ""
    }
  ]
}
"""

class StatusParseTest {
    @Test
    fun 実際の形のstatusを読める() {
        val status = parseStatus(STATUS_JSON)
        assertTrue(status.ok)
        assertEquals(2, status.devices.size)

        val c1000 = status.devices[0]
        assertEquals("c1000-1", c1000.id)
        assertEquals("リビングの C1000", c1000.displayName)
        assertEquals("C1000(X)", c1000.modelLabel)
        assertEquals(36, c1000.batteryPercent)
        assertEquals(213, c1000.inputWatts)
        assertEquals(48, c1000.outputWatts)
        assertEquals("ble", c1000.via)
        assertEquals(3, c1000.ports?.size)
        assertEquals("in", c1000.ports!![0].dir)
        assertEquals(3.4, c1000.remainingHours)
        assertTrue(c1000.hasReading)
        assertEquals(PowerFlow.CHARGING, c1000.powerFlow)
    }

    @Test
    fun 探索中の機器は値なしで読める() {
        val c300 = parseStatus(STATUS_JSON).devices[1]
        assertEquals("searching", c300.state)
        assertNull(c300.battery)
        assertNull(c300.batteryPercent)
        assertFalse(c300.hasReading)
        assertEquals(PowerFlow.UNKNOWN, c300.powerFlow)
        assertEquals("探しています…", c300.stateLabel)
    }

    @Test
    fun 機器が無い応答を読める() {
        val status = parseStatus("""{"ok": true, "now": 1763200000.0, "devices": []}""")
        assertTrue(status.devices.isEmpty())
    }

    @Test
    fun chargingがUNKNOWNなら入出力の多い方で向きを決める() {
        val base = Device(id = "d", state = "connected", battery = 50.0, charging = "UNKNOWN")
        assertEquals(PowerFlow.CHARGING, base.copy(powerIn = 20.0, powerOut = 5.0).powerFlow)
        assertEquals(PowerFlow.DISCHARGING, base.copy(powerIn = 0.0, powerOut = 40.0).powerFlow)
        assertEquals(PowerFlow.IDLE, base.copy(powerIn = 0.0, powerOut = 0.0).powerFlow)
    }

    @Test
    fun スナップショットは保存して読み戻せる() {
        val snapshot = Snapshot(parseStatus(STATUS_JSON), 1_000L, "x", 2_000L)
        val json = StatusJson.encodeToString(Snapshot.serializer(), snapshot)
        assertEquals(snapshot, StatusJson.decodeFromString(Snapshot.serializer(), json))
    }
}
