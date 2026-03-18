package org.xiyu.fxxklocation

import android.os.IBinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

// ============================================================
//  LAYER 2: system_server — hookSystemServer + service polling + heartbeat
// ============================================================
internal fun ModuleMain.hookSystemServer() {
    if (sysHooked) return
    sysHooked = true

    log("[SYS] v41 Installing immediate system_server hooks...")

    hookServiceManagerAddService()
    installUsageStatsHook()
    installFrameworkFallbackHooks()
    installMockFlagStrip()
    disableMockLocationSetting()
    installGnssHooks()
    installActiveGnssFromServer()
    installActiveNmeaFromServer()
    installStepSensorFromServer()

    Thread {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)

            // Phase 1: Proactively register service_fl_ml
            var mlReady = false
            var secs = 0
            while (!mlReady && secs < 120) {
                val existing = getService.invoke(null, "service_fl_ml") as? IBinder
                if (existing != null) {
                    mlReady = true
                    log("[SYS] service_fl_ml already registered (${secs}s), class=${existing.javaClass.name}")
                    processServiceFlMlFinder(existing)
                    break
                }
                if (ourMlBinder == null) {
                    try {
                        registerMockLocationService()
                        if (ourMlBinder != null) {
                            mlReady = true
                            log("[SYS] service_fl_ml self-registered at ${secs}s")
                            break
                        }
                    } catch (e: Throwable) {
                        if (secs % 10 == 0) log("[SYS] service_fl_ml register attempt (${secs}s): ${e.message}")
                    }
                } else {
                    mlReady = true
                    break
                }
                Thread.sleep(1000); secs += 1
                if (secs % 30 == 0) log("[SYS] Waiting to register service_fl_ml... (${secs}s)")
            }
            if (!mlReady) {
                log("[SYS] WARN: service_fl_ml not registered after 120s — SELinux policy may not be patched yet")
            }

            // Phase 2: Watch for service_fl_xp (optional)
            Thread {
                try {
                    var xpSecs = 0
                    while (xpSecs < 120) {
                        val b = getService.invoke(null, "service_fl_xp") as? IBinder
                        if (b != null) {
                            log("[SYS] service_fl_xp found (${xpSecs}s), class=${b.javaClass.name}")
                            processServiceFlMlFinder(b)
                            break
                        }
                        Thread.sleep(2000); xpSecs += 2
                    }
                    if (xpSecs >= 120) log("[SYS] service_fl_xp not found after 120s (OK — not required)")
                } catch (e: Throwable) {
                    log("[SYS] service_fl_xp poll error: $e")
                }
            }.apply { name = "FL-XpPoll"; isDaemon = true }.start()

            // Periodic heartbeat
            while (true) {
                Thread.sleep(60000)
                val mlb = ourMlBinder
                val mocking = mlb?.mocking == true
                val hasLoc = mlb?.currentLocation != null
                val gnssCount = sysGnssListeners.size
                log("[SYS] heartbeat: alive | mocking=$mocking hasLoc=$hasLoc gnssListeners=$gnssCount")
            }

        } catch (e: Throwable) {
            log("[SYS] SYS_CRITICAL: $e")
        }
    }.apply {
        name = "FxxkLocation-SysPoll"
        isDaemon = true
    }.start()
}

internal fun ModuleMain.processServiceFlMlFinder(binder: IBinder) {
    val binderCls = binder.javaClass
    val aidlIface = binderCls.interfaces.firstOrNull { iface ->
        android.os.IInterface::class.java.isAssignableFrom(iface)
    }
    val descriptor = try {
        binder.interfaceDescriptor
    } catch (_: Throwable) { null }
    log("[SYS] Processing binder: class=${binderCls.name} descriptor=$descriptor aidl=${aidlIface?.name}")

    if (descriptor == FL_XP_DESCRIPTOR || aidlIface?.name?.contains("IXPServer") == true) {
        log("[SYS] service_fl_xp (IXPServer) detected — skipping IMockLocationManager hooks")
        return
    }

    // Skip if this is our own service_fl_ml binder
    if (binder === ourMlBinder) {
        mlBinderHooked = true
        log("[SYS] service_fl_ml is our own binder — no additional hooks needed")
        return
    }

    synchronized(this) {
        if (mlBinderHooked) return
        mlBinderHooked = true
    }
    log("[SYS] service_fl_ml (IMockLocationManager) detected — installing hooks")
    val binderCl = binderCls.classLoader
    hookBinderAidlMethods(binderCls)

    val flCl = tryFindFlClassLoader(binder)
    if (flCl != null) {
        installSystemServerHooks(flCl)
    } else if (binderCl != null) {
        val enfCls = findEnforcerByStructure(binderCl)
        if (enfCls != null) {
            hookEnforcerClass(enfCls)
        } else {
            log("[SYS] CL strategies all failed (binder hooks are primary)")
        }
    }
}

internal fun ModuleMain.hookServiceManagerAddService() {
    try {
        val smClass = Class.forName("android.os.ServiceManager")
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                if (name == "service_fl_ml" || name == "service_fl_xp") {
                    log("[SYS] addService('$name') BEFORE — about to register")
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                if (name == "service_fl_ml" || name == "service_fl_xp") {
                    if (param.throwable != null) {
                        log("[SYS] addService('$name') FAILED: ${param.throwable}")
                    } else {
                        log("[SYS] addService('$name') SUCCESS")
                    }
                    val binder = param.args[1] as? IBinder ?: return
                    processServiceFlMlFinder(binder)
                    // When service_fl_xp is registered, also register our service_fl_ml
                    if (name == "service_fl_xp" && param.throwable == null && ourMlBinder == null) {
                        registerMockLocationService()
                    }
                }
            }
        }
        for (m in smClass.declaredMethods) {
            if (m.name == "addService") {
                XposedBridge.hookMethod(m, hook)
            }
        }
    } catch (e: Throwable) {
        log("[SYS] ServiceManager hook failed: $e")
    }
}
