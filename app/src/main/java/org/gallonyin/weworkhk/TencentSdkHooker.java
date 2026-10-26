package org.gallonyin.weworkhk;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 腾讯定位 SDK（com.tencent.map.geolocation）专项拦截。
 *
 * 宿主的定位走 TencentLocationManager：SDK 自己的混合定位引擎（网络定位结果、
 * NMEA 解析等）不经过 LocationManager 回调，因此必须在 SDK 的输出边界上改写：
 * 1. 已知 TencentLocationImpl 实现类的 getLatitude/getLongitude 直接返回 GCJ-02 目标点；
 * 2. 捕获注册到 TencentLocationManager 的监听器，hook 其 onLocationChanged，在回调参数上原位改写；
 * 3. getLastLocation 等返回 TencentLocation 的方法在返回前原位改写。
 * 所有失败都只记日志并放行原始调用，不影响宿主。
 */
final class TencentSdkHooker {
    private static final String TAG = "WeWork";
    // Wework为壳+动态容器（npatch）结构，腾讯定位 SDK 位于内层，
    // 包名带 .sapp. 前缀；标准包名一并保留以兼容普通宿主。
    private static final String[] LOCATION_CLASSES = {
            "com.tencent.map.geolocation.TencentLocation",
            "com.tencent.map.geolocation.sapp.TencentLocation"
    };
    private static final String[] LISTENER_CLASSES = {
            "com.tencent.map.geolocation.TencentLocationListener",
            "com.tencent.map.geolocation.sapp.TencentLocationListener"
    };
    private static final String[] MANAGER_CLASSES = {
            "com.tencent.map.geolocation.TencentLocationManager",
            "com.tencent.map.geolocation.sapp.TencentLocationManager"
    };
    private static final String[] IMPL_CANDIDATES = {
            "com.tencent.map.geolocation.internal.TencentLocationImpl",
            "com.tencent.map.geolocation.TencentLocationImpl",
            "com.tencent.tencentmap.mapsdk.map.model.TencentLocationImpl",
            "com.tencent.map.geolocation.sapp.internal.TencentLocationImpl"
    };

    private static final Set<String> HOOKED_LISTENER_CLASSES = new HashSet<String>();
    private static final Set<String> HOOKED_IMPL_CLASSES = new HashSet<String>();
    private static volatile boolean installed;
    private static volatile boolean lazyArmed;

    private TencentSdkHooker() {
    }

    static void install(final ClassLoader classLoader, final CoordinateProvider coords) {
        if (installed || classLoader == null) return;
        synchronized (TencentSdkHooker.class) {
            if (installed) return;
            int implGetters = 0;
            for (String candidate : IMPL_CANDIDATES) {
                Class<?> impl = findClassIfExists(candidate, classLoader);
                if (impl == null) continue;
                implGetters += hookImplGetters(impl, coords);
            }
            int deliveryPoints = hookManagerDelivery(classLoader, coords);
            if (implGetters > 0 || deliveryPoints > 0) {
                installed = true;
                XposedBridge.log(TAG + " tencent sdk hooks installed implGetters=" + implGetters
                        + " deliveryPoints=" + deliveryPoints);
            } else {
                armLazyTrigger(coords);
            }
        }
    }

    /** 热路径重试入口：宿主对象（监听器等）的真实 classLoader 才能看见内层容器里的 SDK 类。 */
    static void installFromObject(Object hostObject, CoordinateProvider coords) {
        if (installed || hostObject == null) return;
        install(hostObject.getClass().getClassLoader(), coords);
    }

    /**
     * attach 阶段 SDK 类多半还没加载，挂一个 loadClass 触发器等它出现；
     * installed 后只做空判断，开销可忽略。
     */
    private static void armLazyTrigger(final CoordinateProvider coords) {
        if (lazyArmed) return;
        lazyArmed = true;
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (installed) return;
                    if (!(param.args.length > 0 && param.args[0] instanceof String)) return;
                    Object result = param.getResult();
                    if (!(result instanceof Class)) return;
                    if (!isTencentSdkClass((String) param.args[0])) return;
                    install(((Class<?>) result).getClassLoader(), coords);
                }
            });
            XposedBridge.log(TAG + " tencent sdk lazy trigger armed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tencent sdk lazy trigger failed: " + t);
        }
    }

    private static boolean isTencentSdkClass(String name) {
        if (name.endsWith(".TencentLocationImpl")) return true;
        for (String candidate : MANAGER_CLASSES) if (candidate.equals(name)) return true;
        for (String candidate : LOCATION_CLASSES) if (candidate.equals(name)) return true;
        for (String candidate : LISTENER_CLASSES) if (candidate.equals(name)) return true;
        return false;
    }

    private static int hookImplGetters(Class<?> impl, final CoordinateProvider coords) {
        int hooked = 0;
        try {
            XposedBridge.hookAllMethods(impl, "getLatitude", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (coords.isActive()) param.setResult(coords.gcjLatitude());
                }
            });
            hooked++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tencent impl getLatitude hook failed on " + impl.getName() + ": " + t);
        }
        try {
            XposedBridge.hookAllMethods(impl, "getLongitude", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (coords.isActive()) param.setResult(coords.gcjLongitude());
                }
            });
            hooked++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tencent impl getLongitude hook failed on " + impl.getName() + ": " + t);
        }
        // 真实定位对象携带真实精度（GPS 常见 40-50m、网络缓存更差），宿主定位页据此提示"精度较差"；
        // 一并固定为高精度 GPS 形态。
        try {
            XposedBridge.hookAllMethods(impl, "getAccuracy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (coords.isActive()) param.setResult(8f);
                }
            });
            hooked++;
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(impl, "getProvider", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (coords.isActive()) param.setResult("gps");
                }
            });
            hooked++;
        } catch (Throwable ignored) {
        }
        if (hooked > 0) {
            XposedBridge.log(TAG + " tencent location impl hooked: " + impl.getName());
        }
        return hooked;
    }

    /** 混淆实现类往往无 setter 可写：改写失败时按类名去重后直接 hook 它的坐标 getter。 */
    private static void hookImplGettersOnce(Class<?> impl, CoordinateProvider coords) {
        if (impl == null) return;
        synchronized (HOOKED_IMPL_CLASSES) {
            if (!HOOKED_IMPL_CLASSES.add(impl.getName())) return;
        }
        XposedBridge.log(TAG + " tencent impl discovered at runtime: " + impl.getName());
        hookImplGetters(impl, coords);
    }

    private static int hookManagerDelivery(final ClassLoader classLoader, final CoordinateProvider coords) {
        int count = 0;
        for (String managerName : MANAGER_CLASSES) {
            Class<?> manager = findClassIfExists(managerName, classLoader);
            if (manager == null) continue;
            count += hookManagerDeliveryForClass(manager, classLoader, coords);
        }
        return count;
    }

    private static int hookManagerDeliveryForClass(Class<?> manager, final ClassLoader classLoader,
                                                   final CoordinateProvider coords) {
        final Class<?> listenerItf = findFirstExisting(LISTENER_CLASSES, classLoader);
        final Class<?> locationItf = findFirstExisting(LOCATION_CLASSES, classLoader);
        if (listenerItf == null && locationItf == null) return 0;
        int count = 0;
        for (final Method method : manager.getDeclaredMethods()) {
            boolean takesListener = false;
            boolean returnsLocation = locationItf != null && method.getReturnType() == locationItf
                    // sapp 变体里缓存读取方法名为 getLastKnownLocation，标准版为 getLastLocation
                    && (method.getName().startsWith("getLast") || method.getName().startsWith("getCurrentLocation"));
            if (listenerItf != null) {
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (parameterType == listenerItf) {
                        takesListener = true;
                        break;
                    }
                }
            }
            if (!takesListener && !returnsLocation) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!coords.isActive() || listenerItf == null) return;
                        for (Object arg : param.args) {
                            if (arg != null && listenerItf.isInstance(arg)) {
                                hookListenerClass(arg.getClass(), coords);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!coords.isActive()) return;
                        if (locationItf != null) {
                            Object result = param.getResult();
                            if (result != null && locationItf.isInstance(result)) {
                                if (!rewriteLocationInPlace(result, coords)) {
                                    hookImplGettersOnce(result.getClass(), coords);
                                }
                                XposedBridge.log(TAG + " tencent " + method.getName() + " result handled"
                                        + " class=" + result.getClass().getName());
                            }
                        }
                        if (listenerItf != null) {
                            pushCachedLocationImmediately(param, listenerItf, locationItf, coords);
                        }
                    }
                });
                count++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " tencent manager hook failed " + method.getName() + ": " + t);
            }
        }
        return count;
    }

    /**
     * 注册完成后立即用 SDK 缓存的最近位置主动回调一次：目标页打开瞬间即拿到目标坐标，
     * 避免先渲染 SDK 磁盘缓存里的真实位置、等首个实时回调才跳回。
     */
    private static void pushCachedLocationImmediately(XC_MethodHook.MethodHookParam param,
                                                      Class<?> listenerItf, Class<?> locationItf,
                                                      CoordinateProvider coords) {
        try {
            Object listener = null;
            for (Object arg : param.args) {
                if (arg != null && listenerItf.isInstance(arg)) {
                    listener = arg;
                    break;
                }
            }
            if (listener == null) return;
            Object cached = null;
            // sapp 变体为 getLastKnownLocation()，标准 SDK 为 getLastLocation()
            for (String getter : new String[]{"getLastKnownLocation", "getLastLocation"}) {
                try {
                    cached = XposedHelpers.callMethod(param.thisObject, getter);
                    if (cached != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (cached == null || (locationItf != null && !locationItf.isInstance(cached))) return;
            if (!rewriteLocationInPlace(cached, coords)) {
                hookImplGettersOnce(cached.getClass(), coords);
            }
            XposedHelpers.callMethod(listener, "onLocationChanged", cached, 0, "ok");
            XposedBridge.log(TAG + " tencent immediate push class=" + cached.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tencent immediate push failed: " + t);
        }
    }

    private static void hookListenerClass(Class<?> listenerClass, final CoordinateProvider coords) {
        if (listenerClass == null) return;
        synchronized (HOOKED_LISTENER_CLASSES) {
            if (!HOOKED_LISTENER_CLASSES.add(listenerClass.getName())) return;
        }
        try {
            XposedBridge.hookAllMethods(listenerClass, "onLocationChanged", new XC_MethodHook() {
                private volatile boolean loggedFirst;
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!coords.isActive()) return;
                    if (param.args.length >= 1 && param.args[0] != null) {
                        if (!rewriteLocationInPlace(param.args[0], coords)) {
                            hookImplGettersOnce(param.args[0].getClass(), coords);
                        }
                        if (!loggedFirst) {
                            loggedFirst = true;
                            XposedBridge.log(TAG + " tencent callback rewrite active"
                                    + " class=" + param.args[0].getClass().getName());
                        }
                    }
                }
            });
            XposedBridge.log(TAG + " tencent listener class hooked: " + listenerClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tencent listener hook failed " + listenerClass.getName() + ": " + t);
        }
    }

    /** 优先调用 setter，失败后按常见字段名依次尝试直接写字段。 */
    static boolean rewriteLocationInPlace(Object location, CoordinateProvider coords) {
        if (location == null) return false;
        boolean latitudeOk = writeCoordinateValue(location, coords.gcjLatitude(), true);
        boolean longitudeOk = writeCoordinateValue(location, coords.gcjLongitude(), false);
        return latitudeOk && longitudeOk;
    }

    private static boolean writeCoordinateValue(Object target, double value, boolean latitude) {
        String setter = latitude ? "setLatitude" : "setLongitude";
        try {
            XposedHelpers.callMethod(target, setter, value);
            return true;
        } catch (Throwable ignored) {
        }
        String[] fieldNames = latitude
                ? new String[]{"latitude", "mLatitude", "a", "c"}
                : new String[]{"longitude", "mLongitude", "b", "d"};
        for (String fieldName : fieldNames) {
            try {
                java.lang.reflect.Field field = XposedHelpers.findField(target.getClass(), fieldName);
                if (field.getType() == double.class) {
                    field.setDouble(target, value);
                    return true;
                }
                if (field.getType() == float.class) {
                    field.setFloat(target, (float) value);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Class<?> findClassIfExists(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> findFirstExisting(String[] names, ClassLoader classLoader) {
        for (String name : names) {
            Class<?> clazz = findClassIfExists(name, classLoader);
            if (clazz != null) return clazz;
        }
        return null;
    }
}
