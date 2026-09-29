package com.fakerunner.xposed;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Xposed 模块主入口。
 *
 * Modern API (libxposed 102) 的模块入口类需要：
 * 1. 继承 {@link XposedModule}
 * 2. 在 META-INF/xposed/java_init.list 中声明
 * 3. 在 META-INF/xposed/module.prop 中配置 minApiVersion/targetApiVersion
 *
 * 生命周期：
 * - onModuleLoaded: 模块被加载到进程时调用一次
 * - onPackageLoaded: 包的默认 ClassLoader 就绪时（API 29+）
 * - onPackageReady: AppComponentFactory 创建 ClassLoader 后，此时可以加载 App 的类
 *
 * 注意：API 102 不允许调用 legacy de.robv.android.xposed API。
 */
public class MainHook extends XposedModule {

    private static final String TAG = Config.TAG;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 模块刚加载，此时还没有包信息
        // 可以在这里做一些不依赖包的初始化
        if (Config.DEBUG) {
            log(android.util.Log.INFO, TAG,
                    "onModuleLoaded, process=" + param.getProcessName()
                            + ", isSystemServer=" + param.isSystemServer());
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();

        if (Config.DEBUG) {
            log(android.util.Log.INFO, TAG, "onPackageReady: " + packageName);
        }

        // 在 system_server 中不做处理（如果需要 Hook 系统服务可以在这里加）
        if (param.isFirstPackage() && "android".equals(packageName)) {
            return;
        }

        // 跳过自己（模块 App 不 Hook 自己）
        if ("com.fakerunner.xposed".equals(packageName)) {
            return;
        }

        // 这里不过滤包名，因为作用域由 LSPosed 管理器控制
        // 模块只会被注入到用户勾选的 App 进程中
        // 但一个进程可能加载多个包，所以只对第一个包执行 Hook
        if (!param.isFirstPackage()) {
            return;
        }

        try {
            // 初始化定位和传感器 Hook
            // 传入 this，因为 XposedModule 实现了 XposedInterface，拥有 hook 能力
            LocationHook.init(this);
            SensorHook.init(this);

            if (Config.DEBUG) {
                log(android.util.Log.INFO, TAG,
                        "FakeRunner hooks installed for " + packageName);
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "Failed to install hooks for " + packageName, t);
        }
    }
}
