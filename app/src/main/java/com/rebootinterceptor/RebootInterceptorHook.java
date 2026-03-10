package com.rebootinterceptor;

import android.util.Log;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {
    private static final String TAG = "RebootInterceptor";
    private static final String PROP = "sys.reboot.interceptor";
    private static volatile boolean triggered = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");

        try {
            Class<?> shutdownThreadClass = lpparam.classLoader.loadClass(
                    "com.android.server.power.ShutdownThread"
            );
            XposedBridge.log(TAG + ": ShutdownThread loaded");

            for (Method method : shutdownThreadClass.getDeclaredMethods()) {
                String name = method.getName();
                XposedBridge.log(TAG + ": Found method -> " + name);

                if (!name.toLowerCase().contains("shutdown") &&
                    !name.toLowerCase().contains("reboot"))
                    continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String reason = null;
                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                reason = (String) arg;
                                break;
                            }
                        }

                        XposedBridge.log(TAG + ": Intercepted " +
                                param.method.getName() + " reason=" + reason);

                        // Only intercept user-initiated reboots/shutdowns
                        // Let recovery, bootloader, OTA etc. pass through
                        if (reason != null &&
                            !reason.isEmpty() &&
                            !reason.equals("userrequested") &&
                            !reason.contains("user") &&
                            !reason.contains("global"))
                            return;

                        // Block the real reboot
                        param.setResult(null);
                        XposedBridge.log(TAG + ": Real reboot blocked, triggering soft reboot");
                        softReboot();
                    }
                });

                XposedBridge.log(TAG + ": Hook installed on ShutdownThread." + name);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook ShutdownThread: " + t);
        }
    }

    private void softReboot() {
        if (triggered) return;
        triggered = true;

        new Thread(() -> {
            try {
                // -------------------------------------------------------
                // FIX: Do NOT use Runtime.getRuntime().exec("setprop ...")
                // system_server is SELinux-sandboxed and cannot exec shell
                // binaries. Instead, call SystemProperties.set() directly
                // via reflection — system_server already has full property
                // write permissions inside its own domain.
                // -------------------------------------------------------
                Class<?> systemProperties = Class.forName("android.os.SystemProperties");
                Method set = systemProperties.getMethod("set", String.class, String.class);
                set.invoke(null, PROP, "1");
                XposedBridge.log(TAG + ": Property set via SystemProperties API");
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": Failed to set property: " + e);
                // Reset flag so a retry is possible if triggered again
                triggered = false;
            }
        }).start();
    }
}
