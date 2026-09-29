package com.fakerunner.xposed;

/**
 * 传感器数据模拟器：根据当前时间生成跑步时的传感器数值。
 *
 * 生成的数据需要和跑步动作在物理上自洽：
 * - 加速度：z 轴（上下）以步频振荡，叠加小幅随机噪声
 * - 陀螺仪：三轴以步频小幅振荡
 * - 步数计数器：按步频递增
 * - 步数检测器：每一步触发一次
 * - 磁场：地球磁场方向，加小噪声
 *
 * 所有传感器共享同一个时间轴，由调用方传入 timeMs。
 */
public class SensorSimulator {

    private final long startTimeMs;
    private final double stepIntervalMs;   // 每步的时间间隔
    private long stepCount = 0;             // 累计步数
    private long lastStepTimeMs = 0;        // 上一次触发步数检测的时间

    // 地球磁场参考值（微特斯拉，中国北方大致值）
    private static final float MAG_X = 20.0f;
    private static final float MAG_Y = 0.0f;
    private static final float MAG_Z = -45.0f;

    public SensorSimulator() {
        this.startTimeMs = System.currentTimeMillis();
        this.stepIntervalMs = 60000.0 / Config.STEP_RATE_PER_MIN;
    }

    public void reset() {
        stepCount = 0;
        lastStepTimeMs = 0;
    }

    /**
     * 获取加速度传感器数值（m/s²）。
     * 三轴：x（左右）、y（前后）、z（上下）。
     * 静止时 z ≈ 9.8（重力），跑步时 z 轴以步频振荡。
     */
    public float[] getAccelerometer(long timeMs) {
        double phase = getStepPhase(timeMs);

        // z 轴：重力 + 步频振荡
        float z = 9.8f + Config.ACCEL_AMPLITUDE * (float) Math.sin(phase);
        // x/y 轴：小幅振荡
        float x = Config.ACCEL_AMPLITUDE * 0.3f * (float) Math.sin(phase + Math.PI / 4);
        float y = Config.ACCEL_AMPLITUDE * 0.2f * (float) Math.cos(phase);

        // 加随机噪声，避免过于规律
        x += (float) (Math.random() - 0.5) * 0.5f;
        y += (float) (Math.random() - 0.5) * 0.5f;
        z += (float) (Math.random() - 0.5) * 0.5f;

        return new float[]{x, y, z};
    }

    /**
     * 获取陀螺仪数值（rad/s）。
     */
    public float[] getGyroscope(long timeMs) {
        double phase = getStepPhase(timeMs);
        float x = Config.GYRO_AMPLITUDE * (float) Math.sin(phase);
        float y = Config.GYRO_AMPLITUDE * 0.5f * (float) Math.cos(phase);
        float z = Config.GYRO_AMPLITUDE * 0.3f * (float) Math.sin(phase * 2);

        x += (float) (Math.random() - 0.5) * 0.1f;
        y += (float) (Math.random() - 0.5) * 0.1f;
        z += (float) (Math.random() - 0.5) * 0.1f;

        return new float[]{x, y, z};
    }

    /**
     * 获取步数计数器数值（累计步数）。
     * TYPE_STEP_COUNTER 返回自开机以来的累计步数。
     */
    public long getStepCounter(long timeMs) {
        updateStepCount(timeMs);
        // 加一个初始偏移，模拟开机后已有的步数
        return 10000 + stepCount;
    }

    /**
     * 获取步数检测器数值。
     * TYPE_STEP_DETECTOR 每检测到一步返回 1.0，否则不触发。
     * 返回 null 表示本次不触发。
     */
    public Float getStepDetector(long timeMs) {
        if (updateStepCount(timeMs)) {
            return 1.0f;
        }
        return null;
    }

    /**
     * 获取磁场传感器数值（微特斯拉）。
     */
    public float[] getMagneticField(long timeMs) {
        return new float[]{
                MAG_X + (float) (Math.random() - 0.5) * 2.0f,
                MAG_Y + (float) (Math.random() - 0.5) * 2.0f,
                MAG_Z + (float) (Math.random() - 0.5) * 2.0f
        };
    }

    /**
     * 获取旋转矢量（四元数）。
     * 由加速度和磁场合成一个稳定的姿态。
     */
    public float[] getRotationVector(long timeMs) {
        // 简化：返回一个近似水平放置的旋转矢量
        // x, y, z, w (可选 cos(theta/2))
        return new float[]{0.01f, 0.01f, 0.0f, 0.9999f};
    }

    /**
     * 获取线性加速度（去掉重力后的加速度）。
     */
    public float[] getLinearAcceleration(long timeMs) {
        float[] accel = getAccelerometer(timeMs);
        return new float[]{accel[0], accel[1], accel[2] - 9.8f};
    }

    /**
     * 获取重力传感器数值。
     */
    public float[] getGravity(long timeMs) {
        return new float[]{0.0f, 0.0f, 9.8f};
    }

    // ===================== 内部方法 =====================

    /**
     * 获取当前步数相位（弧度，0 到 2π）。
     */
    private double getStepPhase(long timeMs) {
        double elapsedMs = timeMs - startTimeMs;
        return (elapsedMs / stepIntervalMs) * 2 * Math.PI;
    }

    /**
     * 更新累计步数。
     * 返回 true 表示本次调用触发了新的一步（用于 STEP_DETECTOR）。
     */
    private boolean updateStepCount(long timeMs) {
        if (lastStepTimeMs == 0) {
            lastStepTimeMs = timeMs;
            return false;
        }
        if (timeMs - lastStepTimeMs >= stepIntervalMs) {
            stepCount++;
            lastStepTimeMs = timeMs;
            return true;
        }
        return false;
    }
}
