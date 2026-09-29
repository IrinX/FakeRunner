package com.fakerunner.xposed;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;

/**
 * 定位 Hook：拦截系统定位 API，返回模拟的位置。
 *
 * Modern API (libxposed 102) 使用拦截器链模型：
 *   hook(method).intercept(chain -> { ... chain.proceed() ... return result; })
 *
 * 覆盖以下链路：
 * 1. LocationManager.getLastKnownLocation() — 返回伪造位置
 * 2. LocationManager.requestLocationUpdates() — 拦截监听器，定时推送伪造位置
 * 3. LocationManager.getProviders() — 确保 gps provider 存在
 * 4. LocationManager.isProviderEnabled() — gps 总是开启
 * 5. Location.isFromMockProvider() — 返回 false（防止检测模拟定位）
 */
public class LocationHook {

    private static TraceSimulator traceSimulator;
    private static final List<LocationListener> registeredListeners = new ArrayList<>();
    private static Handler locationHandler;
    private static Runnable locationRunnable;
    private static XposedInterface xposed;

    public static void init(XposedInterface xp) {
        xposed = xp;
        traceSimulator = new TraceSimulator();

        hookGetLastKnownLocation();
        hookRequestLocationUpdates();
        hookRemoveUpdates();
        hookGetProviders();
        hookIsProviderEnabled();
        hookIsFromMockProvider();

        startLocationPusher();
    }

    /**
     * Hook LocationManager.getLastKnownLocation(String)
     */
    private static void hookGetLastKnownLocation() {
        try {
            Method method = LocationManager.class.getDeclaredMethod("getLastKnownLocation", String.class);
            HookHandle handle = xposed.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Location fake = traceSimulator.getCurrentLocation();
                        if (Config.DEBUG) {
                            xposed.log(android.util.Log.DEBUG, Config.TAG,
                                    "getLastKnownLocation -> " + fake.getLatitude() + ", " + fake.getLongitude());
                        }
                        return fake;
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookGetLastKnownLocation failed", t);
        }
    }

    /**
     * Hook LocationManager.requestLocationUpdates 的多个重载。
     * 拦截 LocationListener，加入推送列表。
     */
    private static void hookRequestLocationUpdates() {
        // 重载1: (String, long, float, LocationListener)
        try {
            Method method = LocationManager.class.getDeclaredMethod(
                    "requestLocationUpdates",
                    String.class, long.class, float.class, LocationListener.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        LocationListener listener = (LocationListener) chain.getArg(3);
                        registerListener(listener);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookRequestLocationUpdates(4 args) failed", t);
        }

        // 重载2: (String, long, float, LocationListener, Looper)
        try {
            Method method = LocationManager.class.getDeclaredMethod(
                    "requestLocationUpdates",
                    String.class, long.class, float.class, LocationListener.class, Looper.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        LocationListener listener = (LocationListener) chain.getArg(3);
                        registerListener(listener);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookRequestLocationUpdates(5 args) failed", t);
        }
    }

    /**
     * Hook LocationManager.removeUpdates(LocationListener)
     */
    private static void hookRemoveUpdates() {
        try {
            Method method = LocationManager.class.getDeclaredMethod("removeUpdates", LocationListener.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        LocationListener listener = (LocationListener) chain.getArg(0);
                        registeredListeners.remove(listener);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookRemoveUpdates failed", t);
        }
    }

    /**
     * Hook LocationManager.getProviders(boolean) — 确保 gps provider 存在
     */
    private static void hookGetProviders() {
        try {
            Method method = LocationManager.class.getDeclaredMethod("getProviders", boolean.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        @SuppressWarnings("unchecked")
                        List<String> providers = (List<String>) chain.proceed();
                        if (providers == null) {
                            providers = new ArrayList<>();
                        }
                        if (!providers.contains(LocationManager.GPS_PROVIDER)) {
                            providers.add(LocationManager.GPS_PROVIDER);
                        }
                        return providers;
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookGetProviders failed", t);
        }
    }

    /**
     * Hook LocationManager.isProviderEnabled(String) — gps 总是开启
     */
    private static void hookIsProviderEnabled() {
        try {
            Method method = LocationManager.class.getDeclaredMethod("isProviderEnabled", String.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        String provider = (String) chain.getArg(0);
                        if (LocationManager.GPS_PROVIDER.equals(provider)) {
                            return true;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookIsProviderEnabled failed", t);
        }
    }

    /**
     * Hook Location.isFromMockProvider() — 返回 false，防止 App 检测模拟定位
     */
    private static void hookIsFromMockProvider() {
        try {
            Method method = Location.class.getDeclaredMethod("isFromMockProvider");
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> false);
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookIsFromMockProvider failed", t);
        }
    }

    /**
     * 注册 LocationListener，并立即推送一次当前位置。
     */
    private static void registerListener(LocationListener listener) {
        if (listener != null && !registeredListeners.contains(listener)) {
            registeredListeners.add(listener);
            try {
                listener.onLocationChanged(traceSimulator.getCurrentLocation());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 定时向所有已注册的 LocationListener 推送伪造位置。
     */
    private static void startLocationPusher() {
        locationHandler = new Handler(Looper.getMainLooper());
        locationRunnable = new Runnable() {
            @Override
            public void run() {
                Location fake = traceSimulator.getCurrentLocation();
                for (LocationListener listener : new ArrayList<>(registeredListeners)) {
                    try {
                        listener.onLocationChanged(fake);
                    } catch (Throwable t) {
                        registeredListeners.remove(listener);
                    }
                }
                locationHandler.postDelayed(this, Config.LOCATION_INTERVAL_MS);
            }
        };
        locationHandler.postDelayed(locationRunnable, Config.LOCATION_INTERVAL_MS);
    }

    public static void resetTrace() {
        if (traceSimulator != null) {
            traceSimulator.reset();
        }
    }
}
