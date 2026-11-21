package hmgapi.hook;

/**
 * Off-zygote test of the hook runtime, run on the head unit as a plain
 * app_process with HMGAPI_SELFTEST=<jar> (command in core/README.md). The native
 * loader calls selfTest() in its own copy of the jar; main() only ends the run.
 */
public final class SelfTest {
    static int fails;

    static void check(String name, boolean ok, Object got) {
        String line = (ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : " (got " + got + ")");
        if (!ok) fails++;
        Bridge.log(ok ? 4 : 6, "selftest: " + line);
        System.out.println(line);
    }

    // targets
    static int add(int a, int b) { return a + b; }
    String greet(String who) { return "hello " + who; }
    static double mix(long a, double b, int c) { return a / 1e12 + b * c; }
    static void boom() { throw new IllegalStateException("boom"); }

    static int order() { return 1; }

    static int plain(int x) { return x; }

    static int plain2(int x) { return x; }

    static int plain3(int x) { return x; }

    static int plain4(int x) { return x; }
    static int twice(int x) { return x * 2; }
    static boolean flag(boolean f, char c, short s, byte b, float x) { return f && c == 'q' && s == -3 && b == 7 && x == 1.5f; }
    static int[] arr(int[] in, Object o) { return in; }
    static final class Thing { final int n; Thing(int n) { this.n = n; } }

    static void selfTest() {
        try {
            run();
        } catch (Throwable t) {
            check("no unexpected exception", false, Bridge.trace(t));
        }
        System.out.println(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED: " + fails);
        Bridge.log(fails == 0 ? 4 : 6, fails == 0 ? "selftest: OK" : "selftest: FAILED " + fails);
    }

    static void run() throws Throwable {
        Hooks.findAndHookMethod(SelfTest.class, "add", int.class, int.class, new MethodHook() {
            @Override protected void afterHookedMethod(Param p) { p.setResult((Integer) p.getResult() + 100); }
        });
        check("int args, after hook changes result", add(1, 2) == 103, add(1, 2));

        Hooks.findAndHookMethod(SelfTest.class, "greet", String.class, new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                if ("skip".equals(p.args[0])) p.setResult("hooked " + (p.thisObject != null));
            }
        });
        SelfTest st = new SelfTest();
        check("before hook skips original", "hooked true".equals(st.greet("skip")), st.greet("skip"));
        check("instance call passes through", "hello you".equals(st.greet("you")), st.greet("you"));

        final Object[] seen = new Object[3];
        Hooks.findAndHookMethod(SelfTest.class, "mix", long.class, double.class, int.class, new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) { System.arraycopy(p.args, 0, seen, 0, 3); }
        });
        double r = mix(1L << 40, 2.5, 7);
        check("long/double args boxed", Long.valueOf(1L << 40).equals(seen[0]) && Double.valueOf(2.5).equals(seen[1])
                && Integer.valueOf(7).equals(seen[2]), seen[0] + "," + seen[1] + "," + seen[2]);
        check("double result unchanged", r == (1L << 40) / 1e12 + 17.5, r);

        final Throwable[] thrown = new Throwable[1];
        Hooks.findAndHookMethod(SelfTest.class, "boom", new MethodHook() {
            @Override protected void afterHookedMethod(Param p) { thrown[0] = p.getThrowable(); }
        });
        Throwable caught = null;
        try { boom(); } catch (IllegalStateException e) { caught = e; }
        check("exception reaches after hook", thrown[0] instanceof IllegalStateException, thrown[0]);
        check("exception still thrown to caller", caught != null && "boom".equals(caught.getMessage()), caught);

        Hooks.findAndHookMethod(SelfTest.class, "twice", int.class, new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) { p.args[0] = (Integer) p.args[0] + 1; }
        });
        Hooks.findAndHookMethod(SelfTest.class, "twice", int.class, new MethodHook() {
            @Override protected void afterHookedMethod(Param p) { p.setResult((Integer) p.getResult() + 1000); }
        });
        check("two hooks, changed args", twice(4) == 1010, twice(4));

        Hooks.findAndHookMethod(SelfTest.class, "flag", boolean.class, char.class, short.class, byte.class, float.class,
                new MethodHook() {});
        check("boolean/char/short/byte/float args", flag(true, 'q', (short) -3, (byte) 7, 1.5f), "false");

        Hooks.findAndHookMethod(SelfTest.class, "arr", int[].class, Object.class, new MethodHook() {});
        int[] a = { 1, 2, 3 };
        check("array arg and result", arr(a, null) == a, "different array");

        Bridge.hookMethod(Thing.class.getDeclaredConstructor(int.class), new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) { p.args[0] = 6; }
        });
        check("constructor hook", new Thing(5).n == 6, new Thing(5).n);

        // mods' hooks: replace hooks run last; a hook can only do what its mode allows
        ModContext m1 = new ModContext("test-a", "1", null, new org.json.JSONObject());
        ModContext m2 = new ModContext("test-b", "1", null, new org.json.JSONObject());
        final StringBuilder ran = new StringBuilder();
        ModContext.Guard rep = new ModContext.Guard(m1, "self.order", "replace", new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) { ran.append("R"); p.setResult(7); }
            @Override protected void afterHookedMethod(Param p) { ran.append("r"); }
        });
        rep.priority = MethodHook.PRIORITY_REPLACE;
        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("order"), rep);   // registered first
        MethodHook coreAfter = new MethodHook() {
            @Override protected void afterHookedMethod(Param p) { ran.append("C"); }
        };
        coreAfter.priority = MethodHook.PRIORITY_CORE_AFTER;
        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("order"), coreAfter);
        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("order"), new ModContext.Guard(m2, "self.order", "observe",
                new MethodHook() {
                    @Override protected void beforeHookedMethod(Param p) { ran.append("O"); }
                    @Override protected void afterHookedMethod(Param p) { ran.append("o"); }
                }));
        int got = order();
        check("replace runs last before, core after-hook runs before observe after-hook",
                "ORrCo".equals(ran.toString()) && got == 7, ran + " " + got);

        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("plain", int.class), new ModContext.Guard(m1, "self.plain", "observe",
                new MethodHook() { @Override protected void afterHookedMethod(Param p) { p.setResult(-1); } }));
        check("observe hook that changes the result: undone", plain(5) == 5, plain(5));
        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("plain2", int.class), new ModContext.Guard(m1, "self.plain2", "change",
                new MethodHook() { @Override protected void beforeHookedMethod(Param p) { p.setResult(-1); } }));
        check("change hook that skips the original: undone", plain2(5) == 5, plain2(5));
        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("plain3", int.class), new ModContext.Guard(m1, "self.plain3", "change",
                new MethodHook() { @Override protected void afterHookedMethod(Param p) { p.setResult(10); } }));
        check("change hook may change the result", plain3(5) == 10, plain3(5));

        Bridge.hookMethod(SelfTest.class.getDeclaredMethod("plain4", int.class), new ModContext.Guard(m2, "self.plain4", "change",
                new MethodHook() { @Override protected void afterHookedMethod(Param p) { p.setResult(10); } }));
        m2.enabled = false;
        check("a mod that's off: its hook passes through", plain4(5) == 5, plain4(5));
        m2.enabled = true;
        check("on again: its hook runs", plain4(5) == 10, plain4(5));

        int sum = 0;
        for (int i = 0; i < 2000; i++) {
            sum += add(i, 0) - 100;
            if (i % 500 == 0) System.gc();
            String s = st.greet("n" + i);
            if (!s.equals("hello n" + i)) { sum = -1; break; }
        }
        check("2000 calls with GC", sum == 1999 * 2000 / 2, sum);
    }

    public static void main(String[] args) {
        System.out.println("selftest main done");
    }
}
