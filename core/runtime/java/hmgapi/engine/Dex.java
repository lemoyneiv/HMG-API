package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A reader for the classes in a dex: an .odex (the head unit's /system/app and
 * /system/framework files: "dey" header, the dex inside), a plain classes.dex, or a
 * jar or APK holding one. For each class: its name, superclass, fields and methods
 * (names and Java types, static or not), and the strings its code loads
 * (const-string), per method and for the whole class. That's what the hook point
 * catalogue describes points by (MapProbe), the same facts tools/map-car.py reads
 * from baksmali's output. Names only; no code is kept.
 */
public final class Dex {
    public static final class Member {
        public String name, type, ret;
        public List<String> params;
        public boolean isStatic;
        public Set<String> strings;   // methods: const-strings in its own code
        public long sig;              // methods: codeSig of its code (0: none)
        public boolean isVirtual;     // methods: in the vtable (not static, private or a constructor)
        int code;                     // methods: the code item's offset in the file's bytes (0: none)
    }

    public static final class Cls {
        public String name, sup;
        File src;   // the file it was read from (sites() reads its code again)
        public final List<Member> fields = new ArrayList<Member>();
        public final List<Member> methods = new ArrayList<Member>();
        public final Set<String> strings = new LinkedHashSet<String>();
    }

    final byte[] b;
    final int base;
    String[] strings;
    String[] types;

    Dex(byte[] data) {
        b = data;
        if (data.length > 16 && data[0] == 'd' && data[1] == 'e' && data[2] == 'y' && data[3] == '\n') base = u4(8);
        else if (data.length > 0x70 && data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n') base = 0;
        else throw new IllegalArgumentException("not a dex or odex");
    }

    /** Every class in an .odex, .dex, or the classes.dex of a .jar/.apk: name -> class. */
    public static Map<String, Cls> read(File f) throws Exception {
        byte[] data;
        String n = f.getName();
        if (n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".zip")) {
            ZipFile z = new ZipFile(f);
            try {
                ZipEntry e = z.getEntry("classes.dex");
                if (e == null) return new LinkedHashMap<String, Cls>();
                data = readAll(z.getInputStream(e));
            } finally {
                z.close();
            }
        } else {
            data = readAll(new FileInputStream(f));
        }
        Map<String, Cls> out = new Dex(data).classes();
        for (Cls c : out.values()) c.src = f;
        return out;
    }

    static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---- raw reads (little endian; dex offsets are from the dex's own start)

    int u2(int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    int u4(int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    int h(int field) {
        return u4(base + field);
    }

    int pos;

    int uleb() {
        int r = 0, s = 0, x;
        do {
            x = b[pos++] & 0xff;
            r |= (x & 0x7f) << s;
            s += 7;
        } while ((x & 0x80) != 0);
        return r;
    }

    String string(int idx) {
        if (strings[idx] != null) return strings[idx];
        pos = base + u4(base + h(0x3c) + idx * 4);
        int len = uleb();
        StringBuilder s = new StringBuilder(len);
        while (true) {   // modified UTF-8
            int c = b[pos++] & 0xff;
            if (c == 0) break;
            if (c < 0x80) s.append((char) c);
            else if ((c & 0xe0) == 0xc0) s.append((char) ((c & 0x1f) << 6 | (b[pos++] & 0x3f)));
            else s.append((char) ((c & 0x0f) << 12 | (b[pos++] & 0x3f) << 6 | (b[pos++] & 0x3f)));
        }
        return strings[idx] = s.toString();
    }

    String type(int idx) {
        if (types[idx] != null) return types[idx];
        return types[idx] = javaType(string(u4(base + h(0x44) + idx * 4)));
    }

    /** a dex descriptor as map-car.py's jtype writes it: Lcom/x/Y; -> com.x.Y, [I -> int[] */
    public static String javaType(String d) {
        int dims = 0;
        while (d.charAt(dims) == '[') dims++;
        String t = d.substring(dims);
        String j;
        switch (t.charAt(0)) {
            case 'V': j = "void"; break;
            case 'Z': j = "boolean"; break;
            case 'B': j = "byte"; break;
            case 'S': j = "short"; break;
            case 'C': j = "char"; break;
            case 'I': j = "int"; break;
            case 'J': j = "long"; break;
            case 'F': j = "float"; break;
            case 'D': j = "double"; break;
            default: j = t.substring(1, t.length() - 1).replace('/', '.');
        }
        StringBuilder s = new StringBuilder(j);
        for (int i = 0; i < dims; i++) s.append("[]");
        return s.toString();
    }

    Map<String, Cls> classes() {
        strings = new String[h(0x38)];
        types = new String[h(0x40)];
        Map<String, Cls> out = new LinkedHashMap<String, Cls>();
        int defs = h(0x64);
        for (int i = 0; i < h(0x60); i++) {
            int d = base + defs + i * 32;
            Cls c = new Cls();
            c.name = type(u4(d));
            int sup = u4(d + 8);
            c.sup = sup == -1 ? null : type(sup);
            int data = u4(d + 24);
            if (data != 0) classData(c, base + data);
            out.put(c.name, c);
        }
        return out;
    }

    void classData(Cls c, int at) {
        pos = at;
        int sf = uleb(), inf = uleb(), dm = uleb(), vm = uleb();
        int idx = 0;
        for (int i = 0; i < sf + inf; i++) {
            if (i == sf) idx = 0;
            idx += uleb();
            int acc = uleb();
            int save = pos;
            int f = base + h(0x54) + idx * 8;
            Member m = new Member();
            m.type = type(u2(f + 2));
            m.name = string(u4(f + 4));
            m.isStatic = (acc & 0x8) != 0;
            c.fields.add(m);
            pos = save;
        }
        idx = 0;
        for (int i = 0; i < dm + vm; i++) {
            if (i == dm) idx = 0;
            idx += uleb();
            int acc = uleb();
            int code = uleb();
            int save = pos;
            int mo = base + h(0x5c) + idx * 8;
            Member m = new Member();
            m.name = string(u4(mo + 4));
            int proto = base + h(0x4c) + u2(mo + 2) * 12;
            m.ret = type(u4(proto + 4));
            m.params = new ArrayList<String>();
            int pl = u4(proto + 8);
            if (pl != 0) {
                int n = u4(base + pl);
                for (int k = 0; k < n; k++) m.params.add(type(u2(base + pl + 4 + k * 2)));
            }
            m.isStatic = (acc & 0x8) != 0;
            m.isVirtual = i >= dm;
            m.code = code == 0 ? 0 : base + code;
            m.strings = new LinkedHashSet<String>();
            if (code != 0) m.sig = walk(base + code, m.strings);
            c.strings.addAll(m.strings);
            if (m.strings.isEmpty()) m.strings = java.util.Collections.emptySet();   // most methods: the framework has ~100k
            c.methods.add(m);
            pos = save;
        }
    }

    /**
     * Walks a code item by instruction sizes: adds its const-string / const-string/jumbo operands
     * to out, and returns its code signature (tools/dexsig.py computes the same): 64-bit FNV-1a
     * over each instruction's opcode, plus the second code unit of Dalvik's quickened opcodes
     * (field offset, vtable slot, inline index), which say what the code touches when names
     * can't. Table payloads are skipped. Registers, constants and table indices are left out,
     * so the same source gives the same signature in builds whose names differ.
     */
    long walk(int item, Set<String> out) {
        int n = u4(item + 12), start = item + 16;
        long sig = 0xcbf29ce484222325L;
        int i = 0;
        while (i < n) {
            int at = start + i * 2, unit = u2(at), op = unit & 0xff;
            if (op == 0x00 && unit != 0) {   // switch and array data tables sit between instructions
                if (unit == 0x0100) i += 4 + u2(at + 2) * 2;
                else if (unit == 0x0200) i += 2 + u2(at + 2) * 4;
                else if (unit == 0x0300) i += 4 + (int) (((long) u2(at + 2) * u4(at + 4) + 1) / 2);
                else i += 1;
                continue;
            }
            sig = (sig ^ op) * 0x100000001b3L;
            if (QUICK[op]) {
                int v = u2(at + 2);
                sig = (sig ^ (v & 0xff)) * 0x100000001b3L;
                sig = (sig ^ (v >>> 8)) * 0x100000001b3L;
            }
            if (op == 0x1a) out.add(string(u2(at + 2)));
            else if (op == 0x1b) out.add(string(u4(at + 2)));
            i += SIZE[op];
        }
        return sig;
    }

    /**
     * A call in a method's code: the instruction family ("invoke-static", ...), the method it calls
     * ("com.x.Y.name(params)ret" in Java types; null for a quickened call, whose target is a
     * vtable slot), and the families of the next instructions.
     */
    public static final class Site {
        public String op, target;
        public final List<String> next = new ArrayList<String>();
        public final List<String> nextTargets = new ArrayList<String>();   // target of each of them (null if none)
    }

    static File lastFile;
    static Dex lastDex;

    /** the calls in one method's code, read again from its class's file (one file kept cached) */
    public static List<Site> sites(Cls c, Member m, int window) throws Exception {
        if (m.code == 0 || c.src == null) return new ArrayList<Site>();
        if (!c.src.equals(lastFile)) {
            Dex d = new Dex(load(c.src));
            d.strings = new String[d.h(0x38)];
            d.types = new String[d.h(0x40)];
            lastDex = d;
            lastFile = c.src;
        }
        return lastDex.sitesAt(m.code, window);
    }

    static byte[] load(File f) throws Exception {
        String n = f.getName();
        if (n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".zip")) {
            ZipFile z = new ZipFile(f);
            try {
                return readAll(z.getInputStream(z.getEntry("classes.dex")));
            } finally {
                z.close();
            }
        }
        return readAll(new FileInputStream(f));
    }

    /** "com.x.Y.name(a,b)ret" for a method_ids entry */
    String methodRef(int idx) {
        int mo = base + h(0x5c) + idx * 8;
        int proto = base + h(0x4c) + u2(mo + 2) * 12;
        StringBuilder s = new StringBuilder(type(u2(mo))).append('.').append(string(u4(mo + 4))).append('(');
        int pl = u4(proto + 8);
        if (pl != 0) {
            int n = u4(base + pl);
            for (int k = 0; k < n; k++) s.append(k > 0 ? "," : "").append(type(u2(base + pl + 4 + k * 2)));
        }
        return s.append(')').append(type(u4(proto + 4))).toString();
    }

    List<Site> sitesAt(int item, int window) {
        int n = u4(item + 12), start = item + 16;
        List<int[]> ins = new ArrayList<int[]>();   // {op, at}
        int i = 0;
        while (i < n) {
            int at = start + i * 2, unit = u2(at), op = unit & 0xff;
            if (op == 0x00 && unit != 0) {
                if (unit == 0x0100) i += 4 + u2(at + 2) * 2;
                else if (unit == 0x0200) i += 2 + u2(at + 2) * 4;
                else if (unit == 0x0300) i += 4 + (int) (((long) u2(at + 2) * u4(at + 4) + 1) / 2);
                else i += 1;
                continue;
            }
            ins.add(new int[] { op, at });
            i += SIZE[op];
        }
        List<Site> out = new ArrayList<Site>();
        for (int k = 0; k < ins.size(); k++) {
            int op = ins.get(k)[0];
            String fam = family(op);
            if (!fam.startsWith("invoke")) continue;
            Site st = new Site();
            st.op = fam;
            st.target = target(op, ins.get(k)[1]);
            for (int j = k + 1; j < ins.size() && j <= k + window; j++) {
                st.next.add(family(ins.get(j)[0]));
                st.nextTargets.add(family(ins.get(j)[0]).startsWith("invoke") ? target(ins.get(j)[0], ins.get(j)[1]) : null);
            }
            out.add(st);
        }
        return out;
    }

    /** the method an invoke names, or null for a quickened one (vtable slot, inline index) */
    String target(int op, int at) {
        if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) return methodRef(u2(at + 2));
        return null;
    }

    /**
     * An instruction's family as baksmali writes it after deodexing: a quickened or volatile
     * op counts as the op it replaced (iget-object-quick is an iget-object, invoke-virtual-quick
     * an invoke-virtual), and a /range, /16 or /jumbo form as its plain one.
     */
    static String family(int op) {
        String f = FAMILY[op];
        return f == null ? "op-" + Integer.toHexString(op) : f;
    }

    static final String[] FAMILY = new String[256];

    static {
        String[][] names = {
            {"0", "nop"}, {"1-9", "move"}, {"a", "move-result"}, {"b", "move-result-wide"}, {"c", "move-result-object"},
            {"d", "move-exception"}, {"e", "return-void"}, {"f", "return"}, {"10", "return-wide"}, {"11", "return-object"},
            {"12-15", "const"}, {"16-19", "const-wide"}, {"1a-1b", "const-string"}, {"1c", "const-class"},
            {"1d", "monitor-enter"}, {"1e", "monitor-exit"}, {"1f", "check-cast"}, {"20", "instance-of"},
            {"21", "array-length"}, {"22", "new-instance"}, {"23", "new-array"}, {"24-25", "filled-new-array"},
            {"26", "fill-array-data"}, {"27", "throw"}, {"28-2a", "goto"}, {"2b", "packed-switch"}, {"2c", "sparse-switch"},
            {"2d-31", "cmp"}, {"32", "if-eq"}, {"33", "if-ne"}, {"34", "if-lt"}, {"35", "if-ge"}, {"36", "if-gt"},
            {"37", "if-le"}, {"38", "if-eqz"}, {"39", "if-nez"}, {"3a", "if-ltz"}, {"3b", "if-gez"}, {"3c", "if-gtz"},
            {"3d", "if-lez"}, {"44-4a", "aget"}, {"4b-51", "aput"}, {"52", "iget"}, {"53", "iget-wide"},
            {"54", "iget-object"}, {"55", "iget-boolean"}, {"56", "iget-byte"}, {"57", "iget-char"}, {"58", "iget-short"},
            {"59", "iput"}, {"5a", "iput-wide"}, {"5b", "iput-object"}, {"5c", "iput-boolean"}, {"5d", "iput-byte"},
            {"5e", "iput-char"}, {"5f", "iput-short"}, {"60", "sget"}, {"61", "sget-wide"}, {"62", "sget-object"},
            {"63", "sget-boolean"}, {"64", "sget-byte"}, {"65", "sget-char"}, {"66", "sget-short"}, {"67", "sput"},
            {"68", "sput-wide"}, {"69", "sput-object"}, {"6a", "sput-boolean"}, {"6b", "sput-byte"}, {"6c", "sput-char"},
            {"6d", "sput-short"}, {"6e", "invoke-virtual"}, {"6f", "invoke-super"}, {"70", "invoke-direct"},
            {"71", "invoke-static"}, {"72", "invoke-interface"}, {"74", "invoke-virtual"}, {"75", "invoke-super"},
            {"76", "invoke-direct"}, {"77", "invoke-static"}, {"78", "invoke-interface"}, {"7b-8f", "unop"},
            {"90-af", "binop"}, {"b0-cf", "binop/2addr"}, {"d0-e2", "binop/lit"},
            {"e3", "iget"}, {"e4", "iput"}, {"e5", "sget"}, {"e6", "sput"}, {"e7", "iget-object"}, {"e8", "iget-wide"},
            {"e9", "iput-wide"}, {"ea", "sget-wide"}, {"eb", "sput-wide"}, {"ee-ef", "execute-inline"},
            {"f0", "invoke-direct"}, {"f2", "iget"}, {"f3", "iget-wide"}, {"f4", "iget-object"}, {"f5", "iput"},
            {"f6", "iput-wide"}, {"f7", "iput-object"}, {"f8-f9", "invoke-virtual"}, {"fa-fb", "invoke-super"},
            {"fc", "iput-object"}, {"fd", "sget-object"}, {"fe", "sput-object"},
        };
        for (String[] r : names) {
            String[] ab = r[0].split("-");
            int a = Integer.parseInt(ab[0], 16), b = Integer.parseInt(ab[ab.length - 1], 16);
            for (int op = a; op <= b; op++) FAMILY[op] = r[1];
        }
    }

    /** a method's code signature as maps write it ("sig"): 16 hex digits */
    public static String sigHex(long sig) {
        String h = Long.toHexString(sig);
        while (h.length() < 16) h = "0" + h;
        return h;
    }

    /** Dalvik's quickened opcodes (the volatile field ops keep a field index, which moves between builds) */
    static final boolean[] QUICK = new boolean[256];

    /** instruction sizes in 16-bit units, by opcode (dex 035, plus Dalvik's odex opcodes 0xe3-0xfe) */
    static final int[] SIZE = new int[256];

    static {
        int[][] ranges = {
            {0x00, 0x01, 1}, {0x02, 0x02, 2}, {0x03, 0x03, 3}, {0x04, 0x04, 1}, {0x05, 0x05, 2}, {0x06, 0x06, 3},
            {0x07, 0x07, 1}, {0x08, 0x08, 2}, {0x09, 0x09, 3}, {0x0a, 0x12, 1}, {0x13, 0x13, 2}, {0x14, 0x14, 3},
            {0x15, 0x16, 2}, {0x17, 0x17, 3}, {0x18, 0x18, 5}, {0x19, 0x1a, 2}, {0x1b, 0x1b, 3}, {0x1c, 0x1c, 2},
            {0x1d, 0x1e, 1}, {0x1f, 0x20, 2}, {0x21, 0x21, 1}, {0x22, 0x23, 2}, {0x24, 0x26, 3}, {0x27, 0x28, 1},
            {0x29, 0x29, 2}, {0x2a, 0x2c, 3}, {0x2d, 0x3d, 2}, {0x3e, 0x43, 1}, {0x44, 0x6d, 2}, {0x6e, 0x72, 3},
            {0x73, 0x73, 1}, {0x74, 0x78, 3}, {0x79, 0x8f, 1}, {0x90, 0xaf, 2}, {0xb0, 0xcf, 1}, {0xd0, 0xe2, 2},
            {0xe3, 0xeb, 2}, {0xec, 0xec, 1}, {0xed, 0xed, 2}, {0xee, 0xf0, 3}, {0xf1, 0xf1, 1}, {0xf2, 0xf7, 2},
            {0xf8, 0xfb, 3}, {0xfc, 0xfe, 2}, {0xff, 0xff, 1},
        };
        for (int[] r : ranges) for (int op = r[0]; op <= r[1]; op++) SIZE[op] = r[2];
        for (int op = 0xee; op <= 0xef; op++) QUICK[op] = true;   // execute-inline(/range): inline index
        for (int op = 0xf2; op <= 0xfb; op++) QUICK[op] = true;   // iget/iput-*-quick: offset; invoke-*-quick: vtable slot
    }
}
