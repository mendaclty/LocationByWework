package org.gallonyin.weworkhk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Process;
import android.telephony.CellLocation;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Created by gallonyin on 2018/6/13.
 */

public class WeWork implements CoordinateProvider {
    private static final String TAG = "WeWork";

    private ClassLoader classLoader;

    private float defLa = 32.041713f;
    private float defLo = 118.784308f;
    // 配置坐标（腾讯地图选点）为 GCJ-02；wgs* 为派生的 WGS-84，供 NMEA/原生 GPS 域使用。
    private volatile double gcjLa;
    private volatile double gcjLo;
    private volatile double wgsLa;
    private volatile double wgsLo;
    private String defPicPath = "/storage/emulated/0/Tencent/WeixinWork/data/attendance/1597939200000.jpg";
    private String picPath = "";
    private volatile boolean isOpen = true;

    private static volatile WeWork sInstance;

    static WeWork get() {
        return sInstance;
    }

    public void start(ClassLoader classLoader) {
        this.classLoader = classLoader;
        sInstance = this;

        hkStart();
    }

    @Override
    public double gcjLatitude() {
        return gcjLa;
    }

    @Override
    public double gcjLongitude() {
        return gcjLo;
    }

    @Override
    public double wgsLatitude() {
        return wgsLa;
    }

    @Override
    public double wgsLongitude() {
        return wgsLo;
    }

    @Override
    public boolean isActive() {
        return isOpen;
    }

    private void applyCoordinates(double la, double lo) {
        gcjLa = la;
        gcjLo = lo;
        double[] wgs = CoordConverter.gcj02ToWgs84(la, lo);
        wgsLa = wgs[0];
        wgsLo = wgs[1];
    }

    private void initReceiver(Context context, final SharedPreferences sp) {
        BroadcastReceiver receiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "onReceive: " + intent.getAction());
                String action = intent.getAction();
                if (action == null) return;
                String data;
                switch (action) {
                    case "weworkdk_gps":
                        data = intent.getStringExtra("data");
                        try {
                            String[] values = data == null ? new String[0] : data.split("#");
                            applyCoordinates(Double.parseDouble(values[0]), Double.parseDouble(values[1]));
                            Log.i(TAG, "GPS updated process=" + context.getApplicationInfo().processName
                                    + " pid=" + Process.myPid() + " gcj=" + gcjLa + "#" + gcjLo
                                    + " wgs=" + wgsLa + "#" + wgsLo);
                            sp.edit().putString("GPSLatitudeText", String.valueOf(gcjLa))
                                    .putString("GPSLongitudeText", String.valueOf(gcjLo))
                                    .putFloat("GPSLatitude", (float) gcjLa)
                                    .putFloat("GPSLongitude", (float) gcjLo)
                                    .apply();
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " GPS parse failed; allowing previous coordinates: " + t);
                        }
                        break;
                    case "weworkdk_pic":
                        data = intent.getStringExtra("data");
                        Log.d(TAG, "onReceive pic: " + data);
                        picPath = data;
                        sp.edit().putString("PicPath", picPath).apply();
                        Intent broadcast = new Intent("weworkdk_pic_success");
                        context.sendBroadcast(broadcast);
                        break;
                    case "weworkdk_open":
                        isOpen = intent.getBooleanExtra("open", true);
                        sp.edit().putBoolean("open", isOpen).apply();
                        Log.d(TAG, "active=" + isOpen);
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter("weworkdk_gps");
        filter.addAction("weworkdk_pic");
        filter.addAction("weworkdk_open");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void hkStart() {
        Log.e(TAG, "hkStart()");
        safeFindAndHookMethod("android.app.Application", classLoader, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];

                SharedPreferences sp = context.getSharedPreferences("hkWeWork", Context.MODE_PRIVATE);
                double configuredLa = readCoordinate(sp, "GPSLatitudeText", "GPSLatitude", defLa);
                double configuredLo = readCoordinate(sp, "GPSLongitudeText", "GPSLongitude", defLo);
                applyCoordinates(configuredLa, configuredLo);
                isOpen = sp.getBoolean("open", true);
                picPath = sp.getString("PicPath", defPicPath);

                hkGPS(classLoader);
                hkPic(classLoader, sp);
                initReceiver(context, sp);
                DeviceIdGuard.install();
                VolumeKeyInjector.install(classLoader);
            }
        });
    }

    private double readCoordinate(SharedPreferences sp, String textKey, String legacyKey, float fallback) {
        try {
            String text = sp.getString(textKey, null);
            if (text != null && text.length() > 0) return Double.parseDouble(text);
        } catch (Throwable ignored) {
        }
        return sp.getFloat(legacyKey, fallback);
    }

    private void safeFindAndHookMethod(Object... args) {
        String name = args.length > 1 ? String.valueOf(args[args[0] instanceof Class ? 1 : 2]) : "unknown";
        try {
            HookMethodArgsParser.ParsedHook parsed = HookMethodArgsParser.parse(args);
            if (parsed.targetClass != null) {
                XposedHelpers.findAndHookMethod(parsed.targetClass, parsed.methodName, parsed.hookArgs);
            } else {
                XposedHelpers.findAndHookMethod(parsed.className, parsed.classLoader,
                        parsed.methodName, parsed.hookArgs);
            }
        } catch (Throwable t) { XposedBridge.log(TAG + " hook " + name + " failed: " + t); }
    }

    private void safeHookAllMethods(Class<?> clazz, String name, XC_MethodHook callback) {
        try { XposedBridge.hookAllMethods(clazz, name, callback); }
        catch (Throwable t) { XposedBridge.log(TAG + " hook " + name + " failed: " + t); }
    }

    private void hkGPS(ClassLoader classLoader) {
        Log.d(TAG, "hkGPS: gcj=" + gcjLa + "#" + gcjLo + " wgs=" + wgsLa + "#" + wgsLo);

        safeFindAndHookMethod(Location.class, "getAccuracy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isOpen) param.setResult(10f);
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            safeFindAndHookMethod(Location.class, "isFromMockProvider", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isOpen) param.setResult(false);
                }
            });
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeFindAndHookMethod(Location.class, "isMock", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (isOpen) param.setResult(false);
                }
            });
        }

        safeFindAndHookMethod("android.telephony.TelephonyManager", classLoader,
                "getCellLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(null);
                        Log.d(TAG, "getCellLocation");
                    }
                });

        safeFindAndHookMethod("android.telephony.PhoneStateListener", classLoader,
                "onCellLocationChanged", CellLocation.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(null);
                        Log.d(TAG, "onCellLocationChanged");
                    }
                });

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            safeFindAndHookMethod("android.telephony.TelephonyManager", classLoader,
                    "getPhoneCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            param.setResult(1);
                            Log.d(TAG, "getPhoneCount");
                        }
                    });
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            safeFindAndHookMethod("android.telephony.TelephonyManager", classLoader,
                    "getNeighboringCellInfo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            param.setResult(new ArrayList<>());
                            Log.d(TAG, "getNeighboringCellInfo");
                        }
                    });
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
            safeFindAndHookMethod("android.telephony.TelephonyManager", classLoader,
                    "getAllCellInfo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            param.setResult(null);
                            Log.d(TAG, "getAllCellInfo");
                        }
                    });
            safeFindAndHookMethod("android.telephony.PhoneStateListener", classLoader,
                    "onCellInfoChanged", List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            param.setResult(null);
                            Log.d(TAG, "onCellInfoChanged");
                        }
                    });
        }

        safeFindAndHookMethod("android.net.wifi.WifiManager", classLoader, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult(new ArrayList<>());
                Log.d(TAG, "getScanResults");
            }
        });

        safeFindAndHookMethod("android.net.wifi.WifiManager", classLoader, "getWifiState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult(1);
                Log.d(TAG, "getWifiState");
            }
        });

        safeFindAndHookMethod("android.net.wifi.WifiManager", classLoader, "isWifiEnabled", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult(true);
                Log.d(TAG, "isWifiEnabled");
            }
        });

        safeFindAndHookMethod("android.net.wifi.WifiInfo", classLoader, "getMacAddress", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult("00-00-00-00-00-00-00-00");
                Log.d(TAG, "getMacAddress");
            }
        });

        safeFindAndHookMethod("android.net.wifi.WifiInfo", classLoader, "getSSID", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult("null");
                Log.d(TAG, "getSSID");
            }
        });

        safeFindAndHookMethod("android.net.wifi.WifiInfo", classLoader, "getBSSID", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult("00-00-00-00-00-00-00-00");
                Log.d(TAG, "getBSSID");
            }
        });


        safeFindAndHookMethod("android.net.NetworkInfo", classLoader,
                "getTypeName", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult("WIFI");
                        Log.d(TAG, "getTypeName");
                    }
                });
        safeFindAndHookMethod("android.net.NetworkInfo", classLoader,
                "isConnectedOrConnecting", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(true);
                        Log.d(TAG, "isConnectedOrConnecting");
                    }
                });

        safeFindAndHookMethod("android.net.NetworkInfo", classLoader,
                "isConnected", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(true);
                        Log.d(TAG, "isConnected");
                    }
                });

        safeFindAndHookMethod("android.net.NetworkInfo", classLoader,
                "isAvailable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(true);
                        Log.d(TAG, "isAvailable");
                    }
                });

        safeFindAndHookMethod("android.telephony.CellInfo", classLoader,
                "isRegistered", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        param.setResult(true);
                        Log.d(TAG, "isRegistered");
                    }
                });

        safeFindAndHookMethod(LocationManager.class, "getLastLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                Location l = createLocation(8f);
                param.setResult(l);
                Log.d(TAG, "getLastLocation");
            }
        });

        safeFindAndHookMethod(LocationManager.class, "getLastKnownLocation", String.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                Location l = createLocation(8f);
                param.setResult(l);
                Log.d(TAG, "getLastKnownLocation");
            }
        });

        hookLocationCallbackTransport();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeHookAllMethods(LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isOpen || param.args.length == 0) return;
                    Object callback = param.args[param.args.length - 1];
                    if (callback == null) return;
                    try {
                        Method accept = callback.getClass().getMethod("accept", Location.class);
                        accept.invoke(callback, createLocation(10f));
                        param.setResult(null);
                        Log.d(TAG, "getCurrentLocation");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " getCurrentLocation callback failed; allowing original call: " + t);
                    }
                }
            });
        }


        safeHookAllMethods(LocationManager.class, "getProviders", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                ArrayList<String> arrayList = new ArrayList<>();
                arrayList.add("gps");
                param.setResult(arrayList);
                Log.d(TAG, "getProviders");
            }
        });

        safeFindAndHookMethod(LocationManager.class, "getBestProvider", Criteria.class, Boolean.TYPE, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                param.setResult("gps");
                Log.d(TAG, "getBestProvider");
            }
        });

        safeFindAndHookMethod(LocationManager.class, "addGpsStatusListener", GpsStatus.Listener.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isOpen) return;
                if (param.args[0] != null) {
                    try {
                        XposedHelpers.callMethod(param.args[0], "onGpsStatusChanged", 1);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " addGpsStatusListener callback(1) failed; allowing original call: " + t);
                    }
                    try {
                        XposedHelpers.callMethod(param.args[0], "onGpsStatusChanged", 3);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " addGpsStatusListener callback(3) failed; allowing original call: " + t);
                    }
                }
                Log.d(TAG, "addGpsStatusListener");
            }
        });

        installNmeaRewriteHooks();
        blockRawGnssChannels();
        TencentSdkHooker.install(classLoader, this);

        safeFindAndHookMethod("android.location.LocationManager", classLoader,
                "getGpsStatus", GpsStatus.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;
                        Log.d(TAG, "getGpsStatus");
                        GpsStatus gss = (GpsStatus) param.getResult();
                        if (gss == null)
                            return;

                        Class<?> clazz = GpsStatus.class;
                        Method m = null;
                        try {
                            for (Method method : clazz.getDeclaredMethods()) {
                                if (method.getName().equals("setStatus") && method.getParameterTypes().length > 1) {
                                    m = method;
                                    break;
                                }
                            }
                            if (m == null) {
                                XposedBridge.log(TAG + " getGpsStatus setStatus unavailable; allowing original result");
                                return;
                            }
                            m.setAccessible(true);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " getGpsStatus setStatus reflection failed; allowing original result: " + t);
                            return;
                        }

                        int svCount = 5;
                        int[] prns = {1, 2, 3, 4, 5};
                        float[] snrs = {0, 0, 0, 0, 0};
                        float[] elevations = {0, 0, 0, 0, 0};
                        float[] azimuths = {0, 0, 0, 0, 0};
                        int ephemerisMask = 0x1f;
                        int almanacMask = 0x1f;

                        //5 satellites are fixed
                        int usedInFixMask = 0x1f;

                        try {
                            XposedHelpers.callMethod(gss, "setStatus", svCount, prns, snrs, elevations, azimuths, ephemerisMask, almanacMask, usedInFixMask);
                            param.args[0] = gss;
                            param.setResult(gss);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " getGpsStatus setStatus call failed; allowing original result: " + t);
                        }
                        try {
                            m.invoke(gss, svCount, prns, snrs, elevations, azimuths, ephemerisMask, almanacMask, usedInFixMask);
                            param.setResult(gss);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " getGpsStatus setStatus invoke failed; allowing original result: " + t);
                        }
                    }
                });

        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (method.getName().equals("requestLocationUpdates")
                    && !Modifier.isAbstract(method.getModifiers())
                    && Modifier.isPublic(method.getModifiers())) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            Log.d(TAG, "requestLocationUpdates");
                            LocationListener ll = (LocationListener) LocationFactory.findListener(LocationListener.class, param.args);
                            Log.d(TAG, "requestLocationUpdates listener="
                                    + (ll == null ? "null" : ll.getClass().getName()));
                            if (ll != null) {
                                TencentSdkHooker.installFromObject(ll, WeWork.this);
                                Location l = createLocation(10f);
                                if (!LocationFactory.invokeLocationChangedOnce(ll, l)) {
                                    XposedBridge.log(TAG + " requestLocationUpdates callback unavailable");
                                }
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hook requestLocationUpdates failed; continuing: " + t);
                }
            }

            if (method.getName().equals("requestSingleUpdate")
                    && !Modifier.isAbstract(method.getModifiers())
                    && Modifier.isPublic(method.getModifiers())) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isOpen) return;
                            Log.d(TAG, "requestSingleUpdate");
                            LocationListener ll = (LocationListener) LocationFactory.findListener(LocationListener.class, param.args);
                            if (ll != null) {
                                Location l = createLocation(8f);
                                if (!LocationFactory.invokeLocationChangedOnce(ll, l)) {
                                    XposedBridge.log(TAG + " requestSingleUpdate callback unavailable");
                                }
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hook requestSingleUpdate failed; continuing: " + t);
                }
            }
        }
    }

    private void hookLocationCallbackTransport() {
        String[] candidates = {
                "android.location.LocationManager$ListenerTransport",
                "android.location.LocationManager$GnssMeasurementsEventListenerTransport",
                "android.location.LocationManager$LocationListenerTransport"
        };
        for (String className : candidates) {
            try {
                Class<?> transport;
                try { transport = XposedHelpers.findClass(className, classLoader); }
                catch (Throwable ignored) { continue; }
                final Class<?> hookedTransport = transport;
                safeHookAllMethods(hookedTransport, "onLocationChanged", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isOpen) return;
                        Location replacement = createLocation(10f);
                        LocationFactory.RewriteResult result = LocationFactory.rewriteLocationArgumentDetailed(param.args, replacement);
                        Log.d(TAG, "callback rewrite status=" + result.status
                                + " method=" + param.method.getName()
                                + " args=" + LocationFactory.callbackArgumentTypes(param.args)
                                + " before=" + truncateCoordinates(result.beforeCoordinates)
                                + " after=" + truncateCoordinates(result.afterCoordinates)
                                + " configured=" + gcjLa + "," + gcjLo
                                + " listener=" + transportName(param.thisObject));
                    }
                });
                Log.d(TAG, "callback transport hooked: " + className);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " callback transport unavailable " + className + ": " + t);
            }
        }
    }

    private static String truncateCoordinates(String coordinates) {
        if (coordinates == null) return "none";
        return coordinates.length() > 160 ? coordinates.substring(0, 160) + "..." : coordinates;
    }

    private void installNmeaRewriteHooks() {
        XC_MethodHook wrap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isOpen) return;
                for (int i = 0; i < param.args.length; i++) {
                    Object listener = param.args[i];
                    if (listener == null) continue;
                    try {
                        TencentSdkHooker.installFromObject(listener, WeWork.this);
                        if (listener instanceof android.location.OnNmeaMessageListener) {
                            param.args[i] = NmeaProxyFactory.wrapOnNmeaMessage(
                                    (android.location.OnNmeaMessageListener) listener, WeWork.this);
                            Log.d(TAG, "nmea listener wrapped: OnNmeaMessageListener");
                        } else if (listener instanceof GpsStatus.NmeaListener) {
                            param.args[i] = NmeaProxyFactory.wrapGpsStatusNmea(
                                    (GpsStatus.NmeaListener) listener, WeWork.this);
                            Log.d(TAG, "nmea listener wrapped: GpsStatus.NmeaListener");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " nmea wrap failed; allowing original listener: " + t);
                    }
                }
            }
        };
        safeHookAllMethods(LocationManager.class, "addOnNmeaListener", wrap);
        safeHookAllMethods(LocationManager.class, "addNmeaListener", wrap);
        Log.d(TAG, "nmea rewrite hooks installed");
    }

    private void blockRawGnssChannels() {
        XC_MethodHook refuse = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isOpen) return;
                param.setResult(false);
                Log.d(TAG, "raw gnss registration refused: " + param.method.getName());
            }
        };
        safeHookAllMethods(LocationManager.class, "registerGnssMeasurementCallback", refuse);
        safeHookAllMethods(LocationManager.class, "registerGnssNavigationMessageCallback", refuse);
    }

    private String transportName(Object transport) {
        return transport == null ? "null" : transport.getClass().getName();
    }

    private Location createLocation(float accuracy) {
        return LocationFactory.create(gcjLa, gcjLo, accuracy);
    }

    private void hkPic(ClassLoader classLoader, final SharedPreferences sp) {
        Log.d(TAG, "hkPic: ");

        safeFindAndHookMethod("android.os.BaseBundle", classLoader,
                "getString", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!isOpen) return;

                        if (param.args.length > 0 && (param.args[0] instanceof String)) {
                            String pa = (String) param.args[0];
                            if (pa.equals("extra_photo_url")) {
                                Log.d(TAG, "get extra_photo_url: ");
                                param.setResult(picPath);
                                Log.d(TAG, "hook extra_photo_url: " + picPath);
                                sp.edit().putString("PicPath", defPicPath).apply(); //用一次就清除
                            }
                        }
                    }
                });
    }

}
