package org.gallonyin.weworkhk;

import org.junit.Test;

import static org.junit.Assert.*;

public class WeWorkLocationTest {
    @Test
    public void locationFactoryPreservesConfiguredCoordinatesAndAccuracy() {
        LocationFactory.LocationSpec spec = LocationFactory.spec(22.817278f, 113.28315f, 10f);
        assertEquals(22.817278f, spec.latitude, 0f);
        assertEquals(113.28315f, spec.longitude, 0f);
        assertEquals(10f, spec.accuracy, 0f);
        assertEquals("gps", spec.provider);
    }

    @Test
    public void listenerScannerFindsListenerInAnyModernOverloadPosition() {
        Object listener = new Runnable() { public void run() { } };
        assertSame(listener, LocationFactory.findListener(Runnable.class, new Object(), listener, new Object()));
        assertSame(listener, LocationFactory.findListener(Runnable.class, listener, new Object(), new Object(), new Object()));
        assertNull(LocationFactory.findListener(Runnable.class, new Object(), new Object()));
    }

    @Test
    public void callbackMethodSelectionUsesExactLocationParameter() {
        java.lang.reflect.Method method = LocationFactory.locationChangedMethod(OverloadedListener.class);
        assertNotNull(method);
        assertArrayEquals(new Class<?>[]{android.location.Location.class}, method.getParameterTypes());
    }

    @Test
    public void callbackRewriteReplacesIncomingLocationWithoutInvokingListener() {
        android.location.Location real = new android.location.Location("gps");
        android.location.Location configured = new android.location.Location("gps");
        Object[] callbackArgs = new Object[]{real};

        assertTrue(LocationFactory.rewriteLocationArgument(callbackArgs, configured));
        assertSame(configured, callbackArgs[0]);
    }

    @Test
    public void callbackRewriteRebuildsLocationResultLikeContainerAndRewritesEveryLocation() {
        android.location.Location configured = new android.location.Location("gps");
        Object[] callbackArgs = new Object[]{new TestLocationResult(java.util.Arrays.asList(
                new android.location.Location("gps"), new android.location.Location("gps"))) };

        LocationFactory.RewriteResult result = LocationFactory.rewriteLocationArgumentDetailed(callbackArgs, configured);
        assertEquals(LocationFactory.RewriteStatus.CONTAINER, result.status);
        TestLocationResult rewritten = (TestLocationResult) callbackArgs[0];
        assertEquals(2, rewritten.getLocations().size());
        assertSame(configured, rewritten.getLocations().get(0));
        assertSame(configured, rewritten.getLocations().get(1));

        LocationFactory.RewriteResult repeated = LocationFactory.rewriteLocationArgumentDetailed(callbackArgs, configured);
        assertEquals(LocationFactory.RewriteStatus.ALREADY_REWRITTEN, repeated.status);
    }

    @Test
    public void callbackRewriteSupportsEveryLocationInListWithoutInvokingListener() {
        android.location.Location configured = new android.location.Location("gps");
        Object[] callbackArgs = new Object[]{java.util.Arrays.asList(
                new android.location.Location("gps"), new android.location.Location("gps"))};
        LocationFactory.RewriteResult result = LocationFactory.rewriteLocationArgumentDetailed(callbackArgs, configured);
        assertEquals(LocationFactory.RewriteStatus.LIST, result.status);
        assertSame(configured, ((java.util.List<?>) callbackArgs[0]).get(0));
        assertSame(configured, ((java.util.List<?>) callbackArgs[0]).get(1));
    }

    @Test
    public void callbackRewriteReportsNoneForNoLocationParameter() {
        Object listener = new Object();
        Object[] callbackArgs = new Object[]{listener, "unchanged"};

        LocationFactory.RewriteResult result = LocationFactory.rewriteLocationArgumentDetailed(callbackArgs,
                new android.location.Location("gps"));
        assertEquals(LocationFactory.RewriteStatus.NONE, result.status);
        assertSame(listener, callbackArgs[0]);
        assertEquals("unchanged", callbackArgs[1]);
    }

    @Test
    public void locationSaltIsStableForRepeatedReads() {
        LocationFactory.Coordinates first = LocationFactory.stableCoordinates(22.8f, 113.2f, 0f);
        LocationFactory.Coordinates second = LocationFactory.stableCoordinates(22.8f, 113.2f, 0f);
        assertEquals(first.latitude, second.latitude, 0f);
        assertEquals(first.longitude, second.longitude, 0f);
    }

    public static final class TestLocationResult {
        private final java.util.List<android.location.Location> locations;
        TestLocationResult(java.util.List<android.location.Location> locations) { this.locations = locations; }
        public java.util.List<android.location.Location> getLocations() { return locations; }
        public static TestLocationResult create(java.util.List<android.location.Location> locations) {
            return new TestLocationResult(locations);
        }
    }

    public static class OverloadedListener {
        int locationCalls;
        int listCalls;
        public void onLocationChanged(android.location.Location location) { locationCalls++; }
        public void onLocationChanged(java.util.List<android.location.Location> locations) { listCalls++; }
    }

    @Test
    public void modernHookPlanIncludesMockAndCurrentLocationHooks() {
        assertTrue(LocationFactory.supportsMockStateHook(31));
        assertTrue(LocationFactory.supportsCurrentLocationHook(30));
        assertFalse(LocationFactory.supportsMockStateHook(30));
    }

    @Test
    public void executionIsolationReportsFailureWithoutThrowing() {
        assertFalse(LocationFactory.executeSafely(new LocationFactory.ThrowingAction() {
            @Override public void run() throws Throwable { throw new AssertionError("callback failure"); }
        }));
        assertTrue(LocationFactory.executeSafely(new LocationFactory.ThrowingAction() {
            @Override public void run() { }
        }));
    }
}
