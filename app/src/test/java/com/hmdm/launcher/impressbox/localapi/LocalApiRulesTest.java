package com.hmdm.launcher.impressbox.localapi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class LocalApiRulesTest {

    private static final String PLAYER = "A7A7EAE8911E7522D00CFD377D5DED6F2BBEEF629DF6A37BB3C97AEEA96BEA53";

    @Test
    public void aFixedConfigZoneOwnsTheZone() {
        assertFalse(LocalApiRules.zoneManagedByConfig(null));
        assertFalse(LocalApiRules.zoneManagedByConfig(""));
        assertFalse(LocalApiRules.zoneManagedByConfig("  "));
        assertFalse(LocalApiRules.zoneManagedByConfig("auto"));
        assertFalse(LocalApiRules.zoneManagedByConfig("AUTO"));
        assertTrue(LocalApiRules.zoneManagedByConfig("Europe/Vilnius"));
        assertTrue(LocalApiRules.zoneManagedByConfig("UTC"));
    }

    @Test
    public void refusesUnknownCommandsAndMissingRights() {
        assertEquals(LocalApi.UNKNOWN_COMMAND, LocalApiRules.refusal(null, 34, true));
        assertEquals(LocalApi.UNKNOWN_COMMAND, LocalApiRules.refusal("wipeData", 34, true));
        assertEquals(LocalApi.UNKNOWN_COMMAND, LocalApiRules.refusal("settimezone", 34, true));
        assertEquals(LocalApi.OK, LocalApiRules.refusal(LocalApi.GET_STATUS, 26, false));
        assertEquals(LocalApi.NOT_DEVICE_OWNER, LocalApiRules.refusal(LocalApi.SET_TIME_ZONE, 34, false));
        assertEquals(LocalApi.NOT_DEVICE_OWNER, LocalApiRules.refusal(LocalApi.REBOOT, 34, false));
        assertEquals(LocalApi.UNSUPPORTED, LocalApiRules.refusal(LocalApi.SET_TIME_ZONE, 27, true));
        assertEquals(LocalApi.UNSUPPORTED, LocalApiRules.refusal(LocalApi.SET_TIME, 27, true));
        assertEquals(LocalApi.OK, LocalApiRules.refusal(LocalApi.SET_TIME_ZONE, 28, true));
        assertEquals(LocalApi.OK, LocalApiRules.refusal(LocalApi.REBOOT, 26, true));
        assertEquals(LocalApi.OK, LocalApiRules.refusal(LocalApi.SET_SYSTEM_UPDATE_POLICY, 26, true));
    }

    @Test
    public void capabilitiesFollowAndroidLevelAndDeviceOwner() {
        assertEquals(LocalApiRules.COMMANDS, LocalApiRules.capabilities(33, true));
        assertEquals(Arrays.asList(LocalApi.GET_STATUS, LocalApi.SET_AUTO_TIME, LocalApi.REBOOT,
                LocalApi.SET_SYSTEM_UPDATE_POLICY), LocalApiRules.capabilities(27, true));
        assertEquals(Collections.singletonList(LocalApi.GET_STATUS), LocalApiRules.capabilities(34, false));
    }

    @Test
    public void onlyANonDefaultConfigUpdateTypeOwnsThePolicy() {
        assertFalse(LocalApiRules.updatePolicyManagedByConfig(null));
        assertFalse(LocalApiRules.updatePolicyManagedByConfig(0));
        assertTrue(LocalApiRules.updatePolicyManagedByConfig(1));
        assertTrue(LocalApiRules.updatePolicyManagedByConfig(2));
        assertTrue(LocalApiRules.updatePolicyManagedByConfig(3));
    }

    @Test
    public void updateWindowMustBeInsideTheDay() {
        assertNull(LocalApiRules.windowProblem(0, 1439));
        assertNull(LocalApiRules.windowProblem(120, 240));
        // Over midnight is fine: 23:00 - 02:00
        assertNull(LocalApiRules.windowProblem(1380, 120));
        assertNotNull(LocalApiRules.windowProblem(-1, 120));
        assertNotNull(LocalApiRules.windowProblem(120, 1440));
    }

    @Test
    public void clockMustBePlausible() {
        assertTrue(LocalApiRules.isPlausibleTime(1761700000000L));
        assertFalse(LocalApiRules.isPlausibleTime(0));
        assertFalse(LocalApiRules.isPlausibleTime(-1));
        assertFalse(LocalApiRules.isPlausibleTime(4102444800000L));
    }

    @Test
    public void knownZonesOnly() {
        List<String> ids = Arrays.asList("Europe/Vilnius", "Europe/Warsaw", "UTC");
        assertTrue(LocalApiRules.isKnownZone("Europe/Warsaw", ids));
        assertFalse(LocalApiRules.isKnownZone("Europe/Lithuania/Vilnius", ids));
        assertFalse(LocalApiRules.isKnownZone(null, ids));
    }

    @Test
    public void parsesCertificateDigests() {
        String colons = "a7:a7:ea:e8:91:1e:75:22:d0:0c:fd:37:7d:5d:ed:6f:2b:be:ef:62:9d:f6:a3:7b:b3:c9:7a:ee:a9:6b:ea:53";
        Set<String> parsed = LocalApiRules.parseCertDigests(colons);
        assertEquals(Collections.singleton(PLAYER), parsed);

        String other = "1111111111111111111111111111111111111111111111111111111111111111";
        parsed = LocalApiRules.parseCertDigests(PLAYER + ", " + other.toLowerCase() + "; not-a-digest  ABCD", null, "", PLAYER);
        assertEquals(Arrays.asList(PLAYER, other), Arrays.asList(parsed.toArray(new String[0])));

        assertTrue(LocalApiRules.parseCertDigests("", null).isEmpty());
        // 63 and 65 digits are not digests
        assertTrue(LocalApiRules.parseCertDigests(PLAYER.substring(1), PLAYER + "0").isEmpty());
    }

    @Test
    public void hexRoundTrip() {
        byte[] bytes = LocalApiRules.fromHex(PLAYER);
        assertEquals(32, bytes.length);
        assertEquals(PLAYER, LocalApiRules.toHex(bytes));
        assertArrayEquals(new byte[]{0, 127, -128, -1}, LocalApiRules.fromHex("007F80FF"));
    }
}
