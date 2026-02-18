# Reboot Interceptor - LSPosed Module

Hooks `ShutdownThread.rebootOrShutdown()` inside system_server to intercept
power menu reboots and replace them with `stop && start`.

## What it intercepts
| Trigger | Result |
|---|---|
| Power menu → Restart | `stop && start` (soft restart) |
| `adb reboot` | `stop && start` |
| Power menu → Power off | Passes through (real shutdown) |
| `adb reboot recovery` | Passes through |
| `adb reboot bootloader` | Passes through |

## Build Instructions

### Prerequisites
- Android Studio (any recent version), OR
- JDK 11+ and Android SDK with build-tools

### Steps

1. **Set your SDK path** in `local.properties`:
   ```
   sdk.dir=/Users/YOU/Library/Android/sdk
   ```

2. **Build the APK:**
   ```bash
   # Mac/Linux
   ./gradlew assembleRelease

   # Windows
   gradlew.bat assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

3. **Install the APK on device:**
   ```bash
   adb install app/build/outputs/apk/release/app-release-unsigned.apk
   ```

4. **Activate in LSPosed Manager:**
   - Open LSPosed Manager app
   - Go to Modules
   - Enable "Reboot Interceptor"
   - Set scope to "System Framework" (android)
   - Reboot once (this is the last real reboot!)

5. **Verify it's working:**
   ```bash
   adb shell su -c "logcat | grep RebootInterceptor"
   ```
   Then tap Restart from the power menu — you should see:
   ```
   RebootInterceptor: *** INTERCEPTED - running stop && start instead ***
   ```

## Note
This module has no UI. It runs silently in system_server.
To uninstall: disable in LSPosed Manager and reboot normally via `adb reboot`.
