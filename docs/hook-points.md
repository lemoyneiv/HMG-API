# Hook points

<!-- Made by tools/mkhookdoc.py from core/hookpoints/*.json and maps/. Don't edit by hand: change
     the catalogue, then run tools/mkhookdoc.py. -->

A hook point is a stable name for a class, method, constructor or field in the car's
own software. A mod uses the name, never the app's own (often obfuscated, per-build)
one, and each car's map says what the name is on that car. In a mod's hooks:
`ctx.hook(cl, "<point>", new MethodHook() {...})` to hook a method, and
`hmgapi.hook.Points` (`call`, `get`, `cls`, `method`, `field`, `newInstance`, `has`) to
use one. A point a car's map lacks is off on that car, and the HMG-API app says so.

How points are described and found: [core/hookpoints/README.md](../core/hookpoints/README.md).
Need one that isn't here? Open a "New hook point" issue.

Every point and map lives here, in HMG-API, including the ones only a mod in its own repo
uses (the wireless mod's, for one): a mod gets new points through an HMG-API release and its
mod SDK ([mod-repos.md](mod-repos.md), section 4).

351 hook points. **Cars** (`maps/`):

| Car | Map | Points |
|---|---|---|
| Hyundai Palisade USA 260903 | mapped on cars by HMG-API's own mapper | 343 of 351 |
| Hyundai Ioniq 5 CAN 260206 | mapped from stock files | 351 of 351 |
| Hyundai Ioniq 5 USA 260507 | mapped from stock files | 351 of 351 |
| Hyundai Ioniq 5 USA 260903 | mapped on cars by HMG-API's own mapper | 351 of 351 |
| Kia EV6 CAN 260206 | mapped from stock files | 351 of 351 |
| Kia EV6 USA 260507 | checked on a car | 351 of 351 |
| Kia Carnival USA 260507 | mapped on cars by HMG-API's own mapper | 343 of 351 |
| Kia Sportage USA 260507 | mapped on cars by HMG-API's own mapper | 351 of 351 |
| Kia Telluride USA 260507 | mapped on cars by HMG-API's own mapper | 351 of 351 |
| Kia Niro EV USA 260507 | mapped on cars by HMG-API's own mapper | 343 of 351 |

In the tables, **Cars** is "all" when every car above has the point, otherwise the cars without it.

## framework

Android framework and the car's service interfaces (any process).

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `framework.activity_thread` | class | `android.app.ActivityThread` | Android's per-process main thread; every app's start goes through it | all |
| `framework.activity_thread.handle_bind_application` | method | `handleBindApplication(framework.app_bind_data)` → void | an app process starts: HMG-API gives mods the app (LoadedApp) | all |
| `framework.activity_thread.initial_application` | field | `mInitialApplication`: android.app.Application |  | all |
| `framework.app_bind_data` | class | `android.app.ActivityThread$AppBindData` |  | all |
| `framework.app_bind_data.app_info` | field | `appInfo`: android.content.pm.ApplicationInfo |  | all |
| `framework.app_bind_data.process_name` | field | `processName`: String |  | all |
| `framework.resources` | class | `android.content.res.Resources` |  | all |
| `framework.resources.get_drawable` | method | `getDrawable(int)` → android.graphics.drawable.Drawable | HMG-API answers its own resource ids (launcher tile icons) | all |
| `framework.resources.get_text` | method | `getText(int)` → CharSequence |  | all |
| `framework.context_impl` | class | `android.app.ContextImpl` |  | all |
| `framework.context_impl.start_service` | method | `startService(android.content.Intent)` → android.content.ComponentName | an app starts a service (the Wireless mod: no wired Android Auto for USB drives) | all |
| `framework.io_bridge` | class | `libcore.io.IoBridge` |  | all |
| `framework.io_bridge.connect` | method | `static connect(java.io.FileDescriptor, java.net.InetAddress, int)` → boolean | a socket connects (HMG-API's over-the-air update block) | all |
| `framework.io_bridge.connect_timeout` | method | `static connect(java.io.FileDescriptor, java.net.InetAddress, int, int)` → boolean |  | all |
| `framework.navigation_cluster` | class | `com.hkmc.cluster.NavigationCluster` | Hyundai/Kia's API to the instrument cluster and HUD (navigation data) | all |
| `framework.gpm_manager` | class | `com.hkmc.gpm.GpmManager` | the Android Auto (GPM) service's API | all |
| `framework.gpm_manager.new` | ctor | `new (android.content.Context)` |  | all |
| `framework.gpm_manager.is_navi_guidance` | method | `isGpmNaviGuidance()` → boolean | Android Auto is guiding | all |
| `framework.gpm_manager.is_activated` | method | `isGpmActivated()` → boolean | Android Auto is connected | all |
| `framework.gpm_manager.is_call` | method | `isGpmCall()` → boolean | an Android Auto call | all |
| `framework.gpm_manager.is_vr` | method | `isGpmVR()` → boolean | Android Auto's assistant is open (the voice button then goes to Android Auto only) | all |
| `framework.dipo_manager` | class | `android.dipo.DiPOManager` | CarPlay's (DiPO) API; Context.getSystemService("dipo") | all |
| `framework.dipo_manager.is_turn_by_turn` | method | `isDiPOTurnByTurnMode()` → boolean | CarPlay is guiding | all |
| `framework.dipo_manager.current_stat` | method | `getCurrentDiPOStat()` → int | 1: CarPlay is connected | all |
| `framework.dipo_manager.current_mode` | method | `getCurrentDiPOMode()` → framework.dipo_mode |  | all |
| `framework.dipo_mode` | class | `android.dipo.DiPOMode` | CarPlay's current resources (speech, phone call) | all |
| `framework.dipo_mode.phone_call` | method | `getPhoneCall()` → int | not 0: a CarPlay call | all |
| `framework.dipo_mode.speech` | method | `getSpeech()` → int | 1: Siri is open (the voice button then goes to CarPlay only) | all |
| `framework.navigation_cluster.update_tbt_info` | method | `updateTBTInfo(int, int, float, float, int, int, int, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tbt_sub_info` | method | `updateTBTSubInfo(float, int, int, int, int, int, int, float, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tbt_sub_info_b` | method | `updateTBTSubInfo(float, int, int, int, int, int, int, float, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tbt_sub_info2` | method | `updateTBTSubInfo2(float, int, float, int, float, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tbt_sub_info2_b` | method | `updateTBTSubInfo2(float, int, int, float, int, int, float, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tbt_sub_info_2c` | method | `updateTBTSubInfo2C(int, int, int, int, int, int, int, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_remain_dis` | method | `updateRemainDis(int, int, int, int)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tp_crossroad_name` | method | `updateTPCrossroadName(com.hkmc.cluster.NavigationCluster$CrossroadName)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tp_destination_name` | method | `updateTPFlexibleDestinationName(com.hkmc.cluster.NavigationCluster$DestinationName)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tp_turn_list` | method | `updateTPTurnList(com.hkmc.cluster.NavigationCluster$TurnList[])` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tp_turn_list_new` | method | `updateTPTurnListNew(byte[])` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.update_tp_lane_info` | method | `updateTPLaneInfo(com.hkmc.cluster.NavigationCluster$LaneInformation)` → void | turn-by-turn to the cluster/HUD | all |
| `framework.navigation_cluster.new` | ctor | `new (android.content.Context)` |  | all |
| `framework.navigation_cluster.update_lane_info` | method | `updateLaneInfo(byte[])` → void | lanes to the cluster/HUD as raw bytes (Android Auto's lane format) | all |
| `framework.turn_list` | class | `com.hkmc.cluster.NavigationCluster$TurnList` | one upcoming turn in the cluster's turn list | all |
| `framework.turn_list.new` | ctor | `new ()` |  | all |
| `framework.turn_list.type` | field | `type`: int |  | all |
| `framework.turn_list.direction` | field | `direction`: int |  | all |
| `framework.turn_list.traffic` | field | `traffic`: int |  | all |
| `framework.turn_list.no` | field | `no`: int |  | all |
| `framework.turn_list.name` | field | `name`: String |  | all |
| `framework.turn_list.exit_number` | field | `exitNumber`: String |  | all |
| `framework.turn_list.distance` | field | `distance`: float |  | all |
| `framework.turn_list.unit` | field | `unit`: int |  | all |
| `framework.destination_name` | class | `com.hkmc.cluster.NavigationCluster$DestinationName` |  | all |
| `framework.destination_name.new` | ctor | `new ()` |  | all |
| `framework.destination_name.type` | field | `type`: int |  | all |
| `framework.destination_name.name` | field | `name`: String |  | all |
| `framework.crossroad_name` | class | `com.hkmc.cluster.NavigationCluster$CrossroadName` |  | all |
| `framework.crossroad_name.new` | ctor | `new ()` |  | all |
| `framework.crossroad_name.name` | field | `name`: String |  | all |
| `framework.usm_manager` | class | `com.hkmc.usm.USMManager` | the owner's settings (USM): units and the like | all |
| `framework.usm_manager.new` | ctor | `new (android.content.Context)` |  | all |
| `framework.usm_manager.distance_speed_unit` | method | `getDistanceSpeedUnit()` → int | the owner's distance unit: 0 km, 1 miles | all |
| `framework.build_info` | class | `com.hkmc.BuildInfo` | Hyundai/Kia's build info | all |
| `framework.build_info.region` | field | `static REGION`: int | the head unit's region code (1 US, 4 Canada; see Car.region) | all |
| `framework.system_properties` | class | `android.os.SystemProperties` |  | all |
| `framework.system_properties.get_int` | method | `static getInt(String, int)` → int | reads a system property (HMG-API never sets ro.* ones) | all |
| `framework.runtime` | class | `java.lang.Runtime` | the VM's runtime: native library loading | all |
| `framework.runtime.load_library` | method | `loadLibrary(String, ClassLoader)` → void | System.loadLibrary(name) lands here, with the caller's class loader (its dependencies load too) | all |
| `framework.runtime.load` | method | `load(String, ClassLoader)` → void | System.load(path) lands here | all |
| `framework.service_manager` | class | `android.os.ServiceManager` |  | all |
| `framework.service_manager.get_service` | method | `static getService(String)` → android.os.IBinder | a system service by its registered name | all |
| `framework.system_service` | class | `com.hkmc.micom.ISystemService` | the car system service (com.hkmc.micom.system) | all |
| `framework.system_service_stub` | class | `com.hkmc.micom.ISystemService$Stub` |  | all |
| `framework.system_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.system_service |  | all |
| `framework.system_service.gear_p_status` | method | `getGearPStatus()` → int | 1 when the gear is in P | all |
| `framework.system_service.parking_brake_status` | method | `getEPBStatus()` → int | electronic parking brake status (2 with it on, EV6 2026-10-31; getParkingBrakeStatus stays 0 on the EV6) | all |
| `framework.hev_service` | class | `com.hkmc.hev.IHEVService` | the EV/hybrid service (com.hkmc.micom.hev) | all |
| `framework.hev_service_stub` | class | `com.hkmc.hev.IHEVService$Stub` |  | all |
| `framework.hev_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.hev_service |  | all |
| `framework.hev_service.hev_status` | method | `getHEVStatus(int)` → int | one HEV value by its HEVFlag | all |
| `framework.hev_flag` | class | `com.hkmc.hev.HEVManager$HEVFlag` | names of the HEV values | all |
| `framework.hev_flag.display_soc` | field | `static DISPLAY_SOC`: int | state of charge as shown, 0.5% steps | all |
| `framework.hev_flag.ev_ready` | field | `static EV_RDY`: int |  | all |
| `framework.hev_flag.battery_conditioning` | field | `static EV_BATTERYCONDITIONING`: int |  | all |
| `framework.hev_flag.odometer` | field | `static ODOMETER`: int | 0.1 km | all |
| `framework.regulation_manager` | class | `com.hkmc.regulation.RegulationActivityManager` | the car's driving restriction (video and the like while moving) | all |
| `framework.regulation_manager.get_instance` | method | `static getInstance(android.content.Context)` → framework.regulation_manager |  | all |
| `framework.regulation_manager.status` | method | `getStatus()` → int | 1 while the driving restriction is on | all |
| `framework.regulation_manager.show_popup` | method | `showRegulationPopup(String)` → void | the car's own "not while driving" popup | all |
| `framework.hscan_service` | class | `com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService` | the telematics CAN readings (tm_hscan_service, com.hkmc.ext.hcan library) | all |
| `framework.hscan_service_stub` | class | `com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService$Stub` |  | all |
| `framework.hscan_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.hscan_service |  | all |
| `framework.hscan_service.car_speed` | method | `getCarSpeed(byte)` → float | speed; argument 0 = km/h | all |
| `framework.hscan_service.moving` | method | `IsCarMoving()` → boolean |  | all |
| `framework.hscan_service.steering_angle` | method | `navi_getSasAngleVal()` → float | raw steering angle: signed 16-bit, 0.1 degree (Steering Arcade's, checked on a car; getSasAngleVal gives degrees but its left-turn form is unseen) | all |
| `framework.hscan_service.accel_pedal` | method | `getAccelPdlVal()` → float |  | all |
| `framework.hscan_service.brake_pressure` | method | `getCylPrsrVal()` → float |  | all |
| `framework.hscan_service.battery_12v_soc` | method | `getBatSoc()` → int | 12V battery state of charge, % | all |
| `framework.hscan_service.hv_health` | method | `getXEV_Bms_GDS_SOH()` → int |  | all |
| `framework.hscan_service.charging_status` | method | `getChargingStatus()` → int |  | all |
| `framework.hscan_service.battery_12v_sensor_state` | method | `getBATSNSRState()` → byte | 12V battery sensor's state (raw) | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `framework.hscan_service.battery_12v_sensor_invalid` | method | `getBATSNSRInvalid()` → byte | 12V battery sensor's invalid flag (raw) | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `framework.hscan_service.battery_12v_sensor_error` | method | `getBATSNSRError()` → byte | 12V battery sensor's error flag (raw) | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `framework.hscan_service.battery_12v_saver` | method | `get_BAT_AuxBattOpt()` → byte | the Auxiliary Battery Saver option (raw) | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `framework.car_services` | class | `com.hkmc.Service` | the car services' registered names | all |
| `framework.car_services.system` | field | `static SYSTEM_SERVICE`: String |  | all |
| `framework.car_services.hev` | field | `static HEV_SERVICE`: String |  | all |
| `framework.car_services.regulation` | field | `static REGULATION_SERVICE`: String |  | all |
| `framework.hscan_adapter` | class | `com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter` |  | all |
| `framework.hscan_adapter.service_name` | field | `static TM_HSCAN_SERVICE`: String | tm_hscan_service | all |
| `framework.regulation_service` | class | `com.hkmc.regulation.IRegulationManager` |  | all |
| `framework.regulation_service_stub` | class | `com.hkmc.regulation.IRegulationManager$Stub` |  | all |
| `framework.regulation_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.regulation_service |  | all |
| `framework.regulation_service.status` | method | `getStatus()` → int | 1 while the driving restriction is on | all |
| `framework.hkmc_loader` | class | `com.hkmc.internal.Loader` | loads the car's own framework jars in system_server | all |
| `framework.hkmc_loader.automotive_service` | method | `static getAutomotiveServiceJar(android.content.Context)` → framework.hkmc_loader | system_server's loader for automotive-service.jar (the car services, key dispatch) | all |
| `framework.hkmc_loader.class_loader` | field | `mClassLoader`: ClassLoader |  | all |
| `framework.mode_manager_service` | class | `com.hkmc.micom.ModeManagerService` | the car's mode and key service (automotive-service.jar, in system_server) | all |
| `framework.mode_manager_service.intercept_key_queueing` | method | `interceptKeyBeforeQueueing(android.view.InputChannel, int, int, int, int, int, int, int, int)` → int | every key, first: (focus, action, flags, deviceId, keyCode, scanCode, metaState, repeatCount, policyFlags); -1 drops it | all |
| `framework.window_manager_service` | class | `com.android.server.wm.WindowManagerService` | Android's window manager (services.jar, in system_server) | all |
| `framework.window_manager_service.intercept_key` | method | `interceptKey(android.view.KeyEvent)` → int | HKMC's key entry: the key policy's dispatch step hands it every key once Android is up and it passes the key to the car's key dispatcher; negative: the key is used up. A key put back with InputManager.injectInputEvent comes through here again | all |
| `framework.mode_key_dispatcher` | class | `com.hkmc.micom.ModeKeyDispatcher` | the car's key dispatcher (automotive-service.jar, in system_server): WindowManagerService.interceptKey hands it every key | all |
| `framework.mode_key_dispatcher.intercept_key` | method | `interceptKeyFromAndroid(android.view.InputChannel, int, int, int, int, int, int, int, int)` → int | every key once Android is up, from the key policy's dispatch step (the queueing step calls it only before boot): (focus, action, flags, deviceId, keyCode, scanCode, metaState, repeatCount, 0); negative: the key is used up. ModeManagerService.interceptKeyBefore* aren't called on 260507 | all |
| `framework.mode_manager_service.intercept_key_dispatching` | method | `interceptKeyBeforeDispatching(android.view.InputChannel, int, int, int, int, int, int, int, int)` → int |  | all |
| `framework.car_services.bcm` | field | `static BCM_SERVICE`: String |  | all |
| `framework.car_services.usm` | field | `static USM_SERVICE`: String |  | all |
| `framework.car_services.vr` | field | `static VR_SERVICE`: String |  | all |
| `framework.car_services.pgs` | field | `static PGS_SERVICE`: String |  | all |
| `framework.car_services.factory` | field | `static FACTORY_SERVICE`: String |  | all |
| `framework.bcm_service` | class | `com.hkmc.bcm.IBCMService` | body control (doors, lights) | all |
| `framework.bcm_service_stub` | class | `com.hkmc.bcm.IBCMService$Stub` |  | all |
| `framework.bcm_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.bcm_service |  | all |
| `framework.bcm_service.charge_door` | method | `requestChargeLockDoor(boolean)` → void | the charge door, as Kia Connect's remote command (false opens, per Charge Port Button) | all |
| `framework.usm_service` | class | `com.hkmc.usm.IUSMService` | the owner's vehicle settings (USM) | all |
| `framework.usm_service_stub` | class | `com.hkmc.usm.IUSMService$Stub` |  | all |
| `framework.usm_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.usm_service |  | all |
| `framework.usm_service.seat_heat_driver` | method | `setFrontSeatHeatDrvStep(int)` → void | driver seat heat step: 2 off, 6 low, 7 medium, 8 high (Cold-start Heat, on a car) | all |
| `framework.usm_service.seat_heat_passenger` | method | `setFrontSeatHeatAstStep(int)` → void |  | all |
| `framework.usm_service.seat_heat_driver_state` | method | `getFrontSeatHeat_DrvState()` → int |  | all |
| `framework.usm_service.seat_heat_passenger_state` | method | `getFrontSeatHeat_AstState()` → int |  | all |
| `framework.usm_service.seat_heat_driver_option` | method | `getFrontSeatHeat_DrvOption()` → int |  | all |
| `framework.usm_service.seat_heat_passenger_option` | method | `getFrontSeatHeat_AstOption()` → int |  | all |
| `framework.vr_service` | class | `com.hkmc.vr.IVRService` | voice commands' vehicle path | all |
| `framework.vr_service_stub` | class | `com.hkmc.vr.IVRService$Stub` |  | all |
| `framework.vr_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.vr_service |  | all |
| `framework.vr_service.wheel_heat` | method | `set_StrgWhlHeated_Cmd_AVN(int)` → void | steering wheel heat, 1 on 0 off: what "turn on the heated steering wheel" sends | all |
| `framework.system_service.sml_configuration` | method | `setSMLConfiguration(int, int)` → void | ambient light setting (5: brightness) | all |
| `framework.system_service.sml_rgb` | method | `setSMLRGB(byte[], byte[], byte[])` → void | ambient light colour: {0,r},{0,g},{0,b} | all |
| `framework.system_service.sml_status` | method | `requestSMLStatus()` → void |  | all |
| `framework.hev_service.batt_cond_station` | method | `setBattCondEvStation(int, int)` → void | battery conditioning: "routed to a charging station" (2,3 start; 0,0 stop), as the navigation sends | all |
| `framework.hev_service.batt_cond_route_distance` | method | `setBattCondRouteDis(int)` → void |  | all |
| `framework.hev_service.batt_cond_remain_time` | method | `setBattCondRemainTime(int)` → void |  | all |
| `framework.pgs_service` | class | `com.hkmc.pgs.IPgsService` | parking guidance (cameras) | all |
| `framework.pgs_service_stub` | class | `com.hkmc.pgs.IPgsService$Stub` |  | all |
| `framework.pgs_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.pgs_service |  | all |
| `framework.pgs_service.request_guide_lines` | method | `requestGuideLineState(int)` → void | rear camera guide lines: 1 off, 2 on | all |
| `framework.pgs_service.guide_lines` | method | `getRvmGuideLineState()` → int |  | all |
| `framework.factory_service` | class | `com.hkmc.factory.IMicomFactoryService` | factory functions (Engineering Mode) | all |
| `framework.factory_service_stub` | class | `com.hkmc.factory.IMicomFactoryService$Stub` |  | all |
| `framework.factory_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.factory_service |  | all |
| `framework.factory_service.hide_consent_screen` | method | `hideConsentScreen()` → void | hides the startup notice; no known inverse | all |
| `framework.car_services.micom_data` | field | `static MICOMDATA_SERVICE`: String |  | all |
| `framework.micomdata_service` | class | `com.hkmc.micom.IMicomDataService` | the car's raw MICOM data service: per-category frame callbacks (HMG-API only listens; its send method isn't mapped) | all |
| `framework.micomdata_service_stub` | class | `com.hkmc.micom.IMicomDataService$Stub` |  | all |
| `framework.micomdata_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.micomdata_service |  | all |
| `framework.micomdata_service.register` | method | `registerCallback(String, byte, com.hkmc.micom.IMicomDataCallback)` → boolean | frames of one category (CID) to a callback: onEvent(cid, funcH, funcL, data) | all |
| `framework.micomdata_service.unregister` | method | `unregisterCallback(String, byte, com.hkmc.micom.IMicomDataCallback)` → boolean |  | all |
| `framework.micomdata_callback_stub` | class | `com.hkmc.micom.IMicomDataCallback$Stub` | HMG-API's MICOM watch extends it (compiled against core/runtime/stubs) | all |
| `framework.bcm_service.drv_seat_heat_state` | method | `getDrvSeatHeatState()` → int | driver seat climate step: 2 off, 3-5 ventilation low-high, 6-8 heater low-high (BCMConstants DrvSeatHeatState; the settings service answers -1 on the EV6) | all |
| `framework.bcm_service.ast_seat_heat_state` | method | `getAstSeatHeatState()` → int |  | all |
| `framework.bcm_service.rl_seat_heat_state` | method | `getRLSeatHeatState()` → int |  | all |
| `framework.bcm_service.rr_seat_heat_state` | method | `getRRSeatHeatState()` → int |  | all |
| `framework.usm_service.seat_heat_rear_left` | method | `setRearSeatHeatLeftStep(int)` → void | rear left seat climate step (as the front ones) | all |
| `framework.usm_service.seat_heat_rear_right` | method | `setRearSeatHeatRightStep(int)` → void |  | all |
| `framework.bcm_listener` | class | `com.hkmc.bcm.IBCMListener` |  | all |
| `framework.bcm_listener_stub` | class | `com.hkmc.bcm.IBCMListener$Stub` | HMG-API answers its callbacks with a plain binder (only the ones it reads) | all |
| `framework.bcm_listener_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.bcm_listener |  | all |
| `framework.bcm_listener_stub.heating` | field | `static TRANSACTION_onRemoteTeleHeatingStatusChanged`: int | onRemoteTeleHeatingStatusChanged(steering wheel heat state, rear defogger state): sent on registration and on change; no getter has it | all |
| `framework.bcm_service.register_listener` | method | `registerBCMListener(framework.bcm_listener, android.os.IBinder)` → void |  | all |
| `framework.car_services.datc` | field | `static DATC_SERVICE`: String |  | all |
| `framework.datc_service` | class | `com.hkmc.datc.IDATCService` | climate control | all |
| `framework.datc_service_stub` | class | `com.hkmc.datc.IDATCService$Stub` |  | all |
| `framework.datc_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.datc_service |  | all |
| `framework.datc_listener` | class | `com.hkmc.datc.IDATCListener` |  | all |
| `framework.datc_listener_stub` | class | `com.hkmc.datc.IDATCListener$Stub` |  | all |
| `framework.datc_listener_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.datc_listener |  | all |
| `framework.datc_listener_stub.status` | field | `static TRANSACTION_onDatcStatus`: int | onDatcStatus(DatcStatus): the climate status, sent on registration and on change | all |
| `framework.datc_service.register_listener` | method | `registerDATCListener(framework.datc_listener, android.os.IBinder)` → void |  | all |
| `framework.datc_status` | class | `com.hkmc.datc.DatcStatus` |  | all |
| `framework.datc_status.creator` | field | `static CREATOR`: android.os.Parcelable$Creator |  | all |
| `framework.datc_status.ambient_f` | method | `getAmbientTempF()` → float | outside temperature, Fahrenheit (what the cluster shows on a US car: 73.0 for 73 F) | all |
| `framework.datc_status.ambient_c` | method | `getAmbientTempC()` → float |  | all |
| `framework.datc_status.ambient_state_c` | method | `getAmbientTempStateC()` → int | 255: the Celsius value isn't valid (a US car fills Fahrenheit) | all |
| `framework.bcm_service.drv_seat_heat_option` | method | `getDrvSeatHeatOption()` → int | what the seat has: 1 heater with 2 levels (HEATER_ONLY_2STEP), 2 heater with 3, 6 heater and ventilation (EV6 front) | all |
| `framework.bcm_service.ast_seat_heat_option` | method | `getAstSeatHeatOption()` → int |  | all |
| `framework.bcm_service.rl_seat_heat_option` | method | `getRLSeatHeatOption()` → int | 1 on the EV6: 2 levels, no ventilation | all |
| `framework.bcm_service.rr_seat_heat_option` | method | `getRRSeatHeatOption()` → int |  | all |
| `framework.bcm_service.batt_cond_indicator` | method | `getBattCondIndSta()` → int | battery conditioning indicator: 1 conditioning, 0 not (as Launchpad reads it) | all |
| `framework.hev_flag.batt_cond_failure` | field | `static BMS_BATTCONDFAILPOPUP`: int | battery conditioning failure popup (raw; -1 seen when nothing's wrong) | all |
| `framework.car_services.cluster` | field | `static CLUSTER_SERVICE`: String |  | all |
| `framework.cluster_service` | class | `com.hkmc.cluster.IClusterService` |  | all |
| `framework.cluster_service_stub` | class | `com.hkmc.cluster.IClusterService$Stub` |  | all |
| `framework.cluster_service_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.cluster_service |  | all |
| `framework.cluster_listener` | class | `com.hkmc.cluster.INavigationClusterListener` |  | all |
| `framework.cluster_listener_stub` | class | `com.hkmc.cluster.INavigationClusterListener$Stub` | HMG-API answers its callbacks with a plain binder (only the ones it reads) | all |
| `framework.cluster_listener_stub.as_interface` | method | `static asInterface(android.os.IBinder)` → framework.cluster_listener |  | all |
| `framework.cluster_listener_stub.battery_info` | field | `static TRANSACTION_onHandleBatteryInfoResp`: int | onHandleBatteryInfoResp(int[8]): [1] minimum pack temperature (whole C), [5] conditioning indicator; the answer to requestBatteryInfoReq, sent to every listener | all |
| `framework.cluster_service.add_listener` | method | `addNavigationClusterListener(framework.cluster_listener)` → void | the service keeps a list and calls every listener: the navigation's own stays registered | all |
| `framework.cluster_service.battery_info` | method | `requestBatteryInfoReq()` → void | asks the cluster for the battery info; answered through onHandleBatteryInfoResp | all |

## btsettings

App `com.android.btsettings`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `btsettings.projection_helper` | class | a class in `com.android.settings2.bluetooth` (no fixed name) | BtSettings' static helper that opens the Phone Projection pages | all |
| `btsettings.projection_helper.open_wireless_page` | method | `static ?(android.content.Context)` → void | opens the wireless projection page (its only caller is the Phone Projection menu) | all |
| `btsettings.projection_helper.device_connections_available` | method | `static ?()` → boolean |  | all |
| `btsettings.projection_helper.open_device_connections` | method | `static ?(android.content.Context, int)` → void |  | all |
| `btsettings.projection_helper.open_other_page` | method | `static ?(android.content.Context)` → void |  | all |

## buttonsettings

App `com.android.buttonsettings`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `buttonsettings.item` | class | `com.android.buttonsettings.customviews.CustomItem` | one ★ key choice: title, summary and icon resource ids, and the action it stores | all |
| `buttonsettings.item.new` | ctor | `new (int, int, int, String)` |  | all |
| `buttonsettings.item.get_shortcut` | method | `getShortCut()` → String |  | all |
| `buttonsettings.row` | class | `com.android.buttonsettings.util.IconTextItem` |  | all |
| `buttonsettings.row.set_check` | method | `setCheck(boolean)` → void |  | all |
| `buttonsettings.row.is_check` | method | `isCheck()` → boolean |  | all |
| `buttonsettings.adapter` | class | `com.android.buttonsettings.util.IconTextListAdapter` |  | all |
| `buttonsettings.adapter.set_boolean_list` | method | `setBooleanList(boolean[])` → void |  | all |
| `buttonsettings.adapter.set_list_items` | method | `setListItems(java.util.List)` → void |  | all |
| `buttonsettings.head_screen` | class | `com.android.buttonsettings.CustomButtonSetting` | the head unit ★ key's list of choices | all |
| `buttonsettings.head_screen.menu_list` | field | `mMenuList`: java.util.ArrayList |  | all |
| `buttonsettings.head_screen.cur_list` | field | `mCurList`: java.util.List |  | all |
| `buttonsettings.head_screen.title_list` | field | `mCustomKeyTitleList`: int[] |  | all |
| `buttonsettings.head_screen.checked_list` | field | `mCheckedList`: boolean[] |  | all |
| `buttonsettings.head_screen.adapter` | field | `adapter`: buttonsettings.adapter |  | all |
| `buttonsettings.head_screen.keycode` | field | `static mKeycode`: String |  | all |
| `buttonsettings.head_screen.init_list` | method | `initList()` → void |  | all |
| `buttonsettings.head_screen.init_custom_key` | method | `initCustomKey()` → void |  | all |
| `buttonsettings.head_screen.list_item` | method | `getListItem(int, String, int)` → buttonsettings.row |  | all |
| `buttonsettings.head_screen.set_selected` | method | `setSelectedIdem(int)` → void |  | all |
| `buttonsettings.wheel_screen` | class | `com.android.buttonsettings.CustomButtonSettingSWRC` | the steering wheel ★ key's list of choices | all |
| `buttonsettings.wheel_screen.menu_list` | field | `mMenuList`: java.util.ArrayList |  | all |
| `buttonsettings.wheel_screen.cur_list` | field | `mCurList`: java.util.List |  | all |
| `buttonsettings.wheel_screen.title_list` | field | `mCustomKeyTitleList`: int[] |  | all |
| `buttonsettings.wheel_screen.checked_list` | field | `mCheckedList`: boolean[] |  | all |
| `buttonsettings.wheel_screen.adapter` | field | `adapter`: buttonsettings.adapter |  | all |
| `buttonsettings.wheel_screen.keycode` | field | `static mKeycode`: String |  | all |
| `buttonsettings.wheel_screen.init_list` | method | `initList()` → void |  | all |
| `buttonsettings.wheel_screen.init_custom_key` | method | `initCustomKey()` → void |  | all |
| `buttonsettings.wheel_screen.list_item` | method | `getListItem(int, String, int)` → buttonsettings.row |  | all |
| `buttonsettings.wheel_screen.set_selected` | method | `setSelectedIdem(int)` → void |  | all |

## dipo

App `com.daudio.app.dipo`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `dipo.carplay` | class | `com.daudio.app.dipo.telechips.CarPlayService` | DiPO's CarPlay session service: the CarPlay library's callbacks (readable names) | all |
| `dipo.carplay.session_created` | method | `onSessionCreated()` → void | a CarPlay session was created | all |
| `dipo.carplay.session_started` | method | `onSessionStarted(int)` → void | a CarPlay session started (argument: transport, 1 wireless) | all |
| `dipo.carplay.session_finalized` | method | `onSessionFinalized(int)` → void | a CarPlay session ended | all |
| `dipo.carplay.iap_message` | method | `oniAPSendMessage(byte[], int)` → void | iAP2 bytes the phone sent over the CarPlay session's own channel | all |
| `dipo.carplay.native_iap_send` | method | `TccCarPlayNativeMethod_iAPSendMessage(byte[], int)` → int | sends iAP2 bytes to the phone over the CarPlay session's channel (0: sent) | all |
| `dipo.carplay.disable_bluetooth` | method | `onDisableBluetooth(String)` → void | the CarPlay library's disableBluetooth, about 80 ms into a session: DiPO then disconnects the phone's Bluetooth (wired CarPlay's way) | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `dipo.carplay.decode_failure` | method | `onNotifyDecodeFailure(int)` → void | the CarPlay video decoder reported a failure (1 no output frame, 2 sequence header failed, 3 black screen; 0 resets the black-screen count). Stock DiPO only logs it | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `dipo.carplay.screen_timeout` | method | `onScreenTimeout()` → void | the CarPlay screen stream got no data for the library's timeout (10 s on these units). Stock DiPO only sets a flag | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `dipo.carplay.force_key_frame` | method | `forceKeyFrame()` → com.daudio.app.dipo.telechips.CarPlayReturn | asks the phone for a new key frame (IDR) on the CarPlay screen stream. Stock DiPO never calls it | not on Hyundai Palisade USA 260903, Kia Carnival USA 260507, Kia Niro EV USA 260507 |
| `dipo.iap2_listener` | class | a class in `com.daudio.app.dipo` (no fixed name) | DiPO's own iAP2 listener (connected, disconnected, notify from the iAP2 server) | all |
| `dipo.iap2_listener.connected` | method | `connected(int, String)` → void | an iAP2 session is up (component: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel) | all |
| `dipo.iap2_listener.disconnected` | method | `disconnected(int)` → void | an iAP2 session ended (component) | all |
| `dipo.iap2_listener.notify` | method | `notify(int, int, int, com.tcc.iap2.Iap2NotiMsg)` → void | an iAP2 notification (what, arg1, arg2, message) | all |
| `dipo.iap2_client` | class | a class in `com.daudio.app.dipo` (no fixed name) | the class that owns DiPO's iAP2 listener | all |
| `dipo.iap2_listener.owner` | field | `?`: dipo.iap2_client | the listener's owner | all |
| `dipo.iap2` | class | a class in `com.daudio.app.dipo` (no fixed name) | DiPO's iAP2 manager (binds the iAP2 server, starts and stops route guidance) | all |
| `dipo.iap2_client.manager` | field | `?`: dipo.iap2 | the iAP2 manager the listener belongs to | all |
| `dipo.iap2.set_notify_on` | method | `static ?(dipo.iap2, boolean)` → boolean | static setter (manager, on): DiPO takes iAP2 notifications while on (set on connect, cleared on disconnect) | all |
| `dipo.session_listener` | class | a class in `com.daudio.app.dipo` (no fixed name) | DipoService's CarPlay session listener | all |
| `dipo.session_listener.session_details` | method | `?(String, String, String, String)` → void | onSessionDetails(device id, ...): saves the CarPlay phone's Bluetooth address | all |
| `dipo.iap2.bt_address` | method | `?()` → String | the phone's Bluetooth address from iAP2 identification (null after an iAP2 drop) | all |
| `dipo.bt` | class | a class in `com.daudio.app.dipo` (no fixed name) | DiPO's Bluetooth manager | all |
| `dipo.bt.set_carplay_bt_address` | method | `?(String)` → void | stores the CarPlay phone's Bluetooth address (null: kept) | all |
| `dipo.utils` | class | a class in `com.daudio.app.dipo` (no fixed name) | DiPO's utilities (region and feature checks) | all |
| `dipo.utils.is_support_wireless_carplay` | method | `isSupportWirelessCarPlay()` → boolean | DiPO's own wireless CarPlay (Hyundai's): the feature file com.hkmc.software.connectivity.carplay_wireless | all |
| `dipo.btcm` | class | `com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager` | Broadcom's Bluetooth connection manager (library com.broadcom.bt, one per process) | all |
| `dipo.btcm.register_listener` | method | `registerHfDeviceStatusListener(com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener)` → void | a status listener is registered (DiPO does once the Bluetooth service is connected) | all |
| `dipo.iap2_proxy` | class | `com.tcc.iap2.IIap2Service$Stub$Proxy` | the iAP2 server's binder proxy (library com.tcc.iap2) | all |
| `dipo.iap2_proxy.stop_route_guidance` | method | `Iap2SvcStopRouteGuidanceUpdate(String[])` → boolean | stop the phone's route guidance updates (DiPO: the car's navigation took turn-by-turn back) | all |

## gpm

App `com.google.android.projection.sink`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `gpm.navigation_status` | class | `com.google.android.projection.protocol.NavigationStatus` |  | all |
| `gpm.navigation_status.init` | method | `init(int, long, int, com.google.android.projection.proto.Protos$NavigationStatusService$InstrumentClusterType, int, int, int)` → boolean | tells the phone what the cluster takes: IMAGE (pictures) or ENUM (turn data) | all |
| `gpm.navigation_status.next_turn` | method | `navigationNextTurnCallback(String, int, int, byte[], int, int)` → void | the phone's next turn (road, side, event, image, angle, number), from the AA library | all |
| `gpm.navigation_status.next_turn_distance` | method | `navigationNextTurnDistanceCallback(int, int, int, int)` → void | distance to the next turn (meters, time, display distance x1000, unit) | all |
| `gpm.navigation_status.status` | method | `navigationStatusCallback(int)` → void | the phone's guidance status (1 active, 2 inactive, 3 rerouting) | all |
| `gpm.navigation_status.receiver` | field | `mGalReceiver`: gpm.receiver |  | all |
| `gpm.cluster_type` | class | `com.google.android.projection.proto.Protos$NavigationStatusService$InstrumentClusterType` |  | all |
| `gpm.cluster_type.enum` | field | `static ENUM`: gpm.cluster_type |  | all |
| `gpm.cluster_type.image` | field | `static IMAGE`: gpm.cluster_type |  | all |
| `gpm.receiver` | class | `com.google.android.projection.protocol.GalReceiver` | GPM's link to the AA library for one connection | all |
| `gpm.receiver.send_navigation_focus` | method | `sendNavigationFocusState(int)` → void | the car answers who navigates: the car's own navigation (native) or the phone (projected) | all |
| `gpm.receiver.focus_native` | field | `static NAVIGATION_FOCUS_STATE_NATIVE`: int |  | all |
| `gpm.receiver.destroy` | method | `destroy()` → void | the connection ends | all |
| `gpm.receiver_service` | class | `com.google.android.projection.service.GalReceiverService` | GPM's service: Android Auto sessions (wired, wireless) | all |
| `gpm.receiver_service.disconnected` | method | `onDisconnected(int, boolean, int)` → void | a session ended, also when the phone is just gone (ping timeout, Wi-Fi off), where destroy isn't called | all |
| `gpm.video_sink` | class | `com.google.android.projection.protocol.VideoSink` | Android Auto's video sink (what the car offers the phone) | all |
| `gpm.video_sink.nearest` | method | `nearestVideoConfiguration(int, int, int, int, int, int)` → void | one video setting: (width, height, density, real density, fps, depth); density is what AA DPI changes | all |
| `gpm.sensor_source` | class | `com.google.android.projection.protocol.SensorSource` | Android Auto's sensor service | all |
| `gpm.sensor_source.init` | method | `init(int, long, int[], int, int[], int[])` → boolean | (type, native, sensors offered to the phone, location, fuel types, EV connector types): once per connection | all |
| `gpm.sensor_source.speed` | field | `static SENSOR_TYPE_SPEED`: int | the speed sensor (offered: the phone limits taps while moving) | all |

## homesetting

App `com.hkmc.system.app.homesetting`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `homesetting.edit_list` | class | a class in `com.hkmc.system.app.homesetting` (no fixed name) | Edit menu's list of entries (as the launcher's, so the tile can be moved and saved) | all |
| `homesetting.edit_list.load` | method | `load()` → boolean |  | all |
| `homesetting.edit_list.items` | field | `static ?`: java.util.List |  | all |
| `homesetting.edit_list.get_context` | method | `getContext()` → android.content.Context |  | all |
| `homesetting.edit_entry` | class | a class in `com.hkmc.system.app.homesetting` (no fixed name) |  | all |
| `homesetting.edit_entry.new` | ctor | `new (homesetting.edit_list, android.content.Context, String, String, int, int, int)` |  | all |
| `homesetting.edit_entry.get_class_name` | method | `getClassName()` → String |  | all |

## iap2

App `com.tcc.iap2server`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `iap2.init_info` | class | `com.tcc.iap2server.Iap2InitInformationMsg` | what the head unit tells an iPhone it supports (iAP2 identification) | all |
| `iap2.init_info.check_supports_features` | method | `checkSupportsFeatures()` → void | sets the supported-feature flags; route guidance only if stepofspec >= 999 (US/Canada: 105) | all |
| `iap2.init_info.route_guidance` | field | `static iAP2UsedRouteGuidance`: int | 1: offer RouteGuidance (CarPlay turn-by-turn) to the iPhone | all |
| `iap2.service` | class | `com.tcc.iap2server.Iap2Service` | the iAP2 server (CarPlay's control link) | all |
| `iap2.service.on_create` | method | `onCreate()` → void |  | all |
| `iap2.service.is_connected` | method | `static isConnected()` → boolean |  | all |
| `iap2.service.get_component` | method | `static getComponent()` → int |  | all |
| `iap2.service.set_device_serial` | method | `setDeviceSerial(String)` → void |  | all |
| `iap2.service.receive_connect` | method | `receiveConnect(int)` → void | start an iAP2 session on a transport component (3..5: Bluetooth) | all |
| `iap2.service.receive_disconnect` | method | `receiveDisconnect(int)` → void |  | all |
| `iap2.service_receiver` | class | a class in `com.tcc.iap2server` (no fixed name) | the iAP2 service's own broadcast receiver (USB CarPlay attach and more); an inner class, so its number can differ | all |
| `iap2.service_receiver.on_receive` | method | `onReceive(android.content.Context, android.content.Intent)` → void |  | all |
| `iap2.service_receiver.service` | field | `?`: iap2.service |  | all |
| `iap2.service.on_destroy` | method | `onDestroy()` → void |  | all |
| `iap2.service.on_bind` | method | `onBind(android.content.Intent)` → android.os.IBinder |  | all |
| `iap2.service.native_handle` | field | `static mHandleIap2`: long |  | all |
| `iap2.service.native_lock` | field | `static mLock`: Object |  | all |
| `iap2.init_info.use_vehicle_information` | field | `static iAP2UsedVehicelInformation`: int |  | all |
| `iap2.init_info.use_vehicle_status` | field | `static iAP2UsedVehicleStatus`: int |  | all |

## launcher

App `com.android.launcher3`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `launcher.menu_list` | class | a class in `com.android.launcher3` (no fixed name) | the all-apps menu's list of built-in entries (the launcher drops saved entries not in it) | all |
| `launcher.menu_list.load` | method | `load()` → boolean | fills the list; HMG-API adds mod entries after it | all |
| `launcher.menu_list.items` | field | `?`: java.util.List |  | all |
| `launcher.menu_list.lock` | field | `mLock`: Object |  | all |
| `launcher.menu_list.get_context` | method | `getContext()` → android.content.Context |  | all |
| `launcher.menu_entry` | class | a class in `com.android.launcher3` (no fixed name) | one menu entry: package, activity, enabled/disabled icon and title resource ids | all |
| `launcher.menu_entry.new` | ctor | `new (launcher.menu_list, android.content.Context, String, String, int, int, int)` |  | all |

## panel

App `com.mobis.splitscreen.right`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `panel.home` | class | `com.mobis.splitscreen.right.SplitScreenHome` | the right-hand split-screen panel (a persistent system service drawing its own window) | all |
| `panel.page` | class | a class in `com.mobis.splitscreen.right` (no fixed name) | base class of every panel page | all |
| `panel.page.view_id` | field | `viewId`: int |  | all |
| `panel.page.view` | field | `mView`: android.view.View |  | all |
| `panel.page.context` | field | `mContext`: android.content.Context |  | all |
| `panel.page.handler` | field | `mHandler`: android.os.Handler |  | all |
| `panel.home.page_for_id` | method | `?(int)` → panel.page | the page for a view id | all |
| `panel.home.build_views` | method | `?(int, boolean, int)` → void | builds the panel's view list by id (slot 22 is the test page's) | all |
| `panel.test_page` | class | a class in `com.mobis.splitscreen.right` (no fixed name) | Mobis' unused test page (slot 22, getView returns null): HMG-API's page | all |
| `panel.test_page.get_view` | method | `getView()` → android.view.View |  | all |
| `panel.home.test_page` | field | `?`: panel.test_page | never set by stock code | all |
| `panel.test_gate` | class | a class in `com.mobis.splitscreen.right` (no fixed name) |  | all |
| `panel.test_gate.on` | method | `static ?()` → boolean | Mobis' test switch for slot 22 (always off on a car; HMG-API answers true only inside page_for_id and build_views) | all |

## settings

App `com.android.settings`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `settings.split_screen` | class | `com.android.settings.util.SplitScreenUtils` | Settings' split-screen page list helpers | all |
| `settings.split_screen.supported` | method | `static isSupportViewId(android.content.Context, int)` → boolean | is a panel view id supported here (unsupported ones are dropped from the page lists) | all |

## themesettings

App `com.android.themesettings`.

| Point | Kind | Looks for | About | Cars |
|---|---|---|---|---|
| `themesettings.split_item` | class | `com.android.themesettings.splitscreensettings.SplitScreenItem` | one page in the split-screen page editor | all |
| `themesettings.split_item.new` | ctor | `new (int, String, boolean)` | (view id, title, on) | all |
| `themesettings.split_item.type` | method | `?()` → int |  | all |
| `themesettings.split_items` | class | a class in `com.android.themesettings.splitscreensettings` (no fixed name) | the editor's page list helpers | all |
| `themesettings.split_items.make` | method | `static ?(android.content.Context, java.util.LinkedHashMap)` → java.util.ArrayList | the editor's items from {view id: on/off}; unknown ids are skipped | all |
| `themesettings.split_items.view_name` | method | `static ?(android.content.Context, int)` → String |  | all |
| `themesettings.split_items.string_id` | method | `static ?(int)` → int | a page's title (string resource id) by view id; 0 for an id it doesn't know, which the saved-list builder passes to getString | all |
| `themesettings.split_items.available` | method | `static ?(int, android.content.Context)` → boolean | whether the editor keeps a page (view id); false for ids it doesn't know, and the editor then saves its list without them | all |
