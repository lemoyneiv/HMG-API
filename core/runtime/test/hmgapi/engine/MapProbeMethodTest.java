package hmgapi.engine;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Methods only a smali pattern finds: another build's name is only a hint. The method taken is the
 * one whose code is the same as another map's ("sig"); with several such methods, none.
 * Seen on the Korean 260902 builds: py()Z is the test gate elsewhere but reads ro.product.model
 * there (the gate is pB), and gl()I is the getter of another field (the point is gk).
 */
public final class MapProbeMethodTest {
    static int checks;

    static Dex.Member method(String name, long sig) {
        Dex.Member m = new Dex.Member();
        m.name = name;
        m.ret = "boolean";
        m.isStatic = true;
        m.params = Arrays.asList();
        m.sig = sig;
        return m;
    }

    static void reference(File maps, String build, String name, String sig) throws Exception {
        File dir = new File(maps, "make/model/region/" + build);
        dir.mkdirs();
        Engine.write(new File(dir, "test.json"), "{\"test.gate.on\":{\"method\":\"" + name
                + "\",\"params\":[],\"ret\":\"boolean\",\"static\":true" + (sig == null ? "" : ",\"sig\":\"" + sig + "\"") + "}}");
    }

    static final Map<String, Object> FIND = find();

    static Map<String, Object> find() {
        try {
            return Json.object("{\"pattern\":\"gate\",\"group\":\"m\",\"params\":[],\"ret\":\"boolean\",\"static\":true}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void found(String label, MapProbe p, Dex.Cls c, String want) throws Exception {
        String got = p.methodByReference(java.util.Collections.singletonMap(c.name, c), c, "test.gate.on", FIND).name;
        if (!want.equals(got)) throw new AssertionError(label + ": wanted " + want + ", got " + got);
        checks++;
    }

    static void missing(String label, MapProbe p, Dex.Cls c, String reason) throws Exception {
        try {
            String got = p.methodByReference(java.util.Collections.singletonMap(c.name, c), c, "test.gate.on", FIND).name;
            throw new AssertionError(label + ": took " + got);
        } catch (MapProbe.Miss miss) {
            if (!miss.getMessage().contains(reason)) throw new AssertionError(label + ": " + miss.getMessage());
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), maps = new File(root, "maps");
        String gate = Dex.sigHex(0x1111L);

        // the other builds say py; here py is something else and the gate is pB
        reference(maps, "us", "py", gate);
        MapProbe p = new MapProbe(root.getPath(), null, maps);
        Dex.Cls c = new Dex.Cls();
        c.name = "sample.v";
        c.methods.add(method("py", 0x2222L));
        c.methods.add(method("pB", 0x1111L));
        found("same code under another name", p, c, "pB");
        if (!p.byReference.contains("test.gate.on")) throw new AssertionError("not recorded by reference");
        checks++;

        // the name alone is never enough
        c.methods.get(1).sig = 0x3333L;
        missing("a same-named method with other code", p, c, "0 methods in sample.v have the other cars' code");

        // several methods with that code (getters of different fields): no guess, even by name
        c.methods.get(0).sig = 0x1111L;
        c.methods.get(1).sig = 0x1111L;
        missing("two methods with the same code", p, c, "needs this car's map");

        // the shape still has to fit
        c.methods.get(1).ret = "int";
        found("shape first, then code", p, c, "py");

        // the same code twice, but the pattern calls it with invoke-virtual: the private one is out
        Map<String, Object> virtualFind = Json.object("{\"pattern\":\"gate\",\"group\":\"m\",\"params\":[],\"ret\":\"boolean\",\"static\":true,\"site\":{\"virtual\":true}}");
        Dex.Cls vc = new Dex.Cls();
        vc.name = "sample.v";
        Dex.Member priv = method("L", 0x1111L), pub = method("K", 0x1111L);
        pub.isVirtual = true;
        vc.methods.add(priv);
        vc.methods.add(pub);
        String got = p.methodByReference(java.util.Collections.singletonMap(vc.name, vc), vc, "test.gate.on", virtualFind).name;
        if (!"K".equals(got)) throw new AssertionError("site virtual: wanted K, got " + got);
        checks++;

        // maps without signatures give no evidence
        File old = new File(root, "old-maps");
        reference(old, "us", "py", null);
        missing("references without sig", new MapProbe(root.getPath(), null, old), c, "no other car's map has its code signature");

        // Dex.walk signs the same sample as tools/dexsig.py (SAMPLE_UNITS, SAMPLE_SIG)
        int[] units = { 0x0162, 0x0007, 0x011d, 0x10f2, 0x0010, 0x011e, 0x002b, 0x0004, 0x0000, 0x000f,
                        0x0100, 0x0001, 0x0000, 0x0000, 0x0005, 0x0000 };
        String want = args[1];
        if (!want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: wanted " + want + ", got " + Dex.sigHex(sign(units)));
        checks++;
        units[0] = 0x0262;   // another register, another field index, another table value
        units[1] = 0x0099;
        units[13] = 0x7777;
        if (!want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: registers, indices or table data changed the signature");
        checks++;
        units[4] = 0x0014;   // another field offset
        if (want.equals(Dex.sigHex(sign(units)))) throw new AssertionError("Dex.walk: a quickened operand didn't change the signature");
        checks++;
        sites();
        System.out.println("ok    by-reference methods by their code and call sites (" + checks + " checks)");
    }

    static void put4(byte[] b, int o, int v) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i));
    }

    static void put2(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    /**
     * Dex.sitesAt on a hand-made dex: strings, types, one proto ()Z, methods A.f and A.g, and a
     * code item that calls A.f with invoke-static, then a quickened invoke-virtual:
     *   invoke-static {}, LA;->f()Z / move-result v0 / if-eqz v0 / iget-object-quick v0, p0, [obj+8] / goto
     *   invoke-virtual-quick {p0}, vtable@3 / move-result-object v0 / return-object v0
     */
    static void sites() {
        String[] str = { "LA;", "Z", "f", "g", "Z" };
        byte[] b = new byte[0x200];
        b[0] = 'd'; b[1] = 'e'; b[2] = 'x'; b[3] = '\n';
        int strIds = 0x70, typeIds = strIds + str.length * 4, protoIds = typeIds + 8, methodIds = protoIds + 12, data = methodIds + 16;
        put4(b, 0x38, str.length); put4(b, 0x3c, strIds);
        put4(b, 0x40, 2); put4(b, 0x44, typeIds);
        put4(b, 0x48, 1); put4(b, 0x4c, protoIds);
        put4(b, 0x58, 2); put4(b, 0x5c, methodIds);
        int at = data;
        for (int i = 0; i < str.length; i++) {
            put4(b, strIds + i * 4, at);
            b[at++] = (byte) str[i].length();
            for (char ch : str[i].toCharArray()) b[at++] = (byte) ch;
            b[at++] = 0;
        }
        put4(b, typeIds, 0);       // LA;
        put4(b, typeIds + 4, 1);   // Z
        put4(b, protoIds, 4); put4(b, protoIds + 4, 1); put4(b, protoIds + 8, 0);   // ()Z
        put2(b, methodIds, 0); put2(b, methodIds + 2, 0); put4(b, methodIds + 4, 2);     // A.f()Z
        put2(b, methodIds + 8, 0); put2(b, methodIds + 10, 0); put4(b, methodIds + 12, 3); // A.g()Z
        int[] units = { 0x0071, 0x0000, 0x0000, 0x000a, 0x0038, 0x0004, 0x00f4, 0x0008, 0x0028,
                        0x10f8, 0x0003, 0x0000, 0x000c, 0x0011 };
        int item = (at + 3) & ~3;
        put4(b, item + 12, units.length);
        for (int i = 0; i < units.length; i++) put2(b, item + 16 + i * 2, units[i]);
        Dex d = new Dex(b);
        d.strings = new String[str.length];
        d.types = new String[2];
        java.util.List<Dex.Site> ss = d.sitesAt(item, 4);
        if (ss.size() != 2) throw new AssertionError("sites: wanted 2 calls, got " + ss.size());
        Dex.Site st = ss.get(0);
        if (!"invoke-static".equals(st.op) || !"A.f()boolean".equals(st.target)) throw new AssertionError("static call: " + st.op + " " + st.target);
        if (!st.next.equals(Arrays.asList("move-result", "if-eqz", "iget-object", "goto")))
            throw new AssertionError("families after the call (iget-object-quick is an iget-object): " + st.next);
        checks += 2;
        Dex.Site q = ss.get(1);
        if (!"invoke-virtual".equals(q.op) || q.target != null) throw new AssertionError("quickened call: " + q.op + " " + q.target);
        if (!q.next.equals(Arrays.asList("move-result-object", "return-object"))) throw new AssertionError("after the quickened call: " + q.next);
        checks += 2;
    }

    /** a code item (16-byte header, then the units) after a bare dex header */
    static long sign(int[] units) {
        byte[] b = new byte[0x70 + 16 + units.length * 2];
        b[0] = 'd';
        b[1] = 'e';
        b[2] = 'x';
        b[3] = '\n';
        int item = 0x70, n = units.length;
        b[item + 12] = (byte) n;
        b[item + 13] = (byte) (n >> 8);
        for (int i = 0; i < n; i++) {
            b[item + 16 + i * 2] = (byte) units[i];
            b[item + 17 + i * 2] = (byte) (units[i] >> 8);
        }
        return new Dex(b).walk(item, new LinkedHashSet<String>());
    }
}
