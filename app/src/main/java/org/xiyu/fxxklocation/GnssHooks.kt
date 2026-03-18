package org.xiyu.fxxklocation

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArrayList

// ============================================================
//  Passive GNSS hooks — system_server (replace real GnssStatus)
// ============================================================
internal fun ModuleMain.installGnssHooks() {
    var hookCount = 0

    // Hook GnssStatus.Callback.onSatelliteStatusChanged to replace with fake status
    try {
        val gnssCallbackCls = Class.forName("android.location.GnssStatus\$Callback")
        val onSatChanged = gnssCallbackCls.getMethod(
            "onSatelliteStatusChanged", Class.forName("android.location.GnssStatus")
        )
        XposedBridge.hookMethod(onSatChanged, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (ourMlBinder?.mocking != true) return
                    val fakeStatus = buildFakeGnssStatus()
                    if (fakeStatus != null) param.args[0] = fakeStatus
                } catch (_: Throwable) {}
            }
        })
        hookCount++
        log("[SYS-GNSS] onSatelliteStatusChanged hook installed")
    } catch (e: Throwable) {
        log("[SYS-GNSS] Callback hook failed: $e")
    }

    // Hook LocationManager.getGnssYearOfHardware to return recent year
    try {
        XposedHelpers.findAndHookMethod(
            android.location.LocationManager::class.java,
            "getGnssYearOfHardware",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try { if (ourMlBinder?.mocking == true) param.result = 2024 } catch (_: Throwable) {}
                }
            })
        hookCount++
    } catch (_: Throwable) {}

    log("[SYS-GNSS] $hookCount passive GNSS hooks installed")
}

/**
 * Build a fake GnssStatus with 12 GPS satellites.
 * Uses AOSP internal constructor via reflection.
 */
internal fun buildFakeGnssStatus(): Any? {
    try {
        val gnssStatusCls = Class.forName("android.location.GnssStatus")
        val svCount = 12
        val svidWithFlags = IntArray(svCount)
        val cn0s = FloatArray(svCount)
        val elevations = FloatArray(svCount)
        val azimuths = FloatArray(svCount)
        val carrierFreqs = FloatArray(svCount)
        val basebandCn0s = FloatArray(svCount)
        val rng = java.util.Random()
        for (i in 0 until svCount) {
            val svid = i + 1
            val flags = 1 or 2 or (if (i < 10) 4 else 0) or 8 or 16
            val constellation = 1 // GPS
            svidWithFlags[i] = (svid shl 12) or (constellation shl 8) or flags
            cn0s[i] = 25.0f + rng.nextFloat() * 20.0f
            elevations[i] = 15.0f + rng.nextFloat() * 65.0f
            azimuths[i] = rng.nextFloat() * 360.0f
            carrierFreqs[i] = 1575.42f
            basebandCn0s[i] = cn0s[i] - 3.0f
        }

        // Try wrap() static method first (API 30+)
        try {
            val wrap = gnssStatusCls.getMethod(
                "wrap", Int::class.javaPrimitiveType, IntArray::class.java,
                FloatArray::class.java, FloatArray::class.java,
                FloatArray::class.java, FloatArray::class.java,
                FloatArray::class.java
            )
            return wrap.invoke(null, svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs, basebandCn0s)
        } catch (_: Throwable) {}

        // Fallback: constructor with 7 args
        try {
            val ctor = gnssStatusCls.getDeclaredConstructor(
                Int::class.javaPrimitiveType, IntArray::class.java,
                FloatArray::class.java, FloatArray::class.java,
                FloatArray::class.java, FloatArray::class.java,
                FloatArray::class.java
            )
            ctor.isAccessible = true
            return ctor.newInstance(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs, basebandCn0s)
        } catch (_: Throwable) {}

        // Fallback: constructor with 6 args (no basebandCn0s, older AOSP)
        val ctor6 = gnssStatusCls.getDeclaredConstructor(
            Int::class.javaPrimitiveType, IntArray::class.java,
            FloatArray::class.java, FloatArray::class.java,
            FloatArray::class.java, FloatArray::class.java
        )
        ctor6.isAccessible = true
        return ctor6.newInstance(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs)
    } catch (e: Throwable) {
        log("[GNSS] buildFakeGnssStatus failed: $e")
        return null
    }
}

// ============================================================
//  Active GNSS injection from system_server — v40
// ============================================================
internal fun ModuleMain.installActiveGnssFromServer() {
    val listenerCls = try {
        Class.forName("android.location.IGnssStatusListener")
    } catch (_: Throwable) {
        log("[SYS-GNSS] IGnssStatusListener not found, skipping active injection")
        return
    }

    val candidates = listOf(
        "com.android.server.location.gnss.GnssManagerService",
        "com.android.server.location.gnss.GnssStatusProvider",
        "com.android.server.LocationManagerService"
    )

    var hooked = false
    for (clsName in candidates) {
        val cls = try { Class.forName(clsName) } catch (_: Throwable) { continue }
        for (m in cls.declaredMethods) {
            if (!m.name.lowercase().let { it.contains("register") && it.contains("gnss") }) continue
            val hasListener = m.parameterTypes.any { listenerCls.isAssignableFrom(it) }
            if (!hasListener) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.throwable != null) return
                            val listener = param.args.firstOrNull { listenerCls.isInstance(it) }
                            if (listener != null && sysGnssListeners.addIfAbsent(listener)) {
                                log("[SYS-GNSS] captured listener via ${m.name} (total=${sysGnssListeners.size})")
                                ensureSysGnssFeederRunning()
                            }
                        } catch (_: Throwable) {}
                    }
                })
                hooked = true
                log("[SYS-GNSS] hooked ${clsName}.${m.name}")
            } catch (e: Throwable) {
                log("[SYS-GNSS] hook ${m.name} failed: $e")
            }
        }
        // Hook unregister methods to clean up dead listeners
        for (m in cls.declaredMethods) {
            if (!m.name.lowercase().let { it.contains("unregister") && it.contains("gnss") }) continue
            val hasListener = m.parameterTypes.any { listenerCls.isAssignableFrom(it) }
            if (!hasListener) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val listener = param.args.firstOrNull { listenerCls.isInstance(it) }
                            if (listener != null) sysGnssListeners.remove(listener)
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}
        }
        if (hooked) break
    }

    if (!hooked) log("[SYS-GNSS] WARNING: no GNSS registration hook found in known classes")
}

internal fun ModuleMain.ensureSysGnssFeederRunning() {
    if (sysGnssFeederStarted) return
    sysGnssFeederStarted = true

    Thread {
        try {
            val listenerCls = Class.forName("android.location.IGnssStatusListener")
            val onStarted = try { listenerCls.getMethod("onGnssStarted") } catch (_: Throwable) { null }
            val onSvChanged = listenerCls.methods.firstOrNull { it.name == "onSvStatusChanged" }
            if (onSvChanged == null) {
                log("[SYS-GNSS] FATAL: onSvStatusChanged not found on IGnssStatusListener")
                sysGnssFeederStarted = false
                return@Thread
            }
            log("[SYS-GNSS] onSvStatusChanged signature: ${onSvChanged.parameterTypes.joinToString { it.simpleName }}")

            Thread.sleep(500)

            // Fire onGnssStarted for initial listeners
            if (onStarted != null) {
                for (l in ArrayList(sysGnssListeners)) {
                    try { onStarted.invoke(l) } catch (_: Throwable) {}
                }
            }

            log("[SYS-GNSS] active feeder started (${sysGnssListeners.size} listeners)")

            while (!Thread.interrupted()) {
                Thread.sleep(1000)
                if (sysGnssListeners.isEmpty() || ourMlBinder?.mocking != true) continue

                val args = buildFakeGnssArgs(onSvChanged.parameterTypes)
                for (l in ArrayList(sysGnssListeners)) {
                    try {
                        onSvChanged.invoke(l, *args)
                    } catch (e: Throwable) {
                        val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
                        if (cause is android.os.DeadObjectException) {
                            sysGnssListeners.remove(l)
                            log("[SYS-GNSS] removed dead listener (remaining=${sysGnssListeners.size})")
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            log("[SYS-GNSS] feeder error: $e")
            sysGnssFeederStarted = false
        }
    }.apply {
        name = "FL-SysGnssFeed"
        isDaemon = true
        start()
    }
}

/**
 * Build fake GNSS parameter array matching the discovered onSvStatusChanged signature.
 */
internal fun buildFakeGnssArgs(paramTypes: Array<Class<*>>): Array<Any> {
    val svCount = 12
    val svidWithFlags = IntArray(svCount)
    val cn0s = FloatArray(svCount)
    val elevations = FloatArray(svCount)
    val azimuths = FloatArray(svCount)
    val carrierFreqs = FloatArray(svCount)
    val basebandCn0s = FloatArray(svCount)
    val rng = java.util.Random()
    for (i in 0 until svCount) {
        val svid = i + 1
        val flags = 1 or 2 or (if (i < 10) 4 else 0) or 8 or 16
        svidWithFlags[i] = (svid shl 12) or (1 shl 8) or flags
        cn0s[i] = 25.0f + rng.nextFloat() * 20.0f
        elevations[i] = 15.0f + rng.nextFloat() * 65.0f
        azimuths[i] = rng.nextFloat() * 360.0f
        carrierFreqs[i] = 1575.42f
        basebandCn0s[i] = cn0s[i] - 3.0f
    }
    return when (paramTypes.size) {
        7 -> arrayOf(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs, basebandCn0s)
        6 -> arrayOf(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs)
        else -> arrayOf(svCount, svidWithFlags, cn0s, elevations, azimuths, carrierFreqs, basebandCn0s)
    }
}

// ============================================================
//  Active NMEA injection from system_server
//  Captures IGnssNmeaListener via GnssManagerService registration
//  hooks, then feeds fake NMEA sentences (GPGGA/GPRMC) matching
//  the current mock location. All apps that register for NMEA
//  will receive our fake sentences via Binder IPC.
// ============================================================
@Volatile
private var sysNmeaFeederStarted = false
private val sysNmeaListeners = CopyOnWriteArrayList<Any>()

internal fun ModuleMain.installActiveNmeaFromServer() {
    val listenerCls = try {
        Class.forName("android.location.IGnssNmeaListener")
    } catch (_: Throwable) {
        log("[SYS-NMEA] IGnssNmeaListener not found, skipping")
        return
    }

    val candidates = listOf(
        "com.android.server.location.gnss.GnssManagerService",
        "com.android.server.location.gnss.GnssNmeaProvider",
        "com.android.server.LocationManagerService"
    )

    var hooked = false
    for (clsName in candidates) {
        val cls = try { Class.forName(clsName) } catch (_: Throwable) { continue }
        for (m in cls.declaredMethods) {
            if (!m.name.lowercase().let { it.contains("register") && it.contains("nmea") }) continue
            if (!m.parameterTypes.any { listenerCls.isAssignableFrom(it) }) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.throwable != null) return
                            val listener = param.args.firstOrNull { listenerCls.isInstance(it) }
                            if (listener != null && sysNmeaListeners.addIfAbsent(listener)) {
                                log("[SYS-NMEA] captured listener via ${m.name} (total=${sysNmeaListeners.size})")
                                ensureNmeaFeederRunning()
                            }
                        } catch (_: Throwable) {}
                    }
                })
                hooked = true
                log("[SYS-NMEA] hooked ${clsName}.${m.name}")
            } catch (e: Throwable) {
                log("[SYS-NMEA] hook ${m.name} failed: $e")
            }
        }
        // Unregister hooks
        for (m in cls.declaredMethods) {
            if (!m.name.lowercase().let { it.contains("unregister") && it.contains("nmea") }) continue
            if (!m.parameterTypes.any { listenerCls.isAssignableFrom(it) }) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val listener = param.args.firstOrNull { listenerCls.isInstance(it) }
                            if (listener != null) sysNmeaListeners.remove(listener)
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}
        }
        if (hooked) break
    }

    if (!hooked) log("[SYS-NMEA] WARNING: no NMEA registration hook found")
}

private fun ModuleMain.ensureNmeaFeederRunning() {
    if (sysNmeaFeederStarted) return
    sysNmeaFeederStarted = true

    Thread {
        try {
            val listenerCls = Class.forName("android.location.IGnssNmeaListener")
            val onNmeaReceived = listenerCls.methods.firstOrNull { it.name == "onNmeaReceived" }
            if (onNmeaReceived == null) {
                log("[SYS-NMEA] onNmeaReceived not found")
                sysNmeaFeederStarted = false
                return@Thread
            }
            log("[SYS-NMEA] onNmeaReceived signature: ${onNmeaReceived.parameterTypes.joinToString { it.simpleName }}")

            Thread.sleep(500)
            log("[SYS-NMEA] feeder started (${sysNmeaListeners.size} listeners)")

            while (!Thread.interrupted()) {
                Thread.sleep(1000)
                if (sysNmeaListeners.isEmpty() || ourMlBinder?.mocking != true) continue

                val loc = ourMlBinder?.currentLocation ?: continue
                val timestamp = System.currentTimeMillis()
                val sentences = buildFakeNmea(loc, timestamp)

                for (l in ArrayList(sysNmeaListeners)) {
                    for (sentence in sentences) {
                        try {
                            onNmeaReceived.invoke(l, timestamp, sentence)
                        } catch (e: Throwable) {
                            val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
                            if (cause is android.os.DeadObjectException) {
                                sysNmeaListeners.remove(l)
                                log("[SYS-NMEA] removed dead listener (remaining=${sysNmeaListeners.size})")
                                break
                            }
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            log("[SYS-NMEA] feeder error: $e")
            sysNmeaFeederStarted = false
        }
    }.apply { name = "FL-SysNmeaFeed"; isDaemon = true; start() }
}

/**
 * Build fake GPGGA + GPRMC NMEA sentences matching the given location.
 * These are the two most commonly checked sentences by apps.
 */
private fun buildFakeNmea(loc: Location, timestamp: Long): List<String> {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = timestamp

    val time = String.format(
        "%02d%02d%02d.000",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        cal.get(java.util.Calendar.SECOND)
    )
    val date = String.format(
        "%02d%02d%02d",
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.YEAR) % 100
    )

    val lat = Math.abs(loc.latitude)
    val latDeg = lat.toInt()
    val latMin = (lat - latDeg) * 60
    val latStr = String.format("%02d%07.4f", latDeg, latMin)
    val latDir = if (loc.latitude >= 0) "N" else "S"

    val lon = Math.abs(loc.longitude)
    val lonDeg = lon.toInt()
    val lonMin = (lon - lonDeg) * 60
    val lonStr = String.format("%03d%07.4f", lonDeg, lonMin)
    val lonDir = if (loc.longitude >= 0) "E" else "W"

    val speed = loc.speed * 1.94384f // m/s → knots
    val bearing = if (loc.hasBearing()) loc.bearing else 0f
    val alt = if (loc.hasAltitude()) loc.altitude else 45.0

    val gpgga = "\$GPGGA,$time,$latStr,$latDir,$lonStr,$lonDir,1,12,0.8,${"%.1f".format(alt)},M,0.0,M,,"
    val gprmc = "\$GPRMC,$time,A,$latStr,$latDir,$lonStr,$lonDir,${"%.1f".format(speed)},${"%.1f".format(bearing)},$date,,,"

    fun checksum(sentence: String): String {
        var cs = 0
        for (c in sentence.substring(1)) cs = cs xor c.code
        return "$sentence*${"%02X".format(cs)}"
    }

    return listOf(checksum(gpgga), checksum(gprmc))
}
