package com.rebootinterceptor;

import android.util.Log;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");
        Log.i(TAG, "Loaded in system_server");

        hookShutdownThread(lpparam);
        hookPowerManager(lpparam);
    }

    // ----------------------------
    // ShutdownThread hooks
    // ----------------------------

    private void hookShutdownThread(XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread",
                    lpparam.classLoader
            );

            XposedBridge.log(TAG + ": Found ShutdownThread");

            for (Method m : shutdownThread.getDeclaredMethods()) {

                String name = m.getName().toLowerCase();

                XposedBridge.log(TAG + ": ShutdownThread method -> " + name);

                if (name.contains("shutdown")
                        || name.contains("reboot")
                        || name.contains("sequence")) {

                    XposedBridge.hookMethod(m, shutdownHook(name));
                    XposedBridge.log(TAG + ": Hooked ShutdownThread." + name);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ShutdownThread hook failed: " + t);
        }
    }

    // ----------------------------
    // PowerManagerService hooks
    // ----------------------------

    private void hookPowerManager(XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> pms = XposedHelpers.findClass(
                    "com.android.server.power.PowerManagerService",
                    lpparam.classLoader
            );

            XposedBridge.log(TAG + ": Found PowerManagerService");

            for (Method m : pms.getDeclaredMethods()) {

                String name = m.getName().toLowerCase();

                XposedBridge.log(TAG + ": PMS method -> " + name);

                if (name.contains("shutdown")
                        || name.contains("reboot")) {

                    XposedBridge.hookMethod(m, shutdownHook(name));
                    XposedBridge.log(TAG + ": Hooked PowerManagerService." + name);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PowerManagerService hook failed: " + t);
        }
    }

    // ----------------------------
    // Main interceptor
    // ----------------------------

    private XC_MethodHook shutdownHook(final String methodName) {

        return new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

                XposedBridge.log(TAG + ": INTERCEPTED -> " + methodName);
                Log.i(TAG, "Intercepted reboot/shutdown via " + methodName);

                // Block original reboot
                param.setResult(null);

                // Trigger soft reboot
                performSoftReboot();
            }
        };
    }

    // ----------------------------
    // Soft reboot (zygote restart)
    // ----------------------------

    private void performSoftReboot() {

        new Thread(() -> {

            try {

                XposedBridge.log(TAG + ": Restarting zygote");

                Runtime.getRuntime().exec(new String[]{
                        "/system/bin/su",
                        "-c",
                        "setprop ctl.restart zygote"
                });

            } catch (Throwable e) {

                XposedBridge.log(TAG + ": Soft reboot failed " + e);
            }

        }).start();
    }
}
