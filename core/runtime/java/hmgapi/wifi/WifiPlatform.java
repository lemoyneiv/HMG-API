package hmgapi.wifi;

import java.util.List;

/**
 * Hardware and OS abstraction for HomeWifiController (no Android SDK imports so the full
 * state machine is deterministically unit-testable in --fast host runs).
 */
public interface WifiPlatform {
    int WIFI_STATE_DISABLING = 0;
    int WIFI_STATE_DISABLED = 1;
    int WIFI_STATE_ENABLING = 2;
    int WIFI_STATE_ENABLED = 3;
    int WIFI_STATE_UNKNOWN = 4;

    int AP_STATE_DISABLING = 10;
    int AP_STATE_DISABLED = 11;
    int AP_STATE_ENABLING = 12;
    int AP_STATE_ENABLED = 13;

    enum SupState {
        DISCONNECTED,
        INACTIVE,
        SCANNING,
        AUTHENTICATING,
        ASSOCIATING,
        ASSOCIATED,
        FOUR_WAY_HANDSHAKE,
        GROUP_HANDSHAKE,
        COMPLETED,
        DORMANT,
        UNINITIALIZED,
        INVALID
    }

    final class ConfiguredNet {
        public final int netId;
        public final String ssid;
        public final boolean open;
        public final boolean enabled;

        public ConfiguredNet(int netId, String ssid, boolean open, boolean enabled) {
            this.netId = netId;
            this.ssid = WifiSecurity.cleanSsid(ssid);
            this.open = open;
            this.enabled = enabled;
        }
    }

    final class ConnectionSnapshot {
        public final SupState supState;
        public final int netId;
        public final String ssid;
        public final String bssid;
        public final int rssiDbm;
        public final int freqMhz;
        public final int wifiInfoIp;
        public final int dhcpIp;
        public final int dhcpGateway;
        public final int dhcpNetmask;
        public final int dhcpDns1;
        public final int dhcpDns2;

        public ConnectionSnapshot(SupState supState, int netId, String ssid, String bssid,
                                  int rssiDbm, int freqMhz, int wifiInfoIp,
                                  int dhcpIp, int dhcpGateway, int dhcpNetmask, int dhcpDns1, int dhcpDns2) {
            this.supState = supState == null ? SupState.DISCONNECTED : supState;
            this.netId = netId;
            this.ssid = WifiSecurity.cleanSsid(ssid);
            this.bssid = bssid == null ? "" : bssid;
            this.rssiDbm = rssiDbm;
            this.freqMhz = freqMhz;
            this.wifiInfoIp = wifiInfoIp;
            this.dhcpIp = dhcpIp;
            this.dhcpGateway = dhcpGateway;
            this.dhcpNetmask = dhcpNetmask;
            this.dhcpDns1 = dhcpDns1;
            this.dhcpDns2 = dhcpDns2;
        }
    }

    final class VehicleSnapshot {
        public final String power;   // "on", "delay_mode", "adm", "off", ""
        public final boolean parked;
        public final boolean gearPark;

        public VehicleSnapshot(String power, boolean parked, boolean gearPark) {
            this.power = power == null ? "" : power.trim();
            this.parked = parked;
            this.gearPark = gearPark;
        }

        public boolean isPowerOn() {
            return "on".equalsIgnoreCase(power) || power.isEmpty();
        }
    }

    interface Events {
        void onApStateChanged(int apState);
        void onWifiStateChanged(int wifiState);
        void onScanResultsAvailable();
        void onSupplicantStateChanged(SupState state, boolean authError);
        void onNetworkConnected();
    }

    void setEventListener(Events listener);

    long elapsedRealtimeMs();
    long uptimeMillis();
    long wallEpochSec();
    String bootId();

    VehicleSnapshot readVehicle();

    boolean isSoftApActiveOrEnabling();
    int getSoftApMode();
    void captureSoftApConfig();
    boolean disableSoftAp(int currentMode);
    boolean restoreSoftAp(int mode);

    int getWifiState();
    boolean setWifiEnabled(boolean enabled);
    boolean startScan();
    List<HomeWifiStore.SeenNetwork> getScanResults();
    List<ConfiguredNet> getConfiguredNetworks();

    int upsertWifiNetwork(String ssid, String psk, boolean open, String capabilities, boolean forceUpdateCredentials);
    boolean enableOnlyNetwork(int netId);
    boolean enableNetwork(int netId, boolean disableOthers);
    boolean disableNetworkInRam(int netId);
    boolean removeWifiNetwork(int netId);
    boolean reconnect();
    boolean reassociate();
    boolean disconnect();

    ConnectionSnapshot getConnectionSnapshot();

    boolean routeUp(String ip, String gateway, String dns1, String dns2);
    void routeDown();

    void scheduleDelayed(String tag, long delayMs, Runnable task);
    void cancelScheduled(String tag);
    void log(String line);
}
