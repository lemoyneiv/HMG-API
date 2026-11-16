package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Obfuscated reference names can be reused by unrelated classes in another build. */
public final class MapProbeTest {
    static int checks;

    static Dex.Member method(String name, String ret, boolean isStatic, String... params) {
        Dex.Member m = new Dex.Member();
        m.name = name;
        m.ret = ret;
        m.isStatic = isStatic;
        m.params = Arrays.asList(params);
        return m;
    }

    static Dex.Cls cls(String name, Dex.Member... members) {
        Dex.Cls c = new Dex.Cls();
        c.name = name;
        c.methods.addAll(Arrays.asList(members));
        return c;
    }

    static void reference(File maps, String build, String name, String open, String ready) throws Exception {
        File dir = new File(maps, "make/model/region/" + build);
        dir.mkdirs();
        Engine.write(new File(dir, "test.json"), "{\"test.helper\":{\"class\":\"" + name + "\"},"
                + "\"test.helper.open\":{\"method\":\"" + open + "\",\"params\":[\"android.content.Context\"],\"ret\":\"void\",\"static\":true},"
                + "\"test.helper.ready\":{\"method\":\"" + ready + "\",\"params\":[],\"ret\":\"boolean\",\"static\":true}}");
    }

    static String find(MapProbe p, Map<String, Dex.Cls> classes) throws Exception {
        return p.byReferenceClass(classes, "test.helper", Json.object("{\"pattern\":\"menu\",\"package\":\"sample\"}"));
    }

    static void equal(String label, String want, String got) {
        if (!want.equals(got)) throw new AssertionError(label + ": wanted " + want + ", got " + got);
        checks++;
    }

    static void missing(String label, MapProbe p, Map<String, Dex.Cls> classes, String count) throws Exception {
        try {
            find(p, classes);
            throw new AssertionError(label + ": accepted an unproven reference class");
        } catch (MapProbe.Miss miss) {
            String reason = "0".equals(count) ? "no other car's reference class and members fit"
                    : count + " of the other cars' names fit";
            if (!miss.getMessage().contains(reason)) throw miss;
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), maps = new File(root, "maps");
        reference(maps, "old", "sample.Old", "open", "ready");
        Map<String, Dex.Cls> classes = new LinkedHashMap<String, Dex.Cls>();
        classes.put("sample.Old", cls("sample.Old", method("unrelated", "void", false)));
        Dex.Cls current = cls("sample.New", method("open", "void", true, "android.content.Context"), method("ready", "boolean", true));
        classes.put(current.name, current);
        missing("old name reused without a new reference", new MapProbe(root.getPath(), null, maps), classes, "0");

        reference(maps, "new", "sample.New", "open", "ready");
        MapProbe p = new MapProbe(root.getPath(), null, maps);
        equal("choose the helper whose members fit", "sample.New", find(p, classes));
        if (!p.byReference.contains("test.helper")) throw new AssertionError("not recorded by reference");

        current.methods.get(0).ret = "boolean";
        missing("return type must match", p, classes, "0");
        current.methods.get(0).ret = "void";
        current.methods.get(0).isStatic = false;
        missing("staticness must match", p, classes, "0");
        current.methods.get(0).isStatic = true;
        current.methods.get(0).params = new ArrayList<String>();
        missing("parameters must match", p, classes, "0");
        current.methods.get(0).params = Arrays.asList("android.content.Context");

        classes.put("sample.Old", cls("sample.Old", method("open", "void", true, "android.content.Context"), method("ready", "boolean", true)));
        missing("two valid helpers remain ambiguous", p, classes, "2");
        classes.remove("sample.Old");

        reference(maps, "new", "sample.New", "open", "oldReady");
        reference(maps, "variant", "sample.New", "oldOpen", "ready");
        missing("members cannot be combined across builds", new MapProbe(root.getPath(), null, maps), classes, "0");

        File partialMaps = new File(root, "partial-maps"), partial = new File(partialMaps, "make/model/region/partial");
        partial.mkdirs();
        Engine.write(new File(partial, "test.json"), "{\"test.helper\":{\"class\":\"sample.Old\"}}");
        classes.put("sample.Old", cls("sample.Old", method("unrelated", "void", false)));
        equal("class-only references work when no members are known", "sample.Old",
                find(new MapProbe(root.getPath(), null, partialMaps), classes));
        reference(partialMaps, "complete", "sample.New", "open", "ready");
        equal("partial reference cannot hide a complete helper", "sample.New",
                find(new MapProbe(root.getPath(), null, partialMaps), classes));
        classes.remove("sample.New");
        missing("partial reference is not a fallback for a nonmatching complete reference",
                new MapProbe(root.getPath(), null, partialMaps), classes, "0");
        System.out.println("ok    reference class validation (" + checks + " checks)");
    }
}
