package org.gallonyin.weworkhk;

import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Package-private seams for location hook behavior that do not require Android framework classes. */
final class LocationFactory {
    private LocationFactory() { }
    static final class LocationSpec {
        final float latitude, longitude, accuracy;
        final String provider = "gps";
        LocationSpec(float latitude, float longitude, float accuracy) { this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy; }
    }
    static LocationSpec spec(float latitude, float longitude, float accuracy) { return new LocationSpec(latitude, longitude, accuracy); }
    static Location create(float latitude, float longitude, float accuracy) {
        return create((double) latitude, (double) longitude, accuracy);
    }
    static Location create(double latitude, double longitude, float accuracy) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracy);
        location.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt("satellites", 16);
            extras.putInt("satellites_in_view", 16);
            extras.putInt("satellites_used_in_fix", 16);
            extras.putInt("satellites_visible", 16);
            extras.putBoolean("mockLocation", false);
            location.setExtras(extras);
        } catch (Throwable ignored) {
        }
        return location;
    }
    enum RewriteStatus { DIRECT, LIST, CONTAINER, NONE, ALREADY_REWRITTEN, FAILED }

    static class RewriteResult {
        final RewriteStatus status;
        final int locationCount;
        final String beforeCoordinates;
        final String afterCoordinates;
        RewriteResult(RewriteStatus status, int locationCount, String beforeCoordinates, String afterCoordinates) {
            this.status = status;
            this.locationCount = locationCount;
            this.beforeCoordinates = beforeCoordinates;
            this.afterCoordinates = afterCoordinates;
        }
        boolean changed() {
            return status == RewriteStatus.DIRECT || status == RewriteStatus.LIST || status == RewriteStatus.CONTAINER;
        }
    }

    /** Rewrites one callback payload, including Android 16 onLocationChanged(List, IRemoteCallback). */
    static boolean rewriteLocationArgument(Object[] args, Location replacement) {
        return rewriteLocationArgumentDetailed(args, replacement).changed();
    }

    static RewriteResult rewriteLocationArgumentDetailed(Object[] args, Location replacement) {
        if (args == null || replacement == null) return result(RewriteStatus.NONE, 0, null, null);
        for (int i = 0; i < args.length; i++) {
            RewriteResult result = rewritePayload(args[i], replacement, false);
            if (result.status != RewriteStatus.NONE) {
                if (result.changed() || result.status == RewriteStatus.ALREADY_REWRITTEN) return replace(args, i, result, replacement);
                return result;
            }
        }
        return result(RewriteStatus.NONE, 0, null, null);
    }

    private static RewriteResult replace(Object[] args, int index, RewriteResult result, Location replacement) {
        if (result instanceof PayloadResult && ((PayloadResult) result).changedPayload != null) {
            args[index] = ((PayloadResult) result).changedPayload;
        }
        return result;
    }

    private static final class PayloadResult extends RewriteResult {
        final Object changedPayload;
        PayloadResult(RewriteStatus status, int count, String before, String after, Object payload) {
            super(status, count, before, after); changedPayload = payload;
        }
    }

    private static RewriteResult rewritePayload(Object payload, Location replacement, boolean container) {
        if (payload == null) return result(RewriteStatus.NONE, 0, null, null);
        if (payload instanceof Location) {
            Location location = (Location) payload;
            return new PayloadResult(location == replacement ? RewriteStatus.ALREADY_REWRITTEN : RewriteStatus.DIRECT,
                    1, coordinates(location), coordinates(replacement), location == replacement ? null : replacement);
        }
        if (payload instanceof List) {
            List<?> source = (List<?>) payload;
            if (source.isEmpty()) return result(RewriteStatus.NONE, 0, null, null);
            ArrayList<Location> locations = new ArrayList<>(source.size());
            boolean allReplacement = true;
            StringBuilder before = new StringBuilder();
            for (Object item : source) {
                if (!(item instanceof Location)) return result(RewriteStatus.NONE, 0, null, null);
                Location location = (Location) item;
                if (before.length() > 0) before.append(';');
                before.append(coordinates(location));
                if (location != replacement) allReplacement = false;
                locations.add(replacement);
            }
            RewriteStatus status = allReplacement ? RewriteStatus.ALREADY_REWRITTEN : (container ? RewriteStatus.CONTAINER : RewriteStatus.LIST);
            return new PayloadResult(status, locations.size(), before.toString(), coordinates(replacement), allReplacement ? null : locations);
        }
        // LocationResult-like containers expose public getLocations() and static create(List).
        try {
            Method getter = payload.getClass().getMethod("getLocations");
            Object locations = getter.invoke(payload);
            if (!(locations instanceof List)) return result(RewriteStatus.FAILED, 0, null, null);
            RewriteResult listResult = rewritePayload(locations, replacement, true);
            if (listResult.status == RewriteStatus.NONE || listResult.status == RewriteStatus.ALREADY_REWRITTEN) return listResult;
            Method factory = payload.getClass().getMethod("create", List.class);
            if (!Modifier.isStatic(factory.getModifiers())) return result(RewriteStatus.FAILED, listResult.locationCount, listResult.beforeCoordinates, null);
            Object rebuilt = factory.invoke(null, ((PayloadResult) listResult).changedPayload);
            return new PayloadResult(RewriteStatus.CONTAINER, listResult.locationCount, listResult.beforeCoordinates,
                    listResult.afterCoordinates, rebuilt);
        } catch (NoSuchMethodException ignored) {
            return result(RewriteStatus.NONE, 0, null, null);
        } catch (Throwable ignored) {
            return result(RewriteStatus.FAILED, 0, null, null);
        }
    }

    private static PayloadResult result(RewriteStatus status, int count, String before, String after) {
        return new PayloadResult(status, count, before, after, null);
    }

    private static String coordinates(Location location) {
        if (location == null) return "null";
        try {
            return location.getLatitude() + "," + location.getLongitude();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    static String callbackArgumentTypes(Object[] args) {
        if (args == null) return "null";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) result.append(',');
            result.append(args[i] == null ? "null" : args[i].getClass().getName());
        }
        return result.toString();
    }
    static final class Coordinates {
        final float latitude, longitude;
        Coordinates(float latitude, float longitude) { this.latitude = latitude; this.longitude = longitude; }
    }
    static Coordinates stableCoordinates(float latitude, float longitude, float salt) {
        return new Coordinates(latitude + 0.002082f + salt, longitude - 0.005203f - salt);
    }
    static Method locationChangedMethod(Class<?> listenerType) {
        for (Method method : listenerType.getMethods()) {
            if (method.getName().equals("onLocationChanged")
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == Location.class) {
                return method;
            }
        }
        for (Method method : listenerType.getDeclaredMethods()) {
            if (method.getName().equals("onLocationChanged")
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == Location.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
    static boolean invokeLocationChangedOnce(Object listener, Location location) {
        Method method = locationChangedMethod(listener.getClass());
        if (method == null) return false;
        try { method.invoke(listener, location); return true; }
        catch (Throwable ignored) { return false; }
    }
    static Object findListener(Class<?> listenerType, Object... args) {
        for (Object arg : args) if (arg != null && listenerType.isInstance(arg)) return arg;
        return null;
    }
    static boolean supportsMockStateHook(int sdk) { return sdk >= 31; }
    static boolean supportsCurrentLocationHook(int sdk) { return sdk >= 30; }
    interface ThrowingAction { void run() throws Throwable; }
    static boolean executeSafely(ThrowingAction action) {
        try { action.run(); return true; }
        catch (Throwable ignored) { return false; }
    }
}
