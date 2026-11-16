#!/usr/bin/env python3
"""Code signatures of the methods in an .odex/.dex/.jar/.apk, as the on-car mapper computes them
(core/runtime/java/hmgapi/engine/Dex.java: walk): 64-bit FNV-1a over each instruction's opcode,
plus the second code unit of Dalvik's quickened opcodes (execute-inline, iget/iput-*-quick,
invoke-*-quick: inline index, field offset, vtable slot). Table payloads are skipped; registers,
constants and table indices are left out, so the same source gives the same signature in builds
whose names differ.

A map keeps a "sig" on each method found by a smali pattern only; the on-car mapper takes another
car's name for such a point only when this car's method of that name has the same code.

usage: dexsig.py <file> [<class> [<method>]]    prints class method(params) sig
"""
import struct, sys, zipfile

SIZE = [0] * 256
for lo, hi, n in ((0x00, 0x01, 1), (0x02, 0x02, 2), (0x03, 0x03, 3), (0x04, 0x04, 1), (0x05, 0x05, 2), (0x06, 0x06, 3),
                  (0x07, 0x07, 1), (0x08, 0x08, 2), (0x09, 0x09, 3), (0x0a, 0x12, 1), (0x13, 0x13, 2), (0x14, 0x14, 3),
                  (0x15, 0x16, 2), (0x17, 0x17, 3), (0x18, 0x18, 5), (0x19, 0x1a, 2), (0x1b, 0x1b, 3), (0x1c, 0x1c, 2),
                  (0x1d, 0x1e, 1), (0x1f, 0x20, 2), (0x21, 0x21, 1), (0x22, 0x23, 2), (0x24, 0x26, 3), (0x27, 0x28, 1),
                  (0x29, 0x29, 2), (0x2a, 0x2c, 3), (0x2d, 0x3d, 2), (0x3e, 0x43, 1), (0x44, 0x6d, 2), (0x6e, 0x72, 3),
                  (0x73, 0x73, 1), (0x74, 0x78, 3), (0x79, 0x8f, 1), (0x90, 0xaf, 2), (0xb0, 0xcf, 1), (0xd0, 0xe2, 2),
                  (0xe3, 0xeb, 2), (0xec, 0xec, 1), (0xed, 0xed, 2), (0xee, 0xf0, 3), (0xf1, 0xf1, 1), (0xf2, 0xf7, 2),
                  (0xf8, 0xfb, 3), (0xfc, 0xfe, 2), (0xff, 0xff, 1)):
    for op in range(lo, hi + 1):
        SIZE[op] = n
QUICK = set(range(0xee, 0xf0)) | set(range(0xf2, 0xfc))
PRIM = {'V': 'void', 'Z': 'boolean', 'B': 'byte', 'S': 'short', 'C': 'char', 'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double'}
M64 = (1 << 64) - 1


def jtype(d):
    dims = len(d) - len(d.lstrip('['))
    t = d[dims:]
    return (PRIM.get(t) or t[1:-1].replace('/', '.')) + '[]' * dims


def walk(b, item):
    """the signature of the code item at b[item:]"""
    u2 = lambda o: struct.unpack_from('<H', b, o)[0]
    n, start, sig, i = struct.unpack_from('<I', b, item + 12)[0], item + 16, 0xcbf29ce484222325, 0
    while i < n:
        at = start + i * 2
        unit = u2(at)
        op = unit & 0xff
        if op == 0 and unit:   # switch and array data tables sit between instructions
            if unit == 0x0100:
                i += 4 + u2(at + 2) * 2
            elif unit == 0x0200:
                i += 2 + u2(at + 2) * 4
            elif unit == 0x0300:
                i += 4 + (u2(at + 2) * struct.unpack_from('<I', b, at + 4)[0] + 1) // 2
            else:
                i += 1
            continue
        sig = ((sig ^ op) * 0x100000001b3) & M64
        if op in QUICK:
            v = u2(at + 2)
            sig = ((sig ^ (v & 0xff)) * 0x100000001b3) & M64
            sig = ((sig ^ (v >> 8)) * 0x100000001b3) & M64
        i += SIZE[op]
    return '%016x' % sig


# a code item both implementations must sign alike (dexsig-test, MapProbeTest): registers,
# constants and table contents differ between the two copies, the signature must not
SAMPLE_UNITS = [0x0162, 0x0007,      # sget-object v1, field@7
                0x011d,              # monitor-enter v1
                0x10f2, 0x0010,      # iget-quick v0, v1, [obj+0x10]
                0x011e,              # monitor-exit v1
                0x002b, 0x0004, 0x0000,  # packed-switch v0, +4
                0x000f,              # return v0
                0x0100, 0x0001, 0x0000, 0x0000, 0x0005, 0x0000]   # packed-switch payload, 1 entry
SAMPLE_SIG = None   # set below


def sample_item(units):
    return struct.pack('<HHHHII', 2, 1, 0, 0, 0, len(units)) + struct.pack('<%dH' % len(units), *units)


def load(path):
    if path.endswith(('.jar', '.apk', '.zip')):
        with zipfile.ZipFile(path) as z:
            return z.read('classes.dex') if 'classes.dex' in z.namelist() else b''
    return open(path, 'rb').read()


def sigs(path):
    """{(class, method, (param types...)): 16-hex signature} for every method with code"""
    b = load(path)
    if not b:
        return {}
    base = struct.unpack_from('<I', b, 8)[0] if b[:4] == b'dey\n' else 0
    h = lambda o: struct.unpack_from('<I', b, base + o)[0]
    u2 = lambda o: struct.unpack_from('<H', b, o)[0]
    u4 = lambda o: struct.unpack_from('<I', b, o)[0]

    def uleb(p):
        r = s = 0
        while True:
            x = b[p]
            p += 1
            r |= (x & 0x7f) << s
            s += 7
            if not x & 0x80:
                return r, p

    scache, tcache = {}, {}

    def string(i):
        if i not in scache:
            _, p = uleb(base + u4(base + h(0x3c) + i * 4))
            e = b.index(b'\0', p)
            scache[i] = b[p:e].decode('utf-8', 'replace')   # modified UTF-8: names here are ASCII
        return scache[i]

    def typ(i):
        if i not in tcache:
            tcache[i] = jtype(string(u4(base + h(0x44) + i * 4)))
        return tcache[i]

    out = {}
    for c in range(h(0x60)):
        d = base + h(0x64) + c * 32
        cls, data = typ(u4(d)), u4(d + 24)
        if not data:
            continue
        p = base + data
        sf, p = uleb(p)
        inf, p = uleb(p)
        dm, p = uleb(p)
        vm, p = uleb(p)
        for _ in range(sf + inf):
            _, p = uleb(p)
            _, p = uleb(p)
        idx = 0
        for k in range(dm + vm):
            if k == dm:
                idx = 0
            di, p = uleb(p)
            idx += di
            _, p = uleb(p)
            code, p = uleb(p)
            if not code:
                continue
            mo = base + h(0x5c) + idx * 8
            proto = base + h(0x4c) + u2(mo + 2) * 12
            pl = u4(proto + 8)
            params = tuple(typ(u2(base + pl + 4 + j * 2)) for j in range(u4(base + pl))) if pl else ()
            out[(cls, string(u4(mo + 4)), params)] = walk(b, base + code)
    return out


SAMPLE_SIG = walk(sample_item(SAMPLE_UNITS), 0)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for (c, m, ps), s in sorted(sigs(sys.argv[1]).items()):
        if len(sys.argv) > 2 and c != sys.argv[2] or len(sys.argv) > 3 and m != sys.argv[3]:
            continue
        print('%s %s(%s) %s' % (c, m, ','.join(ps), s))
