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

        if (!"android".equals(lpparam.packageName))
            return;

        XposedBridge.log(TAG + ": Loaded in system_server");

        try {
            // 1️⃣ Hook ShutdownThread methods
            Class<?> shutdownThread = lpparam.classLoader.loadClass(
                    "com.android.server.power.ShutdownThread"
            );

            for (Method method : shutdownThread.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.toLowerCase().contains("shutdown") &&
                    !name.toLowerCase().contains("reboot")) continue;

                XposedHelpers.findAndHookMethod(
                        shutdownThread,
                        name,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                String reason = null;
                                for (Object arg : param.args) {
                                    if (arg instanceof String) reason = (String) arg;
                                }
                                XposedBridge.log(TAG + ": Intercepted " + param.method.getName() +
                                        " reason=" + reason);

                                // Only intercept normal user/global reboots
                                if (reason != null &&
                                        !reason.contains("user") &&
                                        !reason.contains("global"))
                                    return;

                                // Prevent actual reboot
                                param.setResult(null);

                                // Run fake reboot sequence
                                fakeRebootSequence();
                            }
                        }
                );
            }

            // 2️⃣ Block PowerManagerService.lowLevelReboot
            Class<?> pms = lpparam.classLoader.loadClass(
                    "com.android.server.power.PowerManagerService"
            );

            XposedHelpers.findAndHookMethod(
                    pms,
                    "lowLevelReboot",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": lowLevelReboot BLOCKED");
                            param.setResult(null);
                        }
                    }
            );

            // 3️⃣ Block sys.powerctl property changes (setprop)
            XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    lpparam.classLoader,
                    "set",
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[0];
                            if ("sys.powerctl".equals(key)) {
                                XposedBridge.log(TAG + ": sys.powerctl change BLOCKED -> " + param.args[1]);
                                param.setResult(null);
                            }
                        }
                    }
            );

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hooking failed " + t);
        }
    }

    private void fakeRebootSequence() {

        new Thread(() -> {
            try {
                XposedBridge.log(TAG + ": Starting fake reboot sequence");

                // 1️⃣ Screen black
                Runtime.getRuntime().exec("service call window 18 i32 0"); // turn off screen
                Thread.sleep(1000);

                // 2️⃣ Shutdown animation (5s)
                Runtime.getRuntime().exec("setprop ctl.start shutdownanimation");
                Thread.sleep(5000);
                Runtime.getRuntime().exec("setprop ctl.stop shutdownanimation");

                // 3️⃣ Black screen (5s)
                Thread.sleep(5000);

                // 4️⃣ Boot animation (10s)
                Runtime.getRuntime().exec("setprop service.bootanim.exit 0");
                Runtime.getRuntime().exec("setprop ctl.start bootanim");
                Thread.sleep(10000);
                Runtime.getRuntime().exec("setprop ctl.stop bootanim");

                // 5️⃣ Turn screen on → lockscreen
                Runtime.getRuntime().exec("service call window 18 i32 1"); // wake screen

                XposedBridge.log(TAG + ": Fake reboot sequence completed");

            } catch (Throwable e) {
                XposedBridge.log(TAG + ": Fake reboot failed " + e);
            }
        }).start();
    }
}
