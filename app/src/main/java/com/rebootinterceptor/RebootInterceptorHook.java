package com.rebootinterceptor;

import java.lang.reflect.Method;

import android.app.AndroidAppHelper;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RebootInterceptorHook implements IXposedHookLoadPackage {

    private static final String TAG = "RebootInterceptor";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (!"android".equals(lpparam.packageName))
            return;

        XposedBridge.log(TAG + ": Loaded in system_server");

        hookShutdownThread(lpparam);
        hookSystemProperties(lpparam);
    }

    private void hookShutdownThread(final XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> shutdownThreadClass =
                    lpparam.classLoader.loadClass(
                            "com.android.server.power.ShutdownThread"
                    );

            XposedBridge.log(TAG + ": ShutdownThread loaded");

            for (Method method : shutdownThreadClass.getDeclaredMethods()) {

                String name = method.getName();

                XposedBridge.log(TAG + ": Found method -> " + name);

                if (!name.toLowerCase().contains("shutdown")
                        && !name.toLowerCase().contains("reboot"))
                    continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {

                        String reason = null;

                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                reason = (String) arg;
                            }
                        }

                        XposedBridge.log(TAG +
                                ": Intercepted "
                                + param.method.getName()
                                + " reason=" + reason);

                        if (reason != null &&
                                !reason.contains("user") &&
                                !reason.contains("global"))
                            return;

                        // block real reboot
                        param.setResult(null);

                        fakeReboot();
                    }
                });

                XposedBridge.log(TAG +
                        ": Hook installed on ShutdownThread." + name);
            }

        } catch (Throwable t) {

            XposedBridge.log(TAG +
                    ": Failed to hook ShutdownThread: " + t);
        }
    }

    private void hookSystemProperties(final XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> sp =
                    lpparam.classLoader.loadClass(
                            "android.os.SystemProperties"
                    );

            for (Method m : sp.getDeclaredMethods()) {

                if (!m.getName().equals("set"))
                    continue;

                if (m.getParameterTypes().length != 2)
                    continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {

                        String key = (String) param.args[0];

                        if ("sys.powerctl".equals(key)) {

                            XposedBridge.log(TAG +
                                    ": BLOCKED sys.powerctl -> "
                                    + param.args[1]);

                            param.setResult(null);
                        }
                    }
                });

                XposedBridge.log(TAG + ": Hooked SystemProperties.set");
            }

        } catch (Throwable t) {

            XposedBridge.log(TAG +
                    ": Failed to hook SystemProperties: " + t);
        }
    }

    private void fakeReboot() {

        new Thread(() -> {

            try {

                XposedBridge.log(TAG + ": Starting fake reboot");

                Object context =
                        AndroidAppHelper.currentApplication();

                Object pm =
                        context.getClass()
                                .getMethod("getSystemService", String.class)
                                .invoke(context, "power");

                Method goToSleep =
                        pm.getClass().getMethod(
                                "goToSleep",
                                long.class
                        );

                Method wakeUp =
                        pm.getClass().getMethod(
                                "wakeUp",
                                long.class
                        );

                // screen black
                goToSleep.invoke(pm, SystemClock.uptimeMillis());

                Thread.sleep(1000);

                // shutdown animation
                Runtime.getRuntime().exec(
                        "setprop service.bootanim.exit 0"
                );

                Runtime.getRuntime().exec(
                        "setprop ctl.start shutdownanimation"
                );

                Thread.sleep(5000);

                Runtime.getRuntime().exec(
                        "setprop ctl.stop shutdownanimation"
                );

                // black screen
                Thread.sleep(5000);

                // boot animation
                Runtime.getRuntime().exec(
                        "setprop service.bootanim.exit 0"
                );

                Runtime.getRuntime().exec(
                        "setprop ctl.start bootanim"
                );

                Thread.sleep(10000);

                Runtime.getRuntime().exec(
                        "setprop ctl.stop bootanim"
                );

                // wake screen
                wakeUp.invoke(pm, SystemClock.uptimeMillis());

                XposedBridge.log(TAG +
                        ": Fake reboot complete");

            } catch (Throwable e) {

                XposedBridge.log(TAG +
                        ": Fake reboot failed " + e);
            }

        }).start();
    }
}
