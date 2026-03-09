package com.rebootinterceptor;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "ShutdownInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        // Only hook system_server
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");
        Log.i(TAG, "Loaded in system_server");

        try {
            // Find ShutdownThread class
            Class<?> shutdownThreadClass = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread",
                    lpparam.classLoader
            );

            // Hook shutdownInner() method
            XposedHelpers.findAndHookMethod(
                    shutdownThreadClass,
                    "shutdownInner",
                    boolean.class,  // reboot
                    String.class,   // reason
                    boolean.class,  // wait
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

                            boolean reboot = (Boolean) param.args[0];
                            String reason = (String) param.args[1];

                            XposedBridge.log(TAG + ": Intercepted shutdownInner! reboot=" + reboot + ", reason=" + reason);
                            Log.i(TAG, "Intercepted shutdownInner! reboot=" + reboot + ", reason=" + reason);

                            // Cancel full shutdown
                            param.setResult(null);

                            // Perform fast soft reboot via zygote restart
                            new Thread(() -> {
                                try {
                                    XposedBridge.log(TAG + ": Performing soft reboot via zygote...");
                                    Log.i(TAG, "Performing soft reboot via zygote...");

                                    Runtime.getRuntime().exec(new String[]{
                                            "su",
                                            "-c",
                                            "setprop ctl.restart zygote"
                                    });
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Soft reboot failed: " + e.getMessage());
                                    Log.e(TAG, "Soft reboot failed", e);
                                }
                            }, "ShutdownInterceptorThread").start();
                        }
                    }
            );

            XposedBridge.log(TAG + ": shutdownInner() hook installed successfully");
            Log.i(TAG, "shutdownInner() hook installed successfully");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook shutdownInner(): " + t);
            Log.e(TAG, "Failed to hook shutdownInner()", t);
        }
    }
}
