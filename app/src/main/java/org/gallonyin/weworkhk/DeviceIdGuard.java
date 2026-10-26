package org.gallonyin.weworkhk;

import android.telephony.TelephonyManager;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 设备标识访问守卫：内嵌腾讯栅格地图 SDK 初始化时会在后台线程读取 IMEI，
 * 宿主 uid 在现代 Android 上无权访问设备标识，SecurityException 未捕获会直接杀死进程。
 *
 * 只在调用栈里出现地图 SDK（com.tencent.mapsdk）时返回固定假值，
 * 宿主自身的设备指纹/登录相关调用完全放行。
 */
final class DeviceIdGuard {
    private static final String TAG = "WeWork";
    private static final String SDK_PREFIX = "com.tencent.mapsdk";
    private static final String PLACEHOLDER_IMEI = "864521053578921";
    private static final String PLACEHOLDER_MEID = "A0000053A2B1C7";
    private static final String PLACEHOLDER_IMSI = "460079850286803";
    private static final String PLACEHOLDER_SIM_SERIAL = "89860123456789012345";
    private static final String PLACEHOLDER_LINE_NUMBER = "";

    private static volatile boolean installed;

    private DeviceIdGuard() {
    }

    static void install() {
        if (installed) return;
        synchronized (DeviceIdGuard.class) {
            if (installed) return;
            Object[][] methods = {
                    {"getDeviceId", PLACEHOLDER_IMEI},
                    {"getImei", PLACEHOLDER_IMEI},
                    {"getMeid", PLACEHOLDER_MEID},
                    {"getSubscriberId", PLACEHOLDER_IMSI},
                    {"getSimSerialNumber", PLACEHOLDER_SIM_SERIAL},
            };
            int hooked = 0;
            for (final Object[] entry : methods) {
                final String placeholder = (String) entry[1];
                try {
                    XposedBridge.hookAllMethods(TelephonyManager.class, (String) entry[0], new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isMapSdkCalling()) return;
                            param.setResult(placeholder);
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " device id guard hook failed: " + entry[0] + " " + t);
                }
            }
            installed = true;
            Log.d(TAG, "device id guard installed, methods=" + hooked);
        }
    }

    private static boolean isMapSdkCalling() {
        for (StackTraceElement element : new Throwable().getStackTrace()) {
            if (element.getClassName().startsWith(SDK_PREFIX)) return true;
        }
        return false;
    }
}
