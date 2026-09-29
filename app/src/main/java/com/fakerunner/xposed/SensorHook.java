package com.fakerunner.xposed;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * 传感器 Hook：拦截系统传感器 API，向 App 推送伪造的传感器数据。
 *
 * Modern API 方式：hook(Method).intercept(chain -> { ... })
 *
 * 覆盖：
 * 1. SensorManager.registerListener — 拦截监听器，保存引用
 * 2. SensorManager.unregisterListener — 移除监听器
 * 3. SensorManager.getSensorList / getDefaultSensor — 确保目标传感器存在
 * 4. 定时向所有监听器推送伪造的 SensorEvent
 */
public class SensorHook {

    private static SensorSimulator sensorSimulator;
    private static XposedInterface xposed;

    // 保存已注册的监听器及其监听的传感器类型
    // key: SensorEventListener, value: 该监听器监听的所有 Sensor
    private static final Map<SensorEventListener, List<Sensor>> listenerSensors = new HashMap<>();

    private static Handler sensorHandler;
    private static Runnable sensorRunnable;

    // 缓存反射创建的 SensorEvent 构造器
    private static Constructor<SensorEvent> sensorEventCtor;

    public static void init(XposedInterface xp) {
        xposed = xp;
        sensorSimulator = new SensorSimulator();

        hookRegisterListener();
        hookUnregisterListener();
        hookGetSensorList();
        hookGetDefaultSensor();

        initSensorEventConstructor();
        startSensorPusher();
    }

    /**
     * 通过反射获取 SensorEvent 的构造器。
     * SensorEvent 是 final 类，构造器是包私有的，需要反射。
     */
    private static void initSensorEventConstructor() {
        try {
            sensorEventCtor = SensorEvent.class.getDeclaredConstructor(int.class);
            sensorEventCtor.setAccessible(true);
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "initSensorEventConstructor failed", t);
        }
    }

    /**
     * Hook SensorManager.registerListener 的主要重载。
     * 保存监听器和对应传感器的映射。
     */
    private static void hookRegisterListener() {
        // 重载: registerListener(SensorEventListener, Sensor, int)
        try {
            Method method = SensorManager.class.getDeclaredMethod(
                    "registerListener",
                    SensorEventListener.class, Sensor.class, int.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        SensorEventListener listener = (SensorEventListener) chain.getArg(0);
                        Sensor sensor = (Sensor) chain.getArg(1);
                        addListenerSensor(listener, sensor);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookRegisterListener(3 args) failed", t);
        }

        // 重载: registerListener(SensorEventListener, Sensor, int, Handler)
        try {
            Method method = SensorManager.class.getDeclaredMethod(
                    "registerListener",
                    SensorEventListener.class, Sensor.class, int.class, Handler.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        SensorEventListener listener = (SensorEventListener) chain.getArg(0);
                        Sensor sensor = (Sensor) chain.getArg(1);
                        addListenerSensor(listener, sensor);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookRegisterListener(4 args) failed", t);
        }
    }

    /**
     * Hook SensorManager.unregisterListener
     */
    private static void hookUnregisterListener() {
        try {
            Method method = SensorManager.class.getDeclaredMethod(
                    "unregisterListener", SensorEventListener.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        SensorEventListener listener = (SensorEventListener) chain.getArg(0);
                        listenerSensors.remove(listener);
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookUnregisterListener failed", t);
        }

        // 重载: unregisterListener(SensorEventListener, Sensor)
        try {
            Method method = SensorManager.class.getDeclaredMethod(
                    "unregisterListener", SensorEventListener.class, Sensor.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        SensorEventListener listener = (SensorEventListener) chain.getArg(0);
                        Sensor sensor = (Sensor) chain.getArg(1);
                        List<Sensor> sensors = listenerSensors.get(listener);
                        if (sensors != null) {
                            sensors.remove(sensor);
                            if (sensors.isEmpty()) {
                                listenerSensors.remove(listener);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookUnregisterListener(sensor) failed", t);
        }
    }

    /**
     * Hook SensorManager.getSensorList — 确保目标传感器在列表中。
     * 如果设备没有该传感器，App 可能拿不到，这里确保返回。
     */
    private static void hookGetSensorList() {
        try {
            Method method = SensorManager.class.getDeclaredMethod("getSensorList", int.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        @SuppressWarnings("unchecked")
                        List<Sensor> sensors = (List<Sensor>) chain.proceed();
                        return sensors != null ? sensors : new ArrayList<>();
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookGetSensorList failed", t);
        }
    }

    /**
     * Hook SensorManager.getDefaultSensor — 确保能拿到传感器。
     */
    private static void hookGetDefaultSensor() {
        try {
            Method method = SensorManager.class.getDeclaredMethod("getDefaultSensor", int.class);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 如果系统返回 null（设备没有该传感器），尝试从 getSensorList 取
                        if (result == null) {
                            int type = (int) chain.getArg(0);
                            try {
                                SensorManager sm = (SensorManager) chain.getThisObject();
                                List<Sensor> list = sm.getSensorList(type);
                                if (list != null && !list.isEmpty()) {
                                    return list.get(0);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        return result;
                    });
        } catch (Throwable t) {
            xposed.log(android.util.Log.ERROR, Config.TAG, "hookGetDefaultSensor failed", t);
        }
    }

    /**
     * 记录监听器与传感器的映射。
     */
    private static void addListenerSensor(SensorEventListener listener, Sensor sensor) {
        if (listener == null || sensor == null) return;
        listenerSensors.computeIfAbsent(listener, k -> new ArrayList<>());
        List<Sensor> sensors = listenerSensors.get(listener);
        if (!sensors.contains(sensor)) {
            sensors.add(sensor);
        }
    }

    /**
     * 定时向所有监听器推送伪造的传感器数据。
     */
    private static void startSensorPusher() {
        sensorHandler = new Handler(Looper.getMainLooper());
        sensorRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<SensorEventListener, List<Sensor>> entry :
                        new ArrayList<>(listenerSensors.entrySet())) {
                    SensorEventListener listener = entry.getKey();
                    for (Sensor sensor : entry.getValue()) {
                        try {
                            SensorEvent event = createFakeSensorEvent(sensor, now);
                            if (event != null) {
                                listener.onSensorChanged(event);
                            }
                        } catch (Throwable t) {
                            // 单个监听器出错不影响其他
                        }
                    }
                }
                sensorHandler.postDelayed(this, Config.SENSOR_INTERVAL_MS);
            }
        };
        sensorHandler.postDelayed(sensorRunnable, Config.SENSOR_INTERVAL_MS);
    }

    /**
     * 根据传感器类型创建伪造的 SensorEvent。
     */
    private static SensorEvent createFakeSensorEvent(Sensor sensor, long timeMs) {
        if (sensorEventCtor == null) return null;

        int type = sensor.getType();
        float[] values;

        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                values = sensorSimulator.getAccelerometer(timeMs);
                break;
            case Sensor.TYPE_GYROSCOPE:
                values = sensorSimulator.getGyroscope(timeMs);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                values = sensorSimulator.getMagneticField(timeMs);
                break;
            case Sensor.TYPE_STEP_COUNTER:
                values = new float[]{(float) sensorSimulator.getStepCounter(timeMs)};
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                Float step = sensorSimulator.getStepDetector(timeMs);
                if (step == null) return null; // 这一步没有触发，不推送
                values = new float[]{step};
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                values = sensorSimulator.getRotationVector(timeMs);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                values = sensorSimulator.getLinearAcceleration(timeMs);
                break;
            case Sensor.TYPE_GRAVITY:
                values = sensorSimulator.getGravity(timeMs);
                break;
            default:
                // 未知类型，跳过
                return null;
        }

        try {
            SensorEvent event = sensorEventCtor.newInstance(values.length);
            event.values = values;
            // 用反射设置 sensor 字段（SensorEvent.sensor 是 public final）
            try {
                java.lang.reflect.Field sensorField = SensorEvent.class.getDeclaredField("sensor");
                sensorField.setAccessible(true);
                // 清除 final 修饰符，否则可能无法设置
                java.lang.reflect.Field accessFlags = java.lang.reflect.Field.class
                        .getDeclaredField("accessFlags");
                accessFlags.setAccessible(true);
                accessFlags.setInt(sensorField, sensorField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                sensorField.set(event, sensor);
            } catch (Throwable ignored) {
                // 某些版本 sensor 字段处理方式不同，忽略
            }
            event.timestamp = System.nanoTime();
            return event;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void resetSensors() {
        if (sensorSimulator != null) {
            sensorSimulator.reset();
        }
    }
}
