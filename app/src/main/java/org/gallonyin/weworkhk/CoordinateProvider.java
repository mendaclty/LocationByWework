package org.gallonyin.weworkhk;

/**
 * 当前目标坐标。配置坐标来自腾讯地图选点，属 GCJ-02；
 * 原生 GPS/NMEA 域使用派生的 WGS-84，避免宿主 SDK 二次偏移后落点漂移。
 */
interface CoordinateProvider {
    double gcjLatitude();

    double gcjLongitude();

    double wgsLatitude();

    double wgsLongitude();

    boolean isActive();
}
