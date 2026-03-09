package com.rebootinterceptor;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/*
 * RebootInterceptor
 *
 * Converts power menu reboot into a soft restart (userspace restart)
 * by intercepting system reboot calls and executing:
 *
 *      setprop ctl.restart zygote
 *
 * This avoids:
 *  - kernel reboot
 *  - bootloader
 *  - boot animation
 *
 * Result:
 * SystemUI + Android framework restart instantly.
 */

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": Loaded in system_server");
        Log.i(TAG, "Loaded in system_server");

        hookWindowManagerService(lpparam.classLoader);
        hookShutdownThread(lpparam.classLoader);
    }


    /*
     * PRIMARY HOOK
     * Most power menus call WindowManagerService.reboot()
     */

    private void hookWindowManagerService(ClassLoader classLoader) {

        try {

            Class<?> wms = XposedHelpers.findClass(
                    "com.android.server.wm.WindowManagerService",
                    classLoader
            );

            XposedHelpers.findAndHookMethod(
                    wms,
                    "reboot",
                    boolean.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {

                            Log.w(TAG, "WindowManagerService reboot intercepted");
                            XposedBridge.log(TAG + ": WMS reboot intercepted");

                            param.setResult(null);

                            performSoftRestart("WMS");
                        }
                    }
            );

            Log.i(TAG, "WMS hook installed");
            XposedBridge.log(TAG + ": WMS hook installed");

        } catch (Throwable t) {

            Log.e(TAG, "WMS hook failed: " + t);
            XposedBridge.log(TAG + ": WMS hook failed: " + t);
        }
    }


    /*
     * FALLBACK HOOK
     * Some ROMs call ShutdownThread directly
     */

    private void hookShutdownThread(ClassLoader classLoader) {

        try {

            Class<?> shutdownThread = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread",
                    classLoader
            );

            XposedHelpers.findAndHookMethod(
                    shutdownThread,
                    "reboot",
                    android.content.Context.class,
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {

                            String reason = (String) param.args[1];

                            Log.w(TAG, "ShutdownThread reboot intercepted reason=" + reason);
                            XposedBridge.log(TAG + ": ShutdownThread reboot intercepted");

                            if ("recovery".equals(reason) || "bootloader".equals(reason)) {
                                return;
                            }

                            param.setResult(null);

                            performSoftRestart("ShutdownThread");
                        }
                    }
            );

            Log.i(TAG, "ShutdownThread fallback hook installed");
            XposedBridge.log(TAG + ": ShutdownThread fallback hook installed");

        } catch (Throwable t) {

            Log.e(TAG, "ShutdownThread hook failed: " + t);
            XposedBridge.log(TAG + ": ShutdownThread hook failed: " + t);
        }
    }


    /*
     * Executes the soft restart
     */

    private void performSoftRestart(String source) {

        new Thread(() -> {

            try {

                Log.i(TAG, source + ": executing soft restart");
                XposedBridge.log(TAG + ": executing soft restart");

                Thread.sleep(200);

                Runtime.getRuntime().exec(new String[]{
                        "/system/bin/su",
                        "-c",
                        "setprop ctl.restart zygote"
                });

            } catch (Throwable t) {

                Log.e(TAG, source + ": restart failed " + t);
                XposedBridge.log(TAG + ": restart failed " + t);
            }

        }, "RebootInterceptorThread").start();
    }
}
