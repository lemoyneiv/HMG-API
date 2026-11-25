package hmgapi.hook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Off-car unit tests for {@link BtcmFastConnect} cold-boot priority connect gating.
 */
public final class BtcmFastConnectTest {
    private static int checks = 0;
    private static int fails = 0;

    private static void check(String label, Object want, Object got) {
        checks++;
        boolean eq = (want == null) ? (got == null) : want.equals(got);
        if (eq) {
            System.out.println("ok    " + label);
        } else {
            System.out.println("FAIL  " + label + ": wanted [" + want + "], got [" + got + "]");
            fails++;
        }
    }

    public static void main(String[] args) {
        Map<String, Object> modsOn = new LinkedHashMap<String, Object>();
        Map<String, Object> wOn = new LinkedHashMap<String, Object>();
        wOn.put("enabled", Boolean.TRUE);
        modsOn.put("wireless-carplay-aa", wOn);

        Map<String, Object> modsOff = new LinkedHashMap<String, Object>();
        Map<String, Object> wOff = new LinkedHashMap<String, Object>();
        wOff.put("enabled", Boolean.FALSE);
        modsOff.put("wireless-carplay-aa", wOff);

        check("BtcmFastConnect: detects enabled wireless-carplay-aa",
                Boolean.TRUE, Boolean.valueOf(BtcmFastConnect.isWirelessProjectionEnabled(modsOn)));
        check("BtcmFastConnect: detects disabled wireless-carplay-aa",
                Boolean.FALSE, Boolean.valueOf(BtcmFastConnect.isWirelessProjectionEnabled(modsOff)));
        check("BtcmFastConnect: handles empty mods map",
                Boolean.FALSE, Boolean.valueOf(BtcmFastConnect.isWirelessProjectionEnabled(Collections.<String, Object>emptyMap())));

        check("BtcmFastConnect: parses public wifi.json waiting state",
                "waiting", BtcmFastConnect.parseHomeWifiState("{\"state\": \"waiting\", \"rssi\": 0, \"freq\": 0}\n"));
        check("BtcmFastConnect: parses public wifi.json up state",
                "up", BtcmFastConnect.parseHomeWifiState("{\"state\": \"up\", \"rssi\": -52, \"freq\": 5180}\n"));
        check("BtcmFastConnect: matches exact boot_id marker",
                Boolean.TRUE, Boolean.valueOf(BtcmFastConnect.matchesBootMarker("boot-abc", "boot-abc\n")));
        check("BtcmFastConnect: rejects stale boot_id marker",
                Boolean.FALSE, Boolean.valueOf(BtcmFastConnect.matchesBootMarker("boot-abc", "boot-old\n")));

        // 1. All cold-boot conditions met -> DISPATCH
        check("BtcmFastConnect: dispatches cold-boot STATE_ON when all guards hold",
                BtcmFastConnect.Action.DISPATCH,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "waiting",
                        12, 13, 2, true, true, false, false, true, false));

        // 2. Locked-cabin silent wake (!powerOrGraceApproved) -> WAIT (never connects phone!)
        check("BtcmFastConnect: waits / suppresses on silent wake without power or entry-grace approval",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, false, false, "waiting",
                        12, 13, 2, true, true, false, false, true, false));

        // 3. BtStandby active -> WAIT
        check("BtcmFastConnect: waits while BtStandby marker suppresses Bluetooth",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, true, true, "waiting",
                        12, 13, 2, true, true, false, false, true, false));

        // 4. Home Wi-Fi up or searching -> WAIT
        check("BtcmFastConnect: waits while Home Wi-Fi is searching",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "searching",
                        12, 13, 2, true, true, false, false, true, false));
        check("BtcmFastConnect: waits while Home Wi-Fi is up",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "up",
                        12, 13, 2, true, true, false, false, true, false));

        // 5. BTCMService still running handleMessage(100) (!btcmReady) or PPService not bound yet -> WAIT
        check("BtcmFastConnect: waits until BTCMService.btcmIsReady() is true",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "waiting",
                        12, 13, 2, true, false, false, false, true, false));
        check("BtcmFastConnect: waits until phoneprojection Binder is ready",
                BtcmFastConnect.Action.WAIT,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "waiting",
                        12, 13, 2, true, true, false, false, false, false));

        // 6. BTCMService already recorded AdapterOnChanged=true or phone already connected -> DONE_ALREADY_ACTIVE
        check("BtcmFastConnect: finishes cleanly when getBluetoothAdapterState() is already true",
                BtcmFastConnect.Action.DONE_ALREADY_ACTIVE,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "waiting",
                        12, 13, 2, true, true, true, false, true, false));
        check("BtcmFastConnect: finishes cleanly when AA/CP phone is already connected",
                BtcmFastConnect.Action.DONE_ALREADY_ACTIVE,
                BtcmFastConnect.evaluate(
                        true, false, false, true, "waiting",
                        12, 13, 2, true, true, false, false, false, true));

        if (fails > 0) {
            System.err.println("FAIL  " + fails + " of " + checks + " BtcmFastConnect checks failed");
            System.exit(1);
        }
        System.out.println("ok    all " + checks + " BtcmFastConnect checks passed");
    }
}
