package com.hmdm.launcher.impressbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WakeOnLanKeeperTest {

    @Test
    public void onValueKeepsTheFormat() {
        assertEquals("1", WakeOnLanKeeper.onValue("0"));
        assertEquals("true", WakeOnLanKeeper.onValue("false"));
        assertEquals("TRUE", WakeOnLanKeeper.onValue("FALSE"));
        assertEquals("on", WakeOnLanKeeper.onValue("off"));
        assertEquals("enabled", WakeOnLanKeeper.onValue("disabled"));
        assertEquals("ENABLE", WakeOnLanKeeper.onValue("DISABLE"));
        assertNull(WakeOnLanKeeper.onValue("1"));
        assertNull(WakeOnLanKeeper.onValue("enabled"));
        assertNull(WakeOnLanKeeper.onValue("2"));
        assertNull(WakeOnLanKeeper.onValue(null));
    }

    @Test
    public void namesThatLookLikeWakeOnLan() {
        String[] yes = {"wol", "wol_enable", "persist.sys.wol", "xbh_wol_enable", "wolEnable", "xbhWol",
                "WOL_SWITCH", "wake_on_lan", "wakeonlan", "WakeOnLan", "wake-on-lan", "persist.vendor.wake_on_eth",
                "lan_wake", "eth_wake_up", "ethernet_wake", "network_standby", "wake_up_by_lan", "magic_packet"};
        String[] no = {"evolution", "wolf", "gwol", "screen_off_timeout", "adb_enabled", "volume_music",
                "wake_gesture_enabled", "lan_ip"};
        for (String s : yes) {
            assertTrue(s, WakeOnLanKeeper.looksLikeWakeOnLan(s));
        }
        for (String s : no) {
            assertFalse(s, WakeOnLanKeeper.looksLikeWakeOnLan(s));
        }
    }

    @Test
    public void parsesServerKeys() {
        List<String[]> keys = WakeOnLanKeeper.parseExtraKeys(
                "system:xbh_wol_enable=1; global:wake_on_lan = true,secure:x=1\nplain_key=on;bad;other:k=1;=1;k=");
        assertEquals(4, keys.size());
        assertEquals("system", keys.get(0)[0]);
        assertEquals("xbh_wol_enable", keys.get(0)[1]);
        assertEquals("1", keys.get(0)[2]);
        assertEquals("global", keys.get(1)[0]);
        assertEquals("wake_on_lan", keys.get(1)[1]);
        assertEquals("true", keys.get(1)[2]);
        assertEquals("secure", keys.get(2)[0]);
        assertEquals("system", keys.get(3)[0]);
        assertEquals("plain_key", keys.get(3)[1]);
        assertTrue(WakeOnLanKeeper.parseExtraKeys(null).isEmpty());
    }
}
