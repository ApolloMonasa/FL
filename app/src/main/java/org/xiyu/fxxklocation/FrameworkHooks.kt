package org.xiyu.fxxklocation

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

// ============================================================
//  BACKUP: Hook UsageStatsManager.queryUsageStats
//  Returns EMPTY list to blind foreground detection.
// ============================================================
internal fun ModuleMain.installUsageStatsHook() {
    try {
        val usmClass = android.app.usage.UsageStatsManager::class.java
        XposedHelpers.findAndHookMethod(
            usmClass,
            "queryUsageStats",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val trace = android.util.Log.getStackTraceString(Throwable())
                        if (trace.contains("androidx.appcompat.view.widget")) {
                            param.result = ArrayList<android.app.usage.UsageStats>()
                        }
                    } catch (_: Throwable) {}
                }
            })
        log("[SYS] UsageStatsManager.queryUsageStats hooked (conditional empty list)")
    } catch (e: Throwable) {
        log("[SYS] UsageStats hook FAILED: ${e.message}")
    }

    // Clear stale cachedResult in ForegroundDetect (if accessible)
    try {
        val fdClass = Class.forName("androidx.appcompat.view.widget.\u0EAB")
        // Find static List<String> field (cachedResult is the only one)
        val cachedField = fdClass.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == List::class.java
        }
        if (cachedField != null) {
            cachedField.isAccessible = true
            cachedField.set(null, null)
            log("[SYS] ForegroundDetect.cachedResult cleared (${cachedField.name})")
        }
    } catch (e: Throwable) {
        log("[SYS] ForegroundDetect cache clear: ${e.javaClass.simpleName}")
    }
}

// Framework-level fallback: prevent test provider removal
internal fun ModuleMain.installFrameworkFallbackHooks() {
    try {
        XposedHelpers.findAndHookMethod(
            android.location.LocationManager::class.java,
            "removeTestProvider",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (bypassRemoveProvider.get() == true) return
                        val provider = param.args[0] as? String
                        if (provider == "gps" || provider == "network") {
                            param.result = null
                        }
                    } catch (_: Throwable) {}
                }
            })
        log("[SYS-FB] removeTestProvider hooked")
    } catch (e: Throwable) {
        log("[SYS-FB] removeTestProvider FAILED: ${e.message}")
    }
    try {
        XposedHelpers.findAndHookMethod(
            android.location.LocationManager::class.java,
            "clearTestProviderLocation",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val provider = param.args[0] as? String
                        if (provider == "gps" || provider == "network") {
                            param.result = null
                        }
                    } catch (_: Throwable) {}
                }
            })
        log("[SYS-FB] clearTestProviderLocation hooked")
    } catch (e: Throwable) {
        log("[SYS-FB] clearTestProviderLocation FAILED: ${e.message}")
    }
}

// ============================================================
//  Strip mock flag at framework level (system_server).
// ============================================================
internal fun ModuleMain.installMockFlagStrip() {
    // Strategy 1: Hook Location.writeToParcel to clear mock flag before sending to apps
    try {
        XposedHelpers.findAndHookMethod(
            Location::class.java, "writeToParcel",
            android.os.Parcel::class.java, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val loc = param.thisObject as Location
                        stripMockFlag(loc)
                    } catch (_: Throwable) {}
                }
            })
        log("[SYS-MOCK] writeToParcel hook installed")
    } catch (e: Throwable) {
        log("[SYS-MOCK] writeToParcel hook FAILED: $e")
    }

    // Strategy 2: Prevent mock flag from being set at all
    for (methodName in arrayOf("setMock", "setIsFromMockProvider")) {
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, methodName,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = false
                    }
                })
            log("[SYS-MOCK] $methodName hook installed")
        } catch (_: Throwable) {}
    }
}

internal fun stripMockFlag(loc: Location) {
    for (fieldName in arrayOf("mIsMock", "mIsFromMockProvider")) {
        try {
            val f = Location::class.java.getDeclaredField(fieldName)
            f.isAccessible = true
            f.setBoolean(loc, false)
        } catch (_: Throwable) {}
    }
    try { loc.extras?.remove("mockProvider") } catch (_: Throwable) {}
}

/**
 * Disable mock_location developer setting from system_server.
 * Some paranoid apps check Settings.Secure.getString("mock_location").
 * Setting it to "0" from system UID makes all apps see mock locations disabled,
 * while our system-level test provider injection still works (system UID bypasses the check).
 */
internal fun ModuleMain.disableMockLocationSetting() {
    Thread {
        try {
            // Wait for system to be ready
            Thread.sleep(5000)
            val ctx = getSystemServerContext() ?: return@Thread
            val resolver = ctx.contentResolver
            android.provider.Settings.Secure.putInt(resolver, "mock_location", 0)
            log("[SYS-MOCK] mock_location setting disabled (all apps see 0)")
        } catch (e: Throwable) {
            log("[SYS-MOCK] disableMockLocationSetting failed: $e")
        }
    }.apply { name = "FL-MockSetting"; isDaemon = true }.start()
}

// ============================================================
//  System-level step sensor injection — v42
//
//  Strategy: Use Android hidden SensorManager.injectSensorData() API
//  from system_server. Steps:
//    1) Hook Sensor.isDataInjectionSupported → return true for step types
//    2) Hook SystemSensorManager.requestDataInjection → bypass native check
//    3) Enable data injection mode via root (service call sensorservice)
//    4) Inject fake step counter/detector data from feeder thread
//
//  If HAL rejects at native level (no DATA_INJECTION flag),
//  per-app hooks in AntiMockDetection.kt remain as fallback.
// ============================================================
@Volatile
private var stepInjectionActive = false

internal fun ModuleMain.installStepSensorFromServer() {
    // Hook 1: Make step sensors report data-injection support
    try {
        val sensorCls = android.hardware.Sensor::class.java
        for (m in sensorCls.declaredMethods) {
            if (m.name == "isDataInjectionSupported") {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val type = (param.thisObject as android.hardware.Sensor).type
                            if (type == 19 || type == 18) param.result = true
                        } catch (_: Throwable) {}
                    }
                })
                log("[STEP-SYS] hooked Sensor.isDataInjectionSupported")
                break
            }
        }
    } catch (e: Throwable) {
        log("[STEP-SYS] hook isDataInjectionSupported failed: $e")
    }

    // Hook 2: Bypass nativeIsDataInjectionEnabled check in requestDataInjection
    try {
        val ssmClass = Class.forName("android.hardware.SystemSensorManager")
        for (m in ssmClass.declaredMethods) {
            if (m.name == "requestDataInjection") {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sensor = param.args[0] as android.hardware.Sensor
                            if (sensor.type == 19 || sensor.type == 18) {
                                // Force enable data injection mode at native level via root
                                enableSensorDataInjectionViaRoot()
                            }
                        } catch (_: Throwable) {}
                    }
                })
                log("[STEP-SYS] hooked SystemSensorManager.requestDataInjection")
                break
            }
        }
    } catch (e: Throwable) {
        log("[STEP-SYS] hook requestDataInjection failed: $e")
    }

    // Start injection feeder thread
    Thread {
        try {
            Thread.sleep(12000) // Wait for system + sensors to be fully ready

            val ctx = getSystemServerContext() ?: run {
                log("[STEP-SYS] no system context"); return@Thread
            }

            // Enable data injection globally via root (ISensorServer transaction 3)
            enableSensorDataInjectionViaRoot()

            val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE)
                as? android.hardware.SensorManager ?: run {
                log("[STEP-SYS] no SensorManager"); return@Thread
            }

            val stepCounter = sm.getDefaultSensor(19)   // TYPE_STEP_COUNTER
            val stepDetector = sm.getDefaultSensor(18)   // TYPE_STEP_DETECTOR
            if (stepCounter == null && stepDetector == null) {
                log("[STEP-SYS] no step sensors on device"); return@Thread
            }

            // Find hidden API methods via reflection
            val smcls = android.hardware.SensorManager::class.java
            val requestMethod = try {
                smcls.getDeclaredMethod(
                    "requestDataInjection",
                    android.hardware.Sensor::class.java,
                    Boolean::class.javaPrimitiveType
                ).also { it.isAccessible = true }
            } catch (_: Throwable) { null }

            val injectMethod = try {
                smcls.getDeclaredMethod(
                    "injectSensorData",
                    android.hardware.Sensor::class.java,
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                ).also { it.isAccessible = true }
            } catch (_: Throwable) { null }

            if (injectMethod == null) {
                log("[STEP-SYS] injectSensorData API not found — per-app fallback active")
                return@Thread
            }

            // Request data injection permission
            var counterOk = false
            var detectorOk = false
            if (requestMethod != null) {
                if (stepCounter != null) {
                    counterOk = try {
                        requestMethod.invoke(sm, stepCounter, true) as Boolean
                    } catch (e: Throwable) {
                        log("[STEP-SYS] requestDataInjection(counter) failed: $e"); false
                    }
                }
                if (stepDetector != null) {
                    detectorOk = try {
                        requestMethod.invoke(sm, stepDetector, true) as Boolean
                    } catch (e: Throwable) {
                        log("[STEP-SYS] requestDataInjection(detector) failed: $e"); false
                    }
                }
            }

            if (!counterOk && !detectorOk) {
                log("[STEP-SYS] data injection not accepted for step sensors — HAL likely lacks DATA_INJECTION flag")
                log("[STEP-SYS] per-app step hook fallback remains active for apps in LSPosed scope")
                return@Thread
            }

            stepInjectionActive = true
            log("[STEP-SYS] system-level step injection ACTIVE: counter=$counterOk detector=$detectorOk")

            var stepAccum = 0L
            var lastInjectTime = 0L

            while (!Thread.interrupted()) {
                val mlb = ourMlBinder
                if (mlb?.mocking != true || !mlb.stepSimActive) {
                    Thread.sleep(500); continue
                }

                val speed = mlb.stepSpeed.coerceIn(0.5f, 30.0f)
                val stepsPerSecond = speed / 0.7
                val intervalMs = (1000.0 / stepsPerSecond).toLong().coerceIn(100, 2000)

                val now = System.currentTimeMillis()
                if (lastInjectTime == 0L) lastInjectTime = now
                val elapsed = (now - lastInjectTime) / 1000.0
                val stepsToAdd = (elapsed * stepsPerSecond).toLong()
                if (stepsToAdd > 0) {
                    stepAccum += stepsToAdd
                    lastInjectTime = now
                }

                val ts = android.os.SystemClock.elapsedRealtimeNanos()
                if (counterOk && stepCounter != null) {
                    try { injectMethod.invoke(sm, stepCounter, floatArrayOf(stepAccum.toFloat()), 3, ts) }
                    catch (_: Throwable) {}
                }
                if (detectorOk && stepDetector != null) {
                    try { injectMethod.invoke(sm, stepDetector, floatArrayOf(1.0f), 3, ts) }
                    catch (_: Throwable) {}
                }

                Thread.sleep(intervalMs)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            log("[STEP-SYS] feeder error: $e")
        }
    }.apply { name = "FL-SysStepFeed"; isDaemon = true; start() }
}

/**
 * Enable sensor data injection mode at native SensorService level via root.
 * Calls ISensorServer.enableDataInjection(1) = transaction code FIRST_CALL + 2 = 3.
 */
private fun enableSensorDataInjectionViaRoot() {
    try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("sh", "-c", "service call sensorservice 3 i32 1 2>/dev/null")
        )
        val exitCode = proc.waitFor()
        log("[STEP-SYS] enableDataInjection via service call: exit=$exitCode")
    } catch (e: Throwable) {
        log("[STEP-SYS] enableDataInjection failed: $e")
    }
}
