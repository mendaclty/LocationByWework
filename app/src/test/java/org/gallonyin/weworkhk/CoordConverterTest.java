package org.gallonyin.weworkhk;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoordConverterTest {
    private static final double OFFICE_LAT = 22.807641983032227d;
    private static final double OFFICE_LNG = 113.20242309570312d;

    @Test
    public void wgs84ToGcj02ShiftsByKnownLocalOffset() {
        double[] gcj = CoordConverter.wgs84ToGcj02(OFFICE_LAT, OFFICE_LNG);
        // 佛山区域的标准偏移：纬度向南约 300m，经度向东约 550m
        assertEquals(-308.8, (gcj[0] - OFFICE_LAT) * 111320, 5.0);
        assertEquals(547.9, (gcj[1] - OFFICE_LNG) * 111320 * Math.cos(Math.toRadians(OFFICE_LAT)), 5.0);
    }

    @Test
    public void gcj02ToWgs84RoundtripsBackToOriginalGcj() {
        double[] gcj = CoordConverter.wgs84ToGcj02(OFFICE_LAT, OFFICE_LNG);
        double[] wgs = CoordConverter.gcj02ToWgs84(gcj[0], gcj[1]);
        double[] backToGcj = CoordConverter.wgs84ToGcj02(wgs[0], wgs[1]);
        assertEquals(gcj[0], backToGcj[0], 1e-6);
        assertEquals(gcj[1], backToGcj[1], 1e-6);
    }

    @Test
    public void outOfChinaCoordinatesPassThroughUnchanged() {
        assertArrayEquals(new double[]{40.0, -74.0}, CoordConverter.wgs84ToGcj02(40.0, -74.0), 0d);
        assertArrayEquals(new double[]{40.0, -74.0}, CoordConverter.gcj02ToWgs84(40.0, -74.0), 0d);
        assertTrue(CoordConverter.outOfChina(40.0, -74.0));
        assertFalse(CoordConverter.outOfChina(OFFICE_LAT, OFFICE_LNG));
    }
}
