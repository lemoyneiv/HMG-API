package hmgapi.hook;

public final class BtStandbyTest {
    private static int fail = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            System.out.println("ok    " + name);
        } else {
            System.out.println("FAIL  " + name);
            fail = 1;
        }
    }

    public static void main(String[] args) {
        String bootA = "11223344-5566-7788-99aa-bbccddeeff00";
        String bootB = "ffeeddcc-bbaa-9988-7766-554433221100";

        check("BtStandby: marker paths match OpenLink and Bounce daemons",
                "/dev/.openlink-bt".equals(BtStandby.OPENLINK_BT_MARKER.getPath())
                        && "/dev/.hmgapi-bounce-bt".equals(BtStandby.BOUNCE_BT_MARKER.getPath()));
        check("BtStandby: idle allows BTCMService (uid 1002)",
                !BtStandby.shouldSuppressEnable(1002, bootA, "", ""));
        check("BtStandby: active OpenLink marker suppresses BTCMService (uid 1002)",
                BtStandby.shouldSuppressEnable(1002, bootA, bootA + "\n", ""));
        check("BtStandby: active Bounce marker suppresses Iap2Service (uid 1000)",
                BtStandby.shouldSuppressEnable(1000, bootA, "", bootA + "\n"));
        check("BtStandby: uid 0 daemon is NEVER suppressed even with marker present",
                !BtStandby.shouldSuppressEnable(0, bootA, bootA, bootA));
        check("BtStandby: stale boot_id marker never suppresses enable",
                !BtStandby.shouldSuppressEnable(1002, bootA, bootB, bootB));
        check("BtStandby: missing boot_id fails open",
                !BtStandby.shouldSuppressEnable(1002, "", bootA, bootA));

        System.exit(fail);
    }
}
