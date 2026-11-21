#!/bin/sh
# Off-car test of HMG-API's BLE stack:
#   1. core/loader/ble.c's Bluedroid HCI/ACL coexistence filter (non-blocking O_NONBLOCK
#      pipe backpressure, 0x3E capture and immediate LE handle tracking, Classic vs LE ACL
#      routing, 0x13 mixed Classic+LE credit zeroing, 0x0E copy-and-forward, 0x05 untrack,
#      and adapter-restart shutdown/restore);
#   2. BleAuth caller authorization via hmgapid public/services.txt session IDs & UID map,
#      BleSession non-blocking bounded queue, BleCentral scoped LE_Host_Support & BT off/on
#      recovery, shared ACL buffer guard, 27-byte LE ACL fragmentation/reassembly, live
#      per-mod revocation, and the hmgapi.client.Ble client library.
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

cc -DBLE_HOST_TEST -O2 -Wall -o "$T/ble-filter-test" core/runtime/test/ble_filter_test.c
"$T/ble-filter-test"

# Minimal compile-only LocalSocket stand-in so Ble.java compiles in --fast runs (no Android SDK)
mkdir -p "$T/src/android/net" "$T/classes"
cat > "$T/src/android/net/LocalSocketAddress.java" <<'J'
package android.net;
public class LocalSocketAddress {
    public enum Namespace { ABSTRACT, RESERVED, FILESYSTEM }
    public LocalSocketAddress(String name, Namespace ns) {}
}
J
cat > "$T/src/android/net/LocalSocket.java" <<'J'
package android.net;
import java.io.*;
public class LocalSocket implements Closeable {
    public void connect(LocalSocketAddress a) throws IOException { throw new IOException("stub"); }
    public void setSoTimeout(int ms) throws IOException {}
    public InputStream getInputStream() throws IOException { return null; }
    public OutputStream getOutputStream() throws IOException { return null; }
    public void close() throws IOException {}
}
J

javac --release 8 -Xlint:-options -nowarn -d "$T/classes" \
    "$T/src/android/net/LocalSocketAddress.java" \
    "$T/src/android/net/LocalSocket.java" \
    core/runtime/java/hmgapi/ble/*.java \
    core/client/java/hmgapi/client/Ble.java \
    core/runtime/test/hmgapi/ble/BleTest.java
java -cp "$T/classes" hmgapi.ble.BleTest
