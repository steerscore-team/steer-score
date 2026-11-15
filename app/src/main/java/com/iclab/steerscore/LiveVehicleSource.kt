package com.iclab.steerscore

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.iclab.steerscore.core.Conditions
import com.iclab.steerscore.core.DrivingSample
import com.iclab.steerscore.core.GeoPoint
import com.iclab.steerscore.core.RoadClass
import com.iclab.steerscore.core.RoadInfo
import com.iclab.steerscore.core.Situation
import com.iclab.steerscore.core.Turn

/**
 * Latest vehicle state from AAOS (speed), the Pleos Vehicle SDK (steering, turn signal, brake, low beam, gear) and the Pleos
 * FusedLocation SDK (position).
 * Callbacks arrive on binder threads, so values are volatile and read through [snapshot].
 */
class LiveVehicleSource(private val context: Context) {

    @Volatile private var speedKmh = 0f
    @Volatile private var steeringDeg = 0f
    @Volatile private var turn = Turn.OFF
    @Volatile private var brake = false
    @Volatile private var lowBeam = false
    @Volatile private var fix: GeoPoint? = null
    @Volatile private var fixAtMs = 0L
    private var fusedLocation: FusedLocation? = null

    /** Posted limit of the current road from the Pleos navigation (NaviHelper); null when unknown or 0. */
    @Volatile private var speedLimitKmh: Float? = null
    @Volatile private var speedLimitAtMs = 0L
    /** Street address from the navigation, for labelling places later. */
    @Volatile var address: String? = null
        private set
    private var navi: NaviHelper? = null
    @Volatile private var naviReady = false
    private var pendingNaviScreen: NavigationScreenRequest? = null
    /** The navigation's candidate routes per RouteOption, for the route recommendation. */
    val routes = NaviRoutes(
        navi = { navi?.takeIf { naviReady } },
        bringToFront = {
            context.startActivity(
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        stopped = { speedKmh < 3f },
    )
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(limit: Int) {
            speedLimitKmh = limit.takeIf { it > 0 }?.toFloat()
            speedLimitAtMs = SystemClock.elapsedRealtime()
        }

        override fun onRecentDestinationInfo(list: List<ai.pleos.playground.navi.data.RecentDestinationInfo>) {
            routes.onRecentDestinationInfo(list)
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            address = info.address.takeIf { it.isNotBlank() }
            routes.onCurrentLocation(info.latitude, info.longitude)
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            if (status != NavigationStatus.ROUTE_DRIVE) {
                tbt = emptyList()
                onExpressway = null
            }
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            // A guidance point we were heading for is gone from the head of the list: we just passed it
            val passed = tbt.firstOrNull()?.takeIf { prev -> list.firstOrNull()?.let { it.type != prev.type } ?: true }
            when (passed?.type?.let(::situationOf)) {
                Situation.IC_ENTRY -> onExpressway = true
                Situation.IC_EXIT -> onExpressway = false
                // Guidance just started: an IC exit ahead means we are on the expressway now, an IC entry that we are not
                else -> if (onExpressway == null && list.isNotEmpty()) {
                    val nextIc = list.map { situationOf(it.type) }.firstOrNull { it == Situation.IC_ENTRY || it == Situation.IC_EXIT }
                    onExpressway = nextIc == Situation.IC_EXIT
                }
            }
            tbt = list
            tbtAtMs = SystemClock.elapsedRealtime()
            routes.onTBTInfo(list)
        }

        override fun onRouteStateInfo(info: RouteStateInfo) = routes.onRouteStateInfo(info)
        override fun onRouteOptionChanged(routeOption: RouteOption) = routes.onRouteOptionChanged(routeOption)
        override fun onDestinationInfo(info: DestinationInfo) = routes.onDestinationInfo(info)
        override fun onRouteEnded() = routes.onRouteEnded()
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) = routes.onRouteStarted(info)
        override fun onRouteCancelled() = routes.onRouteEnded()
    }

    /** Distance to the next guidance point of the route guidance (the navigation speaks before it); null without. */
    fun nextGuidanceM(): Double? =
        tbt.firstOrNull()?.distance?.takeIf { SystemClock.elapsedRealtime() - tbtAtMs <= TBT_MAX_AGE_MS }

    /** Turn-by-turn list from the route guidance; the first item's distance is to the next guidance point. */
    @Volatile private var tbt: List<TBTInfo> = emptyList()
    @Volatile private var tbtAtMs = 0L
    /** Between an IC entry and the next IC exit on the guided route; null without route guidance. */
    @Volatile private var onExpressway: Boolean? = null

    /**
     * Road information from route guidance: the class from the IC entries and exits passed so far, and a situation
     * while the next guidance point is close. Not checked on a moving car yet (the emulator navigation does not move).
     */
    private fun roadInfo(): RoadInfo? {
        if (SystemClock.elapsedRealtime() - tbtAtMs > TBT_MAX_AGE_MS) return null
        val next = tbt.firstOrNull()?.takeIf { it.distance <= SITUATION_AHEAD_M }
        val situation = next?.type?.let(::situationOf)
        val roadClass = when {
            situation == Situation.IC_ENTRY || situation == Situation.IC_EXIT -> RoadClass.RAMP
            onExpressway == true -> RoadClass.EXPRESSWAY
            onExpressway == false -> RoadClass.GENERAL
            else -> null
        }
        return if (roadClass == null && situation == null) null else RoadInfo(roadClass, situation)
    }

    private fun situationOf(t: TurnType): Situation? = NaviRoutes.situationOf(t)
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            data ?: return
            if (fix == null) Log.i(TAG, "first FusedLocation fix ${data.latitude}, ${data.longitude}")
            fix = GeoPoint(data.latitude, data.longitude)
            fixAtMs = SystemClock.elapsedRealtime()
            failedSignals -= "location"
        }

        override fun onError(e: Exception) = fail("location", e)
    }

    /** Called on the main thread when the gear changes, to start and end trips automatically. */
    var onGearChanged: ((VehicleGear) -> Unit)? = null

    /** Signals whose subscription failed, shown in the status bar so a demo problem is visible. */
    val failedSignals: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var speedRegistered = false
    private var vehicle: Vehicle? = null

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { speedKmh = it * 3.6f }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            failedSignals += "speed"
        }
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            // The SDK reports left as positive, the opposite of PERF_STEERING_ANGLE and the Virtual Console
            // (checked 2026-11-13: console −30° arrived as +30°). The detectors use left negative.
            angle?.let { steeringDeg = SDK_STEERING_SIGN * it }
        }
        override fun onFailed(e: Exception) = fail("steering", e)
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            turn = when (state) {
                TurnSignalValue.LEFT -> Turn.LEFT
                TurnSignalValue.RIGHT -> Turn.RIGHT
                else -> Turn.OFF
            }
        }
        override fun onFailed(e: Exception) = fail("turn signal", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            brake = pressed == true
        }
        override fun onFailed(e: Exception) = fail("brake", e)
    }

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) {
            lowBeam = state == LightState.ON
        }
        override fun onFailed(e: Exception) = fail("low beam", e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            gear ?: return
            mainHandler.post { onGearChanged?.invoke(gear) }
        }
        override fun onFailed(e: Exception) = fail("gear", e)
    }

    fun start() {
        startSpeed()
        startNavi()

        val v = Vehicle(context)
        vehicle = v
        v.initialize()
        // initialize() binds to the vehicle service asynchronously, so subscribe a moment later
        mainHandler.postDelayed({
            runCatching { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }.onFailure { fail("steering", it) }
            runCatching { v.getTurnSignal().registerTurnSignalState(turnListener) }.onFailure { fail("turn signal", it) }
            runCatching { v.getBrake().registerBrakePedalStatus(brakeListener) }.onFailure { fail("brake", it) }
            runCatching { v.getLight().registerLowBeamLightState(lowBeamListener) }.onFailure { fail("low beam", it) }
            runCatching { v.getGear().registerGearSelection(gearListener) }.onFailure { fail("gear", it) }
        }, SDK_BIND_DELAY_MS)
    }

    /**
     * Vehicle speed through AAOS CarPropertyManager. CAR_SPEED is a runtime (dangerous) permission: without it the
     * property is simply invisible and registerCallback returns false, so call this again once it is granted.
     */
    fun startSpeed() {
        if (speedRegistered) return
        try {
            val c = car ?: Car.createCar(context).also { car = it }
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            speedRegistered = pm.registerCallback(
                speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST,
            )
            if (speedRegistered) failedSignals -= "speed"
            else fail("speed", IllegalStateException("PERF_VEHICLE_SPEED not accessible (CAR_SPEED permission?)"))
        } catch (e: Exception) {
            fail("speed", e)
        }
    }

    /**
     * Road information from the Pleos navigation through NaviHelper (no application needed). Works while the
     * navigation runs, with or without a destination; without it the live mode simply has no speed limit, as before.
     */
    fun startNavi() {
        if (navi != null) return
        runCatching {
            val n = NaviHelper(context)
            navi = n
            n.initialize({
                naviReady = true
                runCatching { n.addListener(naviListener); n.getCurrentLocationInfo(); n.getRouteStateInfo() }
                    .onFailure { Log.w(TAG, "navigation listener: ${it.message}") }
                pendingNaviScreen?.let { req -> pendingNaviScreen = null; runCatching { n.requestNavigationScreen(req) } }
            }, { Log.w(TAG, "navigation unavailable: ${it.message}") })
        }.onFailure { Log.w(TAG, "navigation unavailable: ${it.message}") }
    }

    /**
     * Brings up the navigation map at the current position (NaviHelper requestNavigationScreen), since the app's own
     * map has no base map. Returns false when the navigation cannot be reached.
     */
    fun openNavigationMap(): Boolean = runCatching {
        startNavi()
        val n = navi ?: return false
        if (naviReady) n.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
        else pendingNaviScreen = NavigationScreenRequest.MoveToCurrentLocationOnMap
        true
    }.getOrElse { Log.w(TAG, "navigation map: ${it.message}"); false }

    /**
     * Position for placing events on the cumulative map, from the Pleos FusedLocation SDK (about 1 Hz; the emulator
     * takes `adb emu geo fix`). Call once pleos.car.permission.FUSED_LOCATION is granted.
     */
    fun startLocation() {
        if (fusedLocation != null) return
        runCatching {
            val f = FusedLocation(context)
            f.initialize()
            fusedLocation = f
            // initialize() binds to the location service asynchronously, like the Vehicle SDK
            mainHandler.postDelayed({
                runCatching {
                    if (!f.isSupported()) fail("location", IllegalStateException("FusedLocation not supported"))
                    else f.registerFusedLocationCallback(locationListener)
                }.onFailure { fail("location", it) }
            }, SDK_BIND_DELAY_MS)
        }.onFailure { fail("location", it) }
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        fusedLocation?.let { f ->
            runCatching { f.unregisterFusedLocationCallback(locationListener) }
            f.release()
        }
        fusedLocation = null
        navi?.let { n ->
            runCatching { n.removeListener(naviListener) }
            runCatching { n.release() }
        }
        navi = null
        naviReady = false
        speedLimitKmh = null
        vehicle?.let { v ->
            runCatching {
                v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                v.getTurnSignal().unregisterTurnSignalState(turnListener)
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getLight().unregisterLowBeamLightState(lowBeamListener)
                v.getGear().unregisterGearSelection(gearListener)
            }
            v.release()
        }
        vehicle = null
        car?.disconnect()
        car = null
        speedRegistered = false
    }

    /** The speed limit comes from the navigation when it reports one; without it, speeding is not scored live. */
    fun snapshot(timeMs: Long) = DrivingSample(
        timeMs = timeMs,
        speedKmh = speedKmh,
        steeringDeg = steeringDeg,
        turnSignal = turn,
        brakePressed = brake,
        conditions = Conditions(lowBeam = lowBeam),
        // A stale fix would pin the car in place; without a fresh one the analyzer dead-reckons
        location = fix?.takeIf { SystemClock.elapsedRealtime() - fixAtMs < FIX_MAX_AGE_MS },
        speedLimitKmh = speedLimitKmh?.takeIf { SystemClock.elapsedRealtime() - speedLimitAtMs < LIMIT_MAX_AGE_MS },
        road = roadInfo(),
    )

    private fun fail(signal: String, e: Throwable) {
        Log.w(TAG, "$signal unavailable: ${e.message}")
        failedSignals += signal
    }

    companion object {
        private const val TAG = "SteerScore"
        private const val SDK_BIND_DELAY_MS = 2000L
        private const val FIX_MAX_AGE_MS = 3000L
        /** The navigation repeats the limit about every second (checked on the emulator). */
        private const val LIMIT_MAX_AGE_MS = 5000L
        private const val TBT_MAX_AGE_MS = 10_000L
        /** A guidance point this close sets the situation (merge, IC exit, ...). */
        private const val SITUATION_AHEAD_M = 300.0
        private const val SDK_STEERING_SIGN = -1f
    }
}
