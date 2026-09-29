package com.fakerunner.xposed;

/**
 * 全局配置：轨迹、速度、传感器参数
 *
 * 使用前请修改 TRACK_POINTS 为你自己的跑步路线。
 * 可以用高德/百度地图的「路线规划」导出坐标，或者手动在地图上取点。
 */
public class Config {

    // ===================== 定位相关 =====================

    /**
     * 轨迹点序列（纬度, 经度）。
     * 模拟器会按顺序沿这些点移动，并在点之间做插值。
     * 请替换为你自己的校园跑步路线。
     */
    public static final double[][] TRACK_POINTS = {
            {39.908823, 116.397470},  // 示例：天安门
            {39.908823, 116.398470},  // 向东移动
            {39.909823, 116.398470},  // 向北移动
            {39.909823, 116.397470},  // 向西移动
            {39.908823, 116.397470},  // 回到起点（绕圈）
    };

    /**
     * 跑步速度（米/秒）。
     * 普通慢跑约 2-3 m/s（配速 5'30"-8'00"）。
     * 快跑约 3.5-4.5 m/s。
     */
    public static final double RUN_SPEED = 3.0;

    /**
     * 定位更新间隔（毫秒）。
     * 一般 GPS 每秒更新一次，这里设为 1000ms。
     * 注意：Location 的 time/elapsedRealtimeNanos 必须连续递增，
     * 否则 App 可能判定为异常。
     */
    public static final long LOCATION_INTERVAL_MS = 1000;

    /**
     * GPS 精度（米）。
     * 真实 GPS 精度一般在 3-10 米之间，设太大会被怀疑。
     */
    public static final float LOCATION_ACCURACY = 5.0f;

    /**
     * 海拔高度（米）。
     * 可设为你学校所在地的大致海拔。
     */
    public static final double ALTITUDE = 50.0;

    // ===================== 传感器相关 =====================

    /**
     * 步频（步/分钟）。
     * 正常跑步步频约 160-180 步/分钟。
     */
    public static final int STEP_RATE_PER_MIN = 170;

    /**
     * 加速度传感器数据推送间隔（毫秒）。
     * 真实加速度频率很高（50-200Hz），但 App 一般只需要 ~20-50Hz。
     * 设为 50ms（20Hz）足够。
     */
    public static final long SENSOR_INTERVAL_MS = 50;

    /**
     * 加速度振荡幅度（m/s²）。
     * 跑步时 z 轴（上下方向）加速度在重力 9.8 附近振荡。
     */
    public static final float ACCEL_AMPLITUDE = 3.0f;

    /**
     * 陀螺仪振荡幅度（rad/s）。
     * 跑步时身体轻微晃动。
     */
    public static final float GYRO_AMPLITUDE = 0.3f;

    // ===================== 调试相关 =====================

    /**
     * 是否在 LSPosed 日志中输出调试信息。
     * 调试时设为 true，稳定后设为 false。
     */
    public static final boolean DEBUG = true;

    /**
     * 日志标签。
     */
    public static final String TAG = "FakeRunner";
}
