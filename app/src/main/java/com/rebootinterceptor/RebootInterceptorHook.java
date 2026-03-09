package com.rebootinterceptor;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        // Only hook system_server
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded in system_server");
        Log.i(TAG, "Loaded in system_server");

        try {
            // Hook SystemProperties.set(String key, String value)
            Class<?> spClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    spClass,
                    "set",
                    String.class,
                    String.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[0];
                            String value = (String) param.args[1];

                            if ("sys.powerctl".equals(key)) {
                                XposedBridge.log(TAG + ": sys.powerctl intercepted! value=" + value);
                                Log.w(TAG, "sys.powerctl intercepted! value=" + value);

                                // Cancel the real reboot
                                param.setResult(null);

                                // Run true soft reboot using stop; start
                                new Thread(() -> {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{
                                                "/system/bin/su",
                                                "-c",
                                                "stop; start"
                                        });
                                    } catch (Exception e) {
                                        Log.e(TAG, "Soft restart failed: " + e.getMessage());
                                        XposedBridge.log(TAG + ": Soft restart failed: " + e.getMessage());
                                    }
                                }, "RebootInterceptorThread").start();
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + ": sys.powerctl hook installed");
            Log.i(TAG, "sys.powerctl hook installed");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sys.powerctl hook failed: " + t);
            Log.e(TAG, "sys.powerctl hook failed: " + t);
        }
    }
}
