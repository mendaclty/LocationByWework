package org.gallonyin.weworkhk;

import android.location.GpsStatus;
import android.location.OnNmeaMessageListener;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** 把 NMEA 监听器包一层代理：真实卫星语句先改写坐标再交给宿主。 */
final class NmeaProxyFactory {
    private NmeaProxyFactory() {
    }

    static OnNmeaMessageListener wrapOnNmeaMessage(OnNmeaMessageListener original, final CoordinateProvider coords) {
        return (OnNmeaMessageListener) Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                new Class<?>[]{OnNmeaMessageListener.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (args != null && args.length >= 1 && args[0] instanceof String
                                && "onNmeaMessage".equals(method.getName()) && coords.isActive()) {
                            args[0] = NmeaRewriter.rewrite((String) args[0], coords.wgsLatitude(), coords.wgsLongitude());
                        }
                        return method.invoke(original, args);
                    }
                });
    }

    static GpsStatus.NmeaListener wrapGpsStatusNmea(GpsStatus.NmeaListener original, final CoordinateProvider coords) {
        return (GpsStatus.NmeaListener) Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                new Class<?>[]{GpsStatus.NmeaListener.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (args != null && args.length >= 2 && args[1] instanceof String
                                && "onNmeaReceived".equals(method.getName()) && coords.isActive()) {
                            args[1] = NmeaRewriter.rewrite((String) args[1], coords.wgsLatitude(), coords.wgsLongitude());
                        }
                        return method.invoke(original, args);
                    }
                });
    }
}
