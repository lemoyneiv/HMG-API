package hmgapi.vehicle;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import hmgapi.hook.Points;

/**
 * Values the car only pushes to listeners (no getter): the steering wheel heat and
 * rear defogger state (body control's onRemoteTeleHeatingStatusChanged), and the outside
 * temperature (climate control's onDatcStatus: DatcStatus, sent on registration and on
 * change; the system service's own ambient callbacks send 0 on the EV6), and the
 * ambient light (the system service's SML_STATUS_CHANGED broadcast, passed on by
 * hmgapi.hook.LightRelay in system_server: a binder registered here as a receiver was
 * accepted but never got a broadcast on the EV6), and the battery info the cluster
 * service answers requestBatteryInfoReq with (onHandleBatteryInfoResp, to every
 * listener: the navigation's own stays registered).
 *
 * A listener interface has dozens of callbacks; implementing it would mean a class
 * built against each car's exact interface. Instead this is a plain binder handed
 * to the car through its own proxy class (Stub.asInterface), answering only the
 * callbacks it reads, by the transaction codes the car's code defines (hook map
 * fields); every other callback is acknowledged and ignored.
 */
final class CarCallbacks {
    /** outside temperature in tenths (Fahrenheit, and Celsius when the car's is valid); MIN_VALUE until climate control has said */
    volatile int outsideF10 = Integer.MIN_VALUE, outsideC10 = Integer.MIN_VALUE;
    /** body control's heating status: steering wheel heat, rear defogger (raw); MIN_VALUE: not heard yet */
    volatile int wheelHeat = Integer.MIN_VALUE, defogger = Integer.MIN_VALUE;

    /** the cluster's last battery info (8 ints: [1] minimum pack temperature °C, [5] conditioning indicator), and when; null until heard */
    volatile int[] battery;
    volatile long batteryAt;

    /** the ambient light's last status: r, g, b (2 bytes each), brightness, dimmed while driving, drive mode; null until heard */
    volatile byte[] sml;

    private Listener system, bcm, cluster;
    private Object datcProxy;
    private long lastDatcAsk;
    private String lastFailure, lastBcmFailure, lastClusterFailure;   // logged once, not every tick
    private boolean lightAsked;

    static boolean validAmbientF(float f) {
        return Commands.validAmbientF(f);
    }

    /**
     * After STR suspend (QuickBoot), climate and body control's cached push values may be
     * from a prior drive until CAN wakes up; clear them so stale readings aren't served.
     */
    synchronized void onWake() {
        outsideF10 = Integer.MIN_VALUE;
        outsideC10 = Integer.MIN_VALUE;
        wheelHeat = Integer.MIN_VALUE;
        defogger = Integer.MIN_VALUE;
        lastDatcAsk = 0;
    }

    /** a binder answering some transactions of one interface */
    abstract static class Listener extends Binder {
        static final int MAX_OUT_ARRAY = 64 * 1024;   // a callback's array; a larger last int is something else
        final String descriptor;
        final int[] codes;

        Listener(String descriptor, int... codes) {
            this.descriptor = descriptor;
            this.codes = codes;
            // KitKat's Binder.queryLocalInterface (asInterface asks it) throws without a descriptor;
            // no local interface: asInterface wraps this binder in the car's own proxy
            attachInterface(null, descriptor);
        }

        abstract void got(int code, Parcel data);

        @Override
        protected boolean onTransact(int c, Parcel data, Parcel reply, int flags) {
            if (c == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(descriptor);
                return true;
            }
            try {
                for (int code : codes) {
                    if (c != code) continue;
                    data.enforceInterface(descriptor);
                    got(c, data);
                }
            } catch (Throwable t) {
                System.out.println("vehicle: callback " + c + ": " + t);
            }
            if (reply != null) answer(data, reply);
            return true;   // every other callback: acknowledged, ignored
        }

        /**
         * A callback with an `out` array (the car's IBCMListener.onDigitalKeyDataChanged) sends only
         * the array's length, as its last int, and its proxy reads an array of that length back.
         * Without one the proxy throws, in the car's service, and takes system_server down. Callbacks
         * without one never read past the exception, so the extra bytes don't reach them.
         */
        static void answer(Parcel data, Parcel reply) {
            reply.writeNoException();
            int end = data.dataSize();
            if (end < 4) return;
            data.setDataPosition(end - 4);
            int n = data.readInt();
            if (n >= 0 && n <= MAX_OUT_ARRAY) reply.writeByteArray(new byte[n]);
        }
    }

    /** (re)registers when not registered or the car's service went away; called every tick */
    synchronized void ensure() {
        ensureLight();
        if (bcm != null && !alive("framework.car_services.bcm")) {   // the service restarted: look it up again
            Hub.services.remove("framework.car_services.bcm");
            bcm = null;
        }
        if (bcm == null) {
            try {
                Object svc = Hub.bcm();
                if (svc != null) {
                    int code = (Integer) Points.get(null, "framework.bcm_listener_stub.heating", null);
                    Listener l = new Listener(Points.cls(null, "framework.bcm_listener").getName(), code) {
                        @Override
                        void got(int c, Parcel d) {
                            int w = d.readInt(), f = d.readInt();
                            if (wheelHeat == Integer.MIN_VALUE) System.out.println("vehicle: heating status, first values: wheel " + w + ", defogger " + f);
                            wheelHeat = w;
                            defogger = f;
                        }
                    };
                    Object proxy = Points.call(null, "framework.bcm_listener_stub.as_interface", null, l);
                    Points.call(svc.getClass().getClassLoader(), "framework.bcm_service.register_listener", svc, proxy, l);
                    bcm = l;
                    lastBcmFailure = null;
                    System.out.println("vehicle: listening to the body control service (wheel heat, defogger)");
                }
            } catch (Throwable t) {
                String why = String.valueOf(t.getCause() != null ? t.getCause() : t);
                if (!why.equals(lastBcmFailure)) System.out.println("vehicle: body control listener: " + why + " (trying again every few seconds)");
                lastBcmFailure = why;
                Hub.services.remove("framework.car_services.bcm");
            }
        }
        if (cluster != null && !alive("framework.car_services.cluster")) {
            Hub.services.remove("framework.car_services.cluster");
            cluster = null;
        }
        if (cluster == null) {
            try {
                Object svc = Hub.cluster();
                if (svc != null) {
                    int code = (Integer) Points.get(null, "framework.cluster_listener_stub.battery_info", null);
                    Listener l = new Listener(Points.cls(null, "framework.cluster_listener").getName(), code) {
                        @Override
                        void got(int c, Parcel d) {
                            int[] v = d.createIntArray();
                            if (battery == null) System.out.println("vehicle: battery info, first values: " + java.util.Arrays.toString(v));
                            battery = v;
                            batteryAt = android.os.SystemClock.elapsedRealtime();
                        }
                    };
                    Object proxy = Points.call(null, "framework.cluster_listener_stub.as_interface", null, l);
                    Points.call(svc.getClass().getClassLoader(), "framework.cluster_service.add_listener", svc, proxy);
                    cluster = l;
                    lastClusterFailure = null;
                    System.out.println("vehicle: listening to the cluster service (battery info)");
                }
            } catch (Throwable t) {
                String why = String.valueOf(t.getCause() != null ? t.getCause() : t);
                if (!why.equals(lastClusterFailure)) System.out.println("vehicle: cluster listener: " + why + " (trying again every few seconds)");
                lastClusterFailure = why;
                Hub.services.remove("framework.car_services.cluster");
            }
        }
        if (system != null && !alive("framework.car_services.datc")) {
            Hub.services.remove("framework.car_services.datc");
            system = null;
            datcProxy = null;
        }
        if (system == null) {
            try {
                Object svc = Hub.datc();
                if (svc != null) {
                    int code = (Integer) Points.get(null, "framework.datc_listener_stub.status", null);
                    final Object creator = Points.get(null, "framework.datc_status.creator", null);
                    Listener l = new Listener(Points.cls(null, "framework.datc_listener").getName(), code) {
                        @Override
                        void got(int c, Parcel d) {
                            if (d.readInt() == 0) return;   // a null status
                            Object st = ((android.os.Parcelable.Creator<?>) creator).createFromParcel(d);
                            float f = (Float) Points.call(null, "framework.datc_status.ambient_f", st);
                            float cc = (Float) Points.call(null, "framework.datc_status.ambient_c", st);
                            int stateC = (Integer) Points.call(null, "framework.datc_status.ambient_state_c", st);
                            if (!validAmbientF(f)) {
                                outsideF10 = Integer.MIN_VALUE;
                                outsideC10 = Integer.MIN_VALUE;
                                return;
                            }
                            boolean first = outsideF10 == Integer.MIN_VALUE;
                            outsideF10 = Math.round(f * 10);
                            outsideC10 = stateC == 255 ? Integer.MIN_VALUE : Math.round(cc * 10);   // 255: Celsius not valid (a US car)
                            if (first) System.out.println("vehicle: climate status, first outside temperature: " + f + " F, " + cc + " C (state " + stateC + ")");
                        }
                    };
                    Object proxy = Points.call(null, "framework.datc_listener_stub.as_interface", null, l);
                    Points.call(svc.getClass().getClassLoader(), "framework.datc_service.register_listener", svc, proxy, l);
                    system = l;
                    datcProxy = proxy;
                    lastDatcAsk = android.os.SystemClock.elapsedRealtime();
                    lastFailure = null;
                    System.out.println("vehicle: listening to climate control (outside temperature)");
                }
            } catch (Throwable t) {
                String why = String.valueOf(t.getCause() != null ? t.getCause() : t);
                if (!why.equals(lastFailure)) System.out.println("vehicle: climate listener: " + why + " (trying again every few seconds)");
                lastFailure = why;
                Hub.services.remove("framework.car_services.datc");
            }
        } else if (outsideF10 == Integer.MIN_VALUE && datcProxy != null
                && android.os.SystemClock.elapsedRealtime() - lastDatcAsk >= 4000) {
            try {
                Object svc = Hub.datc();
                if (svc != null) {
                    lastDatcAsk = android.os.SystemClock.elapsedRealtime();
                    Points.call(svc.getClass().getClassLoader(), "framework.datc_service.register_listener", svc, datcProxy, system);
                }
            } catch (Throwable t) {
                Hub.services.remove("framework.car_services.datc");
                system = null;
                datcProxy = null;
            }
        }
    }

    /**
     * The pack's minimum temperature (whole °C): asks the cluster, waits up to waitMs for
     * the answer. Integer.MIN_VALUE when there's none, or it isn't 8 values with a
     * temperature of -40..60 °C (Launchpad's ClusterBatteryTemperatureReader).
     */
    int packTempMin(long waitMs) {
        long asked = android.os.SystemClock.elapsedRealtime();
        try {
            Object svc = Hub.cluster();
            if (svc == null || cluster == null) return Integer.MIN_VALUE;
            Points.call(svc.getClass().getClassLoader(), "framework.cluster_service.battery_info", svc);
        } catch (Throwable t) {
            Hub.services.remove("framework.car_services.cluster");
            return Integer.MIN_VALUE;
        }
        while (batteryAt < asked && android.os.SystemClock.elapsedRealtime() - asked < waitMs) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
        int[] b = battery;
        if (batteryAt < asked || b == null || b.length != 8 || b[1] < -40 || b[1] > 60) return Integer.MIN_VALUE;
        return b[1];
    }

    /** the light's status from LightRelay: hex of r, g, b (2 bytes each), brightness, dimmed, drive mode */
    void lightStatus(String hex) {
        if (hex.length() < 18 || hex.length() % 2 != 0) return;
        byte[] s = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < s.length; i++) s[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return;
        }
        if (sml == null) System.out.println("vehicle: ambient light status heard");
        sml = s;
        if (Hub.commands != null) Hub.commands.lightReported(Hub.CarServices.light(s));
    }

    /** asks the car once for its light's status, so the owner's light is known before a mod changes it */
    synchronized void ensureLight() {
        if (lightAsked) return;
        try {
            Object svc = Hub.system();
            if (svc == null) return;
            Points.call(svc.getClass().getClassLoader(), "framework.system_service.sml_status", svc);
            lightAsked = true;
        } catch (Throwable t) {
            // the system service isn't up yet: next tick
        }
    }

    /** the car's service binder is still there (it restarts with its process) */
    static boolean alive(String namePoint) {
        Object s = Hub.services.get(namePoint);
        if (s == null) return false;
        try {
            IBinder b = (IBinder) s.getClass().getMethod("asBinder").invoke(s);
            return b != null && b.isBinderAlive();
        } catch (Throwable t) {
            return false;
        }
    }
}
