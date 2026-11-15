package com.iclab.steerscore

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Activity
import android.car.Car
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.iclab.steerscore.core.AnalyzerConfig
import com.iclab.steerscore.core.DrivingEvent
import com.iclab.steerscore.core.DrivingSample
import com.iclab.steerscore.core.EnforcementMonitor
import com.iclab.steerscore.core.EnforcementZone
import com.iclab.steerscore.core.ManeuverModel
import com.iclab.steerscore.core.Maneuver
import com.iclab.steerscore.core.SampleCsv
import com.iclab.steerscore.core.ZoneKind
import com.iclab.steerscore.core.Scenario
import com.iclab.steerscore.core.Scenarios
import com.iclab.steerscore.core.SpeedRuleSets
import com.iclab.steerscore.core.TripAnalyzer
import com.iclab.steerscore.core.TripReport
import com.iclab.steerscore.core.Insights
import com.iclab.steerscore.core.RoadClassStat
import com.iclab.steerscore.core.SituationStat
import com.iclab.steerscore.core.TripLog
import com.iclab.steerscore.core.Turn
import com.iclab.steerscore.core.lateralAcceleration
import com.iclab.steerscore.ui.EventText
import com.iclab.steerscore.ui.ReportTimelineView
import com.iclab.steerscore.ui.InsightMapView
import com.iclab.steerscore.ui.TripMapView
import android.content.pm.PackageManager
import com.iclab.steerscore.ui.ScoreRingView
import com.iclab.steerscore.ui.SteeringView
import com.iclab.steerscore.ui.TraceView
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { LIVE, REPLAY }

    private val handler = Handler(Looper.getMainLooper())
    private val cfg = AnalyzerConfig()
    private lateinit var source: LiveVehicleSource
    private lateinit var history: TripHistory
    private lateinit var tripLogs: TripLogStore

    private var mode = Mode.LIVE
    private var rules = SpeedRuleSets.TMAP
    /** The current trip while [tripRunning], otherwise the last finished one (kept on screen). */
    private var analyzer: TripAnalyzer? = null
    /** Speed cameras of the replayed scenario; live drives have none. Kept with the last trip like [analyzer]. */
    private var enforcement: EnforcementMonitor? = null
    private var tripZones: List<EnforcementZone> = emptyList()
    private var tripRunning = false
    private var tripLabel = LIVE_LABEL
    private var player: ReplayPlayer? = null
    private var recorder: TripRecorder? = null
    private var scenarioIndex = 0
    private var speedFactor = 1f

    /** Learned maneuver classifier shipped in assets; it labels events, the rules still score them. */
    private val maneuverModel: ManeuverModel? by lazy {
        runCatching { assets.open(MODEL_ASSET).bufferedReader().use { ManeuverModel.parse(it.readText()) } }
            .onFailure { android.util.Log.w("SteerScore", "maneuver model unavailable: ${it.message}") }
            .getOrNull()
    }
    /** Some steering event on screen is still waiting for the classifier's verdict. */
    private var aiPending = false

    private lateinit var ruleButton: Button
    private lateinit var ruleSource: TextView
    private lateinit var scoreBaseline: ScoreRingView
    private lateinit var scoreSteer: ScoreRingView
    private lateinit var breakdown: TextView
    private lateinit var tripStats: TextView
    private lateinit var conditionsView: TextView
    private lateinit var enforcementView: TextView
    private lateinit var compareList: LinearLayout
    private lateinit var speedView: TextView
    private lateinit var speedUnit: TextView
    private lateinit var wheel: SteeringView
    private lateinit var steerDeg: TextView
    private lateinit var turnLeft: TextView
    private lateinit var turnRight: TextView
    private lateinit var brakeView: TextView
    private lateinit var trace: TraceView
    private lateinit var eventList: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeLive: Button
    private lateinit var modeReplay: Button
    private lateinit var scenarioButton: Button
    private lateinit var speedFactorButton: Button
    private lateinit var actionButton: Button
    private lateinit var reportButton: Button
    private lateinit var drivingButton: Button
    private lateinit var drivingView: View
    private lateinit var drivingStats: TextView
    private lateinit var drivingScore: ScoreRingView
    private lateinit var drivingSpeed: TextView
    private lateinit var drivingBaseline: TextView
    private lateinit var drivingEvent: TextView
    private val drivingSpeedUnit: TextView by lazy { findViewById(R.id.drivingSpeedUnit) }
    /** The full screen was asked for during this trip; live drives otherwise show the simple driving view. */
    private var showDetail = false
    private var lastEventText: String? = null
    private var lastEventColor = R.color.text
    private var lastEventAt = 0L
    private lateinit var reportOverlay: View
    private lateinit var reportSubtitle: TextView
    private lateinit var reportMap: TripMapView
    private lateinit var reportItems: LinearLayout
    private lateinit var reportFilters: LinearLayout
    /** Event list filter of the trip report. */
    private var reportFilter = ReportFilter.ALL
    /** Real-time voice coaching: the rules decide what and when, Pleos TTS speaks. */
    private lateinit var voice: VoiceCoach
    private var coach = com.iclab.steerscore.core.Coach()
    /** 알림음 (chime, then ask Gleo), 음성 (a short sentence), 끔 */
    private enum class CoachMode { TONE, VOICE, OFF }
    private var coachMode = CoachMode.TONE
    private val coachOn: Boolean get() = coachMode != CoachMode.OFF
    private lateinit var coachButton: Button
    /** Route recommendation by predicted score (RouteOption candidates from the navigation, or the demo pairs). */
    private lateinit var routePlanner: RoutePlanner
    private lateinit var liveDrive: LiveDrivePanel
    private lateinit var userPanel: UserDrivePanel
    private lateinit var dashboardRow: View
    private var idleTicks = 0
    private lateinit var reportGroups: LinearLayout
    private lateinit var reportGroupsTitle: TextView
    private lateinit var reportMissed: TextView
    private lateinit var reportScores: TextView
    private lateinit var reportTips: LinearLayout
    private lateinit var reportTabTrip: Button
    private lateinit var reportTabInsights: Button
    private lateinit var insightMapTitle: TextView
    private lateinit var insightClassesTitle: TextView
    private lateinit var insightClasses: LinearLayout
    private lateinit var insightPlan: View
    /** Report of the last finished trip, reused by the route tab. */
    private var lastReport: TripReport? = null
    private lateinit var reportTripBody: View
    private lateinit var reportInsightBody: View
    private lateinit var insightSummary: TextView
    private lateinit var insightMap: InsightMapView
    private lateinit var insightAreas: LinearLayout
    private lateinit var insightAreaInfo: TextView
    /** Which map of the cumulative analysis is shown; 0 is the latest trip's area. */
    private var insightArea = 0
    private lateinit var insightRoads: LinearLayout
    private lateinit var insightHabits: LinearLayout
    private lateinit var insightGoal: TextView
    private lateinit var insightTrend: TextView

    /** Samples the live vehicle at 10 Hz whenever live mode is shown. */
    private val liveTick = object : Runnable {
        override fun run() {
            if (mode == Mode.LIVE && !simDrive) onSample(source.snapshot(SystemClock.elapsedRealtime()))
            // Parked on the driver's screen: show where the car is (the navigation's position), once a second
            if (!tripRunning && !demoMode && idleTicks++ % 10 == 0) userPanel.idle(source.routes.here)
            handler.postDelayed(this, Scenarios.SAMPLE_INTERVAL_MS)
        }
    }

    /** Built-in scenarios, plus a recorded drive when replay.csv is present. */
    private val replayChoices: List<Scenario>
        get() {
            val choices = Scenarios.all + Scenarios.ROUTE
            val custom = TripRecorder.customReplayFile(this) ?: return choices
            val recorded = Scenario("recorded", "기록 재생", "replay.csv") {
                custom.useLines { SampleCsv.parse(it) }
            }
            return choices + recorded
        }

    private val selectedScenario: Scenario
        get() = replayChoices.let { it[scenarioIndex % it.size] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        voice = VoiceCoach(this).apply { start() }
        routePlanner = RoutePlanner(this) { source.routes }
        // 시연 모드 screen for drives on real roads: in place of the dashboard row (the first child of the main column)
        dashboardRow = ((findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup)
            .getChildAt(0) as android.view.ViewGroup).getChildAt(0)
        liveDrive = LiveDrivePanel(this, dashboardRow)
        // The driver's screen (시연 모드 off): score, map with the car's position, speed and steering graphs
        userPanel = UserDrivePanel(this, dashboardRow)
        findViewById<View>(R.id.routeButton).setOnClickListener {
            reportOverlay.visibility = View.GONE
            source.routes.refresh()
            // The navigation guides whatever the app's mode (a scenario replay does not stop it): always read it
            routePlanner.show(live = true)
        }

        history = TripHistory(this)
        tripLogs = TripLogStore(this)
        source = LiveVehicleSource(this).apply {
            onGearChanged = ::onGearChanged
            start()
        }

        // 시연 모드: scenario replay, replay speed, the detail/simple switch, demo records and the route personas.
        // Off (the default), the app shows only what a driver uses: live scoring, route recommendation, coaching, report.
        demoMode = getSharedPreferences("app", MODE_PRIVATE).getBoolean("demoMode", false)
        findViewById<View>(R.id.demoButton).setOnClickListener {
            demoMode = !demoMode
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("demoMode", demoMode).apply()
            applyDemoMode()
        }
        modeLive.setOnClickListener { switchMode(Mode.LIVE) }
        modeReplay.setOnClickListener { switchMode(Mode.REPLAY) }
        actionButton.setOnClickListener { if (tripRunning) endTrip() else startTrip() }
        // The last trip's report, or straight to the cumulative view when this session has no trip yet
        reportButton.setOnClickListener { if (analyzer != null) showReport() else showInsights() }
        reportTabTrip.setOnClickListener { showReport() }
        coachButton.setOnClickListener {
            coachMode = CoachMode.entries[(coachMode.ordinal + 1) % CoachMode.entries.size]
            if (coachMode != CoachMode.VOICE) voice.stop()
            renderCoachButton()
        }
        renderCoachButton()
        reportTabInsights.setOnClickListener { showInsights() }
        findViewById<View>(R.id.reportMapAll).setOnClickListener { selectItem(null) }
        findViewById<View>(R.id.insightSeed).setOnClickListener { seedTrips(Scenarios.OTHER_ROADS) }
        findViewById<View>(R.id.insightSeedRoute).setOnClickListener { seedTrips(Scenarios.ROUTE_TRIPS) }
        findViewById<View>(R.id.insightClear).setOnLongClickListener {
            tripLogs.clear()
            insightArea = 0
            showInsights()
            true
        }
        findViewById<View>(R.id.reportClose).setOnClickListener { reportOverlay.visibility = View.GONE }
        findViewById<View>(R.id.reportNaviMap).setOnClickListener {
            if (!source.openNavigationMap()) {
                android.widget.Toast.makeText(this, R.string.report_navi_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        scenarioButton.setOnClickListener {
            if (tripRunning) return@setOnClickListener
            scenarioIndex = (scenarioIndex + 1) % replayChoices.size
            renderControls()
        }
        speedFactorButton.setOnClickListener {
            speedFactor = when (speedFactor) { 1f -> 2f; 2f -> 4f; else -> 1f }
            player?.speedFactor = speedFactor
            speedFactorButton.text = "${speedFactor.toInt()}x"
        }
        ruleButton.setOnClickListener {
            val all = SpeedRuleSets.all
            rules = all[(all.indexOf(rules) + 1) % all.size]
            trace.ruleFilter = rules.id
            renderRule()
            renderTrip()
            renderEvents()
            renderComparison()
        }
        // Long-press clears the comparison table before recording a fresh demo
        ruleButton.setOnLongClickListener {
            history.clear()
            renderComparison()
            true
        }

        trace.ruleFilter = rules.id
        switchMode(Mode.LIVE)
        applyDemoMode()
        renderRule()
        renderTrip()
        renderEvents()
        renderComparison()
        handler.post(liveTick)
        // Speed (CAR_SPEED) and Pleos FusedLocation are runtime permissions; the Pleos vehicle permissions are install-time
        val missing = arrayOf(Car.PERMISSION_SPEED, FUSED_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (FUSED_LOCATION !in missing) source.startLocation()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) return@forEachIndexed
            when (p) {
                Car.PERMISSION_SPEED -> source.startSpeed()
                FUSED_LOCATION -> source.startLocation()
            }
        }
    }

    private fun bindViews() {
        ruleButton = findViewById(R.id.ruleButton)
        ruleSource = findViewById(R.id.ruleSource)
        scoreBaseline = findViewById(R.id.scoreBaseline)
        scoreSteer = findViewById(R.id.scoreSteer)
        breakdown = findViewById(R.id.breakdown)
        tripStats = findViewById(R.id.tripStats)
        conditionsView = findViewById(R.id.conditions)
        enforcementView = findViewById(R.id.enforcement)
        compareList = findViewById(R.id.compareList)
        speedView = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speedUnit)
        wheel = findViewById(R.id.wheel)
        steerDeg = findViewById(R.id.steerDeg)
        turnLeft = findViewById(R.id.turnLeft)
        turnRight = findViewById(R.id.turnRight)
        brakeView = findViewById(R.id.brake)
        trace = findViewById(R.id.trace)
        eventList = findViewById(R.id.eventList)
        status = findViewById(R.id.status)
        modeLive = findViewById(R.id.modeLive)
        modeReplay = findViewById(R.id.modeReplay)
        scenarioButton = findViewById(R.id.scenarioButton)
        speedFactorButton = findViewById(R.id.speedFactor)
        actionButton = findViewById(R.id.action)
        reportButton = findViewById(R.id.reportButton)
        drivingButton = findViewById(R.id.drivingButton)
        coachButton = findViewById(R.id.coachButton)
        drivingView = findViewById(R.id.drivingView)
        drivingStats = findViewById(R.id.drivingStats)
        drivingScore = findViewById(R.id.drivingScore)
        drivingSpeed = findViewById(R.id.drivingSpeed)
        drivingBaseline = findViewById(R.id.drivingBaseline)
        drivingEvent = findViewById(R.id.drivingEvent)
        reportOverlay = findViewById(R.id.reportOverlay)
        reportSubtitle = findViewById(R.id.reportSubtitle)
        reportMap = findViewById(R.id.reportMap)
        reportItems = findViewById(R.id.reportItems)
        reportFilters = findViewById(R.id.reportFilters)
        reportGroups = findViewById(R.id.reportGroups)
        reportGroupsTitle = findViewById(R.id.reportGroupsTitle)
        reportMissed = findViewById(R.id.reportMissed)
        reportScores = findViewById(R.id.reportScores)
        reportTips = findViewById(R.id.reportTips)
        reportTabTrip = findViewById(R.id.reportTabTrip)
        reportTabInsights = findViewById(R.id.reportTabInsights)
        insightMapTitle = findViewById(R.id.insightMapTitle)
        insightClassesTitle = findViewById(R.id.insightClassesTitle)
        insightClasses = findViewById(R.id.insightClasses)
        insightPlan = findViewById(R.id.insightPlan)
        reportTripBody = findViewById(R.id.reportTripBody)
        reportInsightBody = findViewById(R.id.reportInsightBody)
        insightSummary = findViewById(R.id.insightSummary)
        insightMap = findViewById(R.id.insightMap)
        insightAreas = findViewById(R.id.insightAreas)
        insightAreaInfo = findViewById(R.id.insightAreaInfo)
        insightRoads = findViewById(R.id.insightRoads)
        insightHabits = findViewById(R.id.insightHabits)
        insightGoal = findViewById(R.id.insightGoal)
        insightTrend = findViewById(R.id.insightTrend)
    }

    private var demoMode = false

    /** A live drive fed recorded samples (시연 모드 on the emulator, where the car cannot move). */
    private var simDrive = false
    private var lastChimeAt = 0L

    /**
     * 시연 모드 drive: the route the navigation is guiding, when it is one of the bundled ones (a drive generated along
     * that route's line offline), so the car on the map follows the navigation's route; otherwise
     * scenario ⑦. Asks the navigation first (turn list → which of its routes), at most 4 s.
     */
    private fun startSimDrive() {
        var started = false
        fun go(samples: List<DrivingSample>, label: String, data: String) {
            if (started || !tripRunning || !simDrive || samples.isEmpty()) return
            started = true
            // For an optional demo helper on the PC: it plays the same drive into the Virtual Console so the emulator's own screens
            // (cluster, gear, speed) drive along
            android.util.Log.i("SteerScore", "sim drive start $data")
            tripLabel = label
            analyzer = TripAnalyzer(cfg, model = maneuverModel, start = Scenarios.ROUTE.start)
            // The simulated drive shows what the driver would see: the driver's screen (one score, the map, speed and steering)
            userPanel.start()
            userPanel.show(true)
            dashboardRow.visibility = View.GONE
            player = ReplayPlayer(samples, handler, ::onSample) { endTrip(completed = true) }.also {
                it.speedFactor = 1f
                it.start()
            }
            renderControls()
        }
        val fallback = { go(Scenarios.ROUTE.build(), SIM_LABEL, "route_1") }
        handler.postDelayed(fallback, 4_000)
        source.routes.current { g, _ ->
            runOnUiThread {
                val demo = g?.let { com.iclab.steerscore.core.RouteMaps.liveDemoFor(it.to) }
                val index = demo?.let { d ->
                    source.routes.chosenAlt?.takeIf { it in d.od.routes.indices }
                        ?: d.guidedIndex(g.steps.map { it.description }.filter { it != "경유" })
                }
                val res = if (demo != null && index != null) javaClass.getResourceAsStream("/scenarios/live/${demo.od.id}_$index.csv.gz") else null
                if (demo == null || index == null || res == null) return@runOnUiThread
                val samples = java.util.zip.GZIPInputStream(res).bufferedReader(Charsets.UTF_8).useLines {
                    com.iclab.steerscore.core.SampleCsv.parse(it)
                }
                handler.removeCallbacks(fallback)
                val label = com.iclab.steerscore.core.RouteAdvisor.optionLabel(demo.od.routes[index].options)
                go(samples, "가상 주행 · ${demo.od.to} ${"①②③④⑤".getOrElse(index) { '·' }} $label", "${demo.od.id}_$index")
            }
        }
    }

    /** Shows or hides everything that exists only for the demonstration (see the 시연 모드 button). */
    private fun applyDemoMode() {
        if (!demoMode && mode == Mode.REPLAY) switchMode(Mode.LIVE)
        // A demo drive cannot go on on the driver's screen (it has no demo drive), so it ends with 시연 모드
        if (!demoMode && simDrive && tripRunning) endTrip()
        // The driver's screen replaces the dashboard; 시연 모드 shows the dashboard (or the demo drive map)
        showDetail = true
        userPanel.show(!demoMode)
        dashboardRow.visibility = if (!demoMode || liveDrive.active) View.GONE else View.VISIBLE
        val demo = if (demoMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.demoButton).isSelected = demoMode
        modeLive.visibility = demo
        modeReplay.visibility = demo
        drivingButton.visibility = View.GONE
        findViewById<View>(R.id.drivingDetail).visibility = View.GONE
        findViewById<View>(R.id.insightSeedRow).visibility = demo
        routePlanner.demoMode = demoMode
        renderDrivingView()
        renderControls()
    }

    private fun switchMode(newMode: Mode) {
        if (tripRunning) endTrip()
        if (newMode != mode) {
            // Cameras belong to the replayed route, so they should not linger over a live drive
            tripZones = emptyList()
            enforcement = null
            trace.zones = emptyList()
            renderEnforcement()
        }
        mode = newMode
        // Live mode must keep sampling and coaching while the navigation is in front
        if (mode == Mode.LIVE) DriveKeepAliveService.start(this) else DriveKeepAliveService.stop(this)
        modeLive.isSelected = mode == Mode.LIVE
        modeReplay.isSelected = mode == Mode.REPLAY
        val replayOnly = if (mode == Mode.REPLAY) View.VISIBLE else View.GONE
        speedFactorButton.visibility = replayOnly
        scenarioButton.visibility = replayOnly
        trace.clear()
        renderControls()
    }

    private fun onGearChanged(gear: VehicleGear) {
        // A simulated drive is fed its own samples: the car's real gear (the Virtual Console mirroring it for a demo
        // recording) neither starts nor ends it
        if (mode != Mode.LIVE || simDrive) return
        val driving = gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_NEUTRAL &&
            gear != VehicleGear.GEAR_REVERSE && gear != VehicleGear.GEAR_UNKNOWN
        if (driving && !tripRunning) startTrip()
        if (gear == VehicleGear.GEAR_PARK && tripRunning) endTrip()
    }

    private fun startTrip() {
        val start = if (mode == Mode.REPLAY) selectedScenario.start else Scenarios.DEFAULT_START
        analyzer = TripAnalyzer(cfg, model = maneuverModel, start = start)
        tripVirtual = onVirtualRoad()
        DriveState.startTrip(System.currentTimeMillis(), rules.label)
        tripRunning = true
        reportOverlay.visibility = View.GONE
        // The driver's screen (or the demo dashboard) stays during the drive; the glanceable overlay is not used
        showDetail = true
        if (!demoMode) userPanel.start()
        lastEventText = null
        trace.clear()
        coach = com.iclab.steerscore.core.Coach()
        renderEvents()

        tripZones = if (mode == Mode.REPLAY) selectedScenario.zones else emptyList()
        enforcement = EnforcementMonitor(tripZones)
        trace.zones = tripZones

        when (mode) {
            Mode.LIVE -> if (demoMode) {
                // 시연 모드: the emulator's car does not move, so the live drive is fed the recorded real-road trip ⑦
                // (판교IC → 경부고속도로 → 양재IC) in real time. Everything else is the live path: the navigation in
                // front, the keep-alive service, alerts, Gleo, and the report with the real road map.
                simDrive = true
                tripLabel = SIM_LABEL
                startSimDrive()
            } else {
                tripLabel = LIVE_LABEL
                recorder = TripRecorder(this).apply { start() }
            }
            Mode.REPLAY -> {
                val scenario = selectedScenario
                tripLabel = scenario.title
                val samples = scenario.build()
                // A real-road scenario (⑦) in 시연 모드 gets the moving map too
                if (demoMode && scenario.id.startsWith("route") && samples.any { it.location != null })
                    liveDrive.start(samples.mapNotNull { it.location }, samples.last().timeMs - samples.first().timeMs, samples.first().timeMs)
                player = ReplayPlayer(samples, handler, ::onSample) { endTrip(completed = true) }.also {
                    it.speedFactor = speedFactor
                    it.start()
                }
            }
        }
        renderControls()
    }

    /**
     * [completed] is true when a replay reached its end. A replay stopped halfway is not saved, since a
     * partial run would sit in the comparison table next to full runs of the same route.
     */
    private fun endTrip(completed: Boolean = false) {
        if (!tripRunning) return
        tripRunning = false
        DriveState.endTrip()
        // A simulated live drive is kept however it ends, like a real one
        liveDrive.stop()
        userPanel.stop(analyzer?.steerScore(rules.id), analyzer?.baselineScore(rules.id))
        // 시연 모드: back to the dashboard after a simulated drive
        if (demoMode) {
            userPanel.show(false)
            dashboardRow.visibility = View.VISIBLE
        }
        val wasReplay = player != null && !simDrive
        if (simDrive) android.util.Log.i("SteerScore", "sim drive end")
        simDrive = false
        player?.stop()
        player = null
        recorder?.stop()
        recorder = null
        val keep = if (wasReplay) completed else true
        analyzer?.let { a ->
            if (keep && a.durationMs >= MIN_SAVED_TRIP_MS) {
                history.add(a, tripLabel)
                tripLogs.add(TripLog.of(a, tripLabel, System.currentTimeMillis(), cfg, virtualRoad = tripVirtual))
                insightArea = 0
            }
        }
        // Let the classifier label the last seconds too, so every event gets its verdict
        analyzer?.maneuvers?.flush()
        renderEvents()
        renderComparison()
        renderControls()
        renderTrip()
        renderDrivingView()
        // The report is for after the drive, when the car is parked or the replay has finished
        if (keep && (analyzer?.durationMs ?: 0L) >= MIN_SAVED_TRIP_MS) showReport()
    }

    /**
     * After-trip report under the selected baseline: (1) the trip map with every deduction, (2) the events in time
     * order (tap one to zoom the map to it), (3) deductions by road type (or by situation without navigation),
     * with the steering points the baseline missed and what to fix next.
     */
    private fun showReport() {
        val a = analyzer ?: return
        if (tripRunning) return
        val r = TripReport.of(a, rules.id, cfg)
        lastReport = r
        selectTab(TAB_TRIP)

        reportSubtitle.text = "$tripLabel  ·  %.1f km · %s".format(Locale.US, r.distanceKm, EventText.clock(r.durationMs)) +
            if (demoMode) "  ·  차선변경 ${r.laneChangeCount}회  ·  비교 기준 ${rules.label}" else ""
        // This drive's score, large, above its list of deductions
        val deductionCount = r.items.count { it.event.penalty > 0f }
        tripScoreHeader = scoreHeader(reportFilters.parent as ViewGroup, tripScoreHeader, r.steerScore, "이번 주행 점수",
            (if (deductionCount == 0) "감점 없는 주행" else if (demoMode) "100점에서 ${pointsText(100f - r.steerScore)}점 · 감점 ${deductionCount}번" else "감점 ${deductionCount}번") +
                if (demoMode) " · 기존 방식 ${r.baselineScore}점" else "")
        // A path recorded from real location fixes (a live drive with GPS, or scenario ⑦) lies on real roads: draw the
        // OpenStreetMap roads underneath. Virtual scenario roads (①–⑥) get no background, it would not match them.
        val roads = if (a.onRealRoads && !tripVirtual) com.iclab.steerscore.core.RouteMaps.roadsAround(r.track.map { it.point }) else null
        val credit = if (roads != null || selectedScenarioIsRoute()) "지도 데이터 © OpenStreetMap contributors" else null
        reportMap.showPoints = demoMode
        reportMap.show(r.track, r.items, credit, roads)
        reportMap.onSelect = { selectItem(it) }

        // (2) Events in time order, with a filter row
        reportFilters.removeAllViews()
        ReportFilter.entries.forEach { f ->
            val count = r.items.count { f.accepts(it.event) }
            reportFilters.addView(Button(this, null, 0, R.style.SmallButton).apply {
                text = "${f.label} $count"
                textSize = 15f
                isSelected = f == reportFilter
                setTextColor(getColor(if (f == reportFilter) R.color.bg else R.color.text))
                setBackgroundColor(getColor(if (f == reportFilter) R.color.accent else R.color.panel_line))
                setPadding(14, 6, 14, 6)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
                setOnClickListener {
                    reportFilter = f
                    showReport()
                }
            })
        }
        reportItems.removeAllViews()
        if (r.items.isEmpty()) reportItems.addView(mutedText(getString(R.string.report_clean)))
        var number = 0
        r.items.forEachIndexed { i, item ->
            val e = item.event
            val deduction = e.penalty > 0f
            if (deduction) number++
            if (!reportFilter.accepts(e)) return@forEachIndexed
            reportItems.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 12, 8, 12)
                tag = i
                isClickable = true
                setOnClickListener { selectItem(i) }
                addView(TextView(context).apply {
                    text = if (deduction) "$number" else "·"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.bg))
                    // Same colour as the row's points and the map marker: by the size of the deduction
                    setBackgroundColor(getColor(EventText.colorRes(e)))
                    layoutParams = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 14 }
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = EventText.title(e)
                        textSize = 19f
                        if (deduction) setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = "${EventText.clock(item.offsetMs)} · ${item.place} · ${EventText.tag(e)}"
                        textSize = 15f
                        setTextColor(getColor(R.color.text_muted))
                    })
                })
                addView(TextView(context).apply {
                    text = if (!demoMode) (if (deduction) "감점" else "정상") else if (deduction) pointsText(e.penalty) else "0"
                    textSize = if (demoMode) 21f else 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(EventText.colorRes(e)))
                })
            })
        }

        // (3) Deductions by road type (situation without navigation), with their causes
        reportGroupsTitle.text = getString(if (r.hasRoadInfo) R.string.report_groups else R.string.report_groups_situation)
        reportGroups.removeAllViews()
        if (r.groups.isEmpty()) reportGroups.addView(mutedText(getString(R.string.report_clean)))
        val totalPenalty = r.groups.sumOf { it.penalty.toDouble() }.toFloat()
        val sections = if (r.hasRoadInfo && r.groups.isNotEmpty()) listOf(null to r.groups, "상황별" to r.situationGroups)
        else listOf(null to r.groups)
        sections.forEach { (title, gs) ->
        title?.let {
            reportGroups.addView(TextView(this).apply {
                text = it
                textSize = 17f
                setPadding(0, 14, 0, 2)
                setTextColor(getColor(R.color.text_muted))
            })
        }
        gs.forEach { g ->
            reportGroups.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 10)
                addView(roadRow(g.label, if (totalPenalty > 0f) g.penalty / totalPenalty else 0f,
                    if (demoMode) "%d회 · %s".format(Locale.US, g.count, pointsText(g.penalty)) else "${g.count}회"))
                addView(TextView(context).apply {
                    text = g.causes.joinToString("   ·   ") { c -> "${c.cause.label} ${c.count}회" + if (demoMode) " ${pointsText(c.penalty)}" else "" }
                    textSize = 16f
                    setPadding(150, 0, 0, 0)
                    setTextColor(getColor(R.color.text_muted))
                })
            })
        }
        }

        // What the speed-only baseline missed is the demo's point, not the driver's business
        val missedTitle = (reportMissed.parent as ViewGroup).let { it.getChildAt(it.indexOfChild(reportMissed) - 1) }
        for (v in listOf(missedTitle, reportMissed, reportScores)) v.visibility = if (demoMode) View.VISIBLE else View.GONE
        if (r.steeringPenalty == 0f) {
            reportMissed.text = "없음"
            reportMissed.setTextColor(getColor(R.color.good))
        } else {
            reportMissed.text = "조향 ${pointsText(r.steeringPenalty)}점"
            reportMissed.setTextColor(getColor(R.color.accent))
        }
        reportScores.text = buildString {
            append("기존 방식 ${r.baselineScore}점  ·  SteerScore ${r.steerScore}점")
            r.weaving.takeIf { it.isNotEmpty() }?.let { w ->
                append("\n칼치기(연속 급차선변경) ${w.size}번 · ${pointsText(w.sumOf { it.penalty.toDouble() }.toFloat())}점")
            }
        }

        reportTips.removeAllViews()
        if (r.tips.isEmpty()) reportTips.addView(mutedText(getString(R.string.report_clean)))
        r.tips.take(2).forEachIndexed { i, tip ->
            reportTips.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        text = "${i + 1}. ${tip.habit.goal}"
                        textSize = 19f
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(context).apply {
                        text = "+%.0f점".format(Locale.US, tip.gain)
                        textSize = 19f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.good))
                    })
                })
                addView(TextView(context).apply {
                    text = "이번 주행: ${tip.detail}"
                    textSize = 15f
                    setTextColor(getColor(R.color.text_muted))
                })
            })
        }
        reportOverlay.visibility = View.VISIBLE
    }

    /** Zooms the report map to event [index] (null: whole trip) and marks its row in the list. */
    private fun selectItem(index: Int?) {
        reportMap.select(index)
        for (k in 0 until reportItems.childCount) {
            val row = reportItems.getChildAt(k)
            row.setBackgroundColor(if (row.tag == index) 0x332F80ED else 0x00000000)
        }
    }

    private fun selectedScenarioIsRoute() = mode == Mode.REPLAY && selectedScenario.id.startsWith("route")

    /** Scenarios ①–⑥ drive a made-up road: their coordinates come as fixes but do not lie on the real road map. */
    private fun onVirtualRoad() = mode == Mode.REPLAY && !selectedScenario.id.startsWith("route")

    /** The trip being driven or last driven is on a virtual scenario road (set when it starts: the mode may change after). */
    private var tripVirtual = false

    private fun fillRoadSections(situations: List<SituationStat>, classes: List<RoadClassStat>, total: Float) {
        insightRoads.removeAllViews()
        if (total == 0f) insightRoads.addView(mutedText(getString(R.string.report_clean)))
        else situations.forEach { insightRoads.addView(situationRow(it.type.label, it.share, it.count, it.penalty)) }
        insightClasses.removeAllViews()
        val showClasses = classes.isNotEmpty() && total > 0f
        insightClassesTitle.visibility = if (showClasses) View.VISIBLE else View.GONE
        insightClasses.visibility = if (showClasses) View.VISIBLE else View.GONE
        if (showClasses) classes.forEach { insightClasses.addView(situationRow(it.type.label, it.share, it.count, it.penalty)) }
    }

    /** "합류 구간  ████░░░  38% · 2회 · -7.1" */
    private fun situationRow(name: String, share: Float, count: Int, penalty: Float) =
        roadRow(name, share, when {
            count == 0 -> "0%"
            demoMode -> "%.0f%% · %d회 · %s".format(Locale.US, share * 100, count, pointsText(penalty))
            else -> "%.0f%% · %d회".format(Locale.US, share * 100, count)
        })

    /** "-37" for whole points, "-11.2" otherwise (weather multipliers). */
    private fun pointsText(p: Float): String =
        if (p % 1f == 0f) "-%.0f".format(Locale.US, p) else "-%.1f".format(Locale.US, p)

    private fun selectTab(tab: Int) {
        reportTripBody.visibility = if (tab == TAB_TRIP) View.VISIBLE else View.GONE
        reportInsightBody.visibility = if (tab == TAB_TRIP) View.GONE else View.VISIBLE
        reportTabTrip.isSelected = tab == TAB_TRIP
        reportTabInsights.isSelected = tab == TAB_INSIGHTS
        reportTabTrip.isEnabled = analyzer != null && !tripRunning
        reportOverlay.visibility = View.VISIBLE
    }

    /** Cumulative analysis over every logged trip: hotspot map, road types, improvement plan, weekly goal. */
    private fun showInsights() {
        if (tripRunning) return
        selectTab(TAB_INSIGHTS)
        val logs = tripLogs.load()
        val ins = Insights.of(logs, rules.id)
        insightMapTitle.text = getString(R.string.insight_map)
        insightPlan.visibility = View.VISIBLE
        if (insightArea >= ins.areas.size) insightArea = 0
        showInsightArea(ins)
        insightRoads.removeAllViews()
        insightClasses.removeAllViews()
        insightHabits.removeAllViews()
        if (logs.isEmpty()) {
            insightSummary.text = getString(R.string.insight_empty)
            insightGoal.text = ""
            insightTrend.text = ""
            return
        }
        insightSummary.text = if (demoMode) "주행 %d회 · %.1f km · 평균 기존 %.0f점 / SteerScore %.0f점 · 비교 기준 %s".format(
            Locale.US, ins.tripCount, ins.distanceKm, ins.avgBaseline, ins.avgSteer, rules.label,
        ) else "주행 %d회 · %.1f km".format(Locale.US, ins.tripCount, ins.distanceKm)
        // My driving score: the average of the latest 10 drives (each drive starts at 100 and loses only its own deductions)
        val recentScores = ins.trend.takeLast(10)
        val myScore = recentScores.average().toFloat().let { if (it.isNaN()) 100f else it }
        val change = ins.recentChange()?.let { (before, after) -> after - before }
        val changeText = when {
            change == null -> ""
            change >= 1f -> " · 최근 3회 %.0f점 올랐어요".format(Locale.US, change)
            change <= -1f -> " · 최근 3회 %.0f점 내려갔어요".format(Locale.US, -change)
            else -> " · 최근 3회 그대로예요"
        }
        myScoreHeader = scoreHeader(insightClassesTitle.parent as ViewGroup, myScoreHeader, kotlin.math.round(myScore).toInt(),
            "내 운전 점수", "최근 ${recentScores.size}회 주행 평균$changeText")

        fillRoadSections(ins.situations, ins.roadClasses, ins.totalPenalty)

        if (ins.habits.isEmpty()) insightHabits.addView(mutedText(getString(R.string.report_clean)))
        // Missions: the habits that cost the most, each with the points it brings back per drive and how the latest
        // drives went (● kept: no deduction of that kind in that drive)
        val lastDrives = logs.takeLast(5)
        ins.habits.take(3).forEachIndexed { i, h ->
            val kept = lastDrives.map { t -> t.eventsFor(rules.id).none { (it.habits[h.habit] ?: 0f) > 0f } }
            insightHabits.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = "미션 ${i + 1}"
                        textSize = 17f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.accent))
                        setPadding(0, 0, 16, 0)
                    })
                    addView(TextView(context).apply {
                        text = h.habit.goal
                        textSize = 22f
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(context).apply {
                        text = if (h.perTrip >= 1f) "+%.0f점".format(Locale.US, h.perTrip) else "+%.1f점".format(Locale.US, h.perTrip)
                        textSize = 28f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.good))
                    })
                })
                addView(TextView(context).apply {
                    // A progress bar: one filled dot per kept drive, from the left
                    val n = kept.count { it }
                    val dots = (0 until kept.size).joinToString(" ") { if (it < n) "●" else "○" }
                    text = "$dots   최근 ${kept.size}회 중 ${n}번 성공 · 다음 주행에서 지키면 점수가 올라가요"
                    textSize = 18f
                    setTextColor(getColor(R.color.text_muted))
                })
            })
        }

        val goal = ins.goal
        insightGoal.text = if (goal == null) getString(R.string.report_clean) else
            "${goal.habit.goal}\n이번 주 지키면 내 운전 점수 %.0f → %.0f점".format(
                Locale.US, myScore, (myScore + goal.perTrip).coerceAtMost(100f),
            )
        insightTrend.visibility = if (demoMode) View.VISIBLE else View.GONE
        insightTrend.text = ins.recentChange()?.let { (before, after) ->
            "점수 추이: 이전 평균 %.0f점 → 최근 3회 평균 %.0f점 (%+.0f)".format(Locale.US, before, after, after - before)
        } ?: "점수 추이는 주행이 4회 이상 쌓이면 보여드려요"
    }

    /**
     * Trips in different places get separate maps (routes that never come within 300 m of each other): one button per
     * map above it, the latest trip's map first. The panels on the right stay over all trips.
     */
    private fun showInsightArea(ins: Insights) {
        val areas = ins.areas
        insightMap.area = areas.getOrNull(insightArea)
        insightAreas.removeAllViews()
        insightAreas.visibility = if (areas.size > 1) View.VISIBLE else View.GONE
        insightAreaInfo.visibility = if (areas.size > 1) View.VISIBLE else View.GONE
        if (areas.size <= 1) return
        areas.take(MAX_AREA_BUTTONS).forEachIndexed { i, a ->
            insightAreas.addView(Button(this, null, 0, R.style.SmallButton).apply {
                text = "경로 ${i + 1} · ${a.trips}회"
                textSize = 18f
                minHeight = 64
                setTextColor(getColor(if (i == insightArea) R.color.bg else R.color.text))
                setBackgroundColor(getColor(if (i == insightArea) R.color.accent else R.color.panel_line))
                setPadding(28, 10, 28, 10)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = 10 }
                setOnClickListener {
                    insightArea = i
                    showInsightArea(ins)
                }
            })
        }
        val a = areas[insightArea]
        val names = a.labels.distinct()
        insightAreaInfo.text = "이 지도: 주행 %d회 · %.1f km · %s".format(
            Locale.US, a.trips, a.distanceKm,
            names.first() + if (names.size > 1) " 외 ${names.size - 1}개" else "",
        )
    }

    /** "커브  ███████░░░  38% · 9회" */
    private fun roadRow(name: String, share: Float, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 6, 0, 6)
        addView(TextView(context).apply {
            text = name
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(0, 30, 1f)
            addView(View(context).apply {
                setBackgroundColor(getColor(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, share.coerceIn(0.005f, 1f))
            })
        })
        addView(TextView(context).apply {
            text = value
            textSize = 17f
            gravity = Gravity.END
            setTextColor(getColor(R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(170, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    /** Demo helper: runs every scenario through the analyzer at once and logs it, like replaying each to the end. */
    private fun seedTrips(scenarios: List<Scenario>) {
        val now = System.currentTimeMillis()
        status.text = "주행 기록을 만드는 중…"
        // Long drives take seconds to analyze: off the main thread
        Thread {
            val logs = scenarios.mapIndexed { i, sc ->
                val a = TripAnalyzer(cfg, start = sc.start)
                sc.build().forEach { a.onSample(it) }
                TripLog.of(a, sc.title, now + i, cfg, virtualRoad = !(sc.id.startsWith("route") || sc.id.startsWith("live")))
            }
            runOnUiThread {
                tripLogs.addAll(logs)
                insightArea = 0
                renderControls()
                showInsights()
            }
        }.start()
    }

    private var tripScoreHeader: View? = null
    private var myScoreHeader: View? = null

    /** A score, large, with its name and one line under it, at the top of [panel] (replacing the previous one). */
    private fun scoreHeader(panel: ViewGroup, old: View?, score: Int, name: String, detail: String): View {
        old?.let { panel.removeView(it) }
        val color = getColor(when { score >= 90 -> R.color.good; score >= 75 -> R.color.accent; score >= 60 -> R.color.warn; else -> R.color.bad })
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
            addView(TextView(context).apply {
                text = score.toString()
                textSize = 64f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color)
                setPadding(0, 0, 24, 0)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = name; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(context).apply { text = detail; textSize = 18f; setTextColor(getColor(R.color.text_muted)) })
            })
        }
        panel.addView(header, 0)
        return header
    }

    private fun mutedText(s: String) = TextView(this).apply {
        text = s
        textSize = 19f
        setTextColor(getColor(R.color.text_muted))
    }

    private fun onSample(s: DrivingSample) {
        val a = analyzer
        val lateral: Float
        if (tripRunning && a != null) {
            val detected = a.onSample(s)
            enforcement?.onSample(s)
            recorder?.write(s)
            lateral = a.lateralAcc
            detected.forEach { e -> trace.mark(s.timeMs, EventText.shortLabel(e), EventText.colorRes(e), e.ruleId) }
            // The driving view shows the latest steering event or deduction of the selected baseline
            detected.lastOrNull { it.isSteering || (it.ruleId == rules.id && it.penalty > 0f) }?.let { e ->
                lastEventText = EventText.title(e) + if (e.penalty > 0f) "  " + pointsText(e.penalty) else ""
                lastEventColor = EventText.colorRes(e)
                lastEventAt = SystemClock.uptimeMillis()
            }
            // Voice coaching: each new deduction goes to the coach, which says one short line when the moment is right
            val deductions = detected.filter { it.penalty > 0f && (it.isSteering || it.ruleId == rules.id) }
            // Alert tone: right at the deduction (a short tone needs no quiet moment), at most one per 1.5 s
            if (coachMode == CoachMode.TONE && deductions.isNotEmpty() && SystemClock.uptimeMillis() - lastChimeAt > 1_500) {
                lastChimeAt = SystemClock.uptimeMillis()
                voice.chime()
            }
            liveDrive.onSample(a, s, deductions, rules.id)
            userPanel.onSample(a, s, deductions, rules.id)
            deductions.forEach {
                val road = a.roadAt(it.timeMs)
                val situation = com.iclab.steerscore.core.Situation.of(it, road)
                coach.onEvent(it, situation, road?.roadClass)
                // What Gleo AI tells the driver when asked "방금 왜 감점됐어?"
                val cause = com.iclab.steerscore.core.Cause.of(it)
                if (cause != null) DriveState.add(DriveState.Deduction(System.currentTimeMillis(), cause.label, situation.label,
                    road?.roadClass?.label, it.penalty, com.iclab.steerscore.core.Coach.phrase(cause, situation, road?.roadClass)))
            }
            DriveState.baseline = a.baselineScore(rules.id)
            DriveState.steer = a.steerScore(rules.id)
            if (coachOn) {
                val ctx = com.iclab.steerscore.core.Coach.Context(
                    timeMs = s.timeMs,
                    situation = s.road?.situation,
                    nextGuidanceM = if (mode == Mode.LIVE) source.nextGuidanceM() else null,
                    steeringDeg = s.steeringDeg,
                    speaking = voice.speaking,
                )
                // Voice mode speaks the coach's line when the moment is right; tone mode already chimed above
                coach.poll(ctx)?.let { if (coachMode == CoachMode.VOICE) voice.say(it.text) }
            }
            // The classifier labels a moment 4 s later, so refresh pending verdicts about once a second
            val refreshAi = aiPending && a.maneuvers.size % 10 == 0
            if (refreshAi || detected.any { it.isSteering || it.ruleId == rules.id }) renderEvents()
        } else {
            lateral = lateralAcceleration(s.speedKmh, s.steeringDeg, cfg)
        }
        trace.add(s.timeMs, lateral, s.speedKmh)
        renderGauges(s)
        renderTrip()
        renderEnforcement()
        renderControls()
        renderDrivingView(s)
    }

    private fun renderCoachButton() {
        coachButton.text = getString(when (coachMode) {
            CoachMode.TONE -> R.string.coach_tone
            CoachMode.VOICE -> R.string.coach_on
            CoachMode.OFF -> R.string.coach_off
        })
        coachButton.isSelected = coachOn
    }

    /** Glanceable view while driving: SteerScore, speed, baseline and the latest event for a few seconds. */
    private fun renderDrivingView(s: DrivingSample? = null) {
        val show = tripRunning && !showDetail
        drivingView.visibility = if (show) View.VISIBLE else View.GONE
        drivingButton.isEnabled = tripRunning && showDetail
        val a = analyzer
        if (!show || a == null) return
        drivingScore.score = a.steerScore(rules.id)
        drivingScore.caption = "주행 중"
        drivingBaseline.text = "기존 방식 ${a.baselineScore(rules.id)}점"
        drivingStats.text = "$tripLabel · %.1f km · %s".format(Locale.US, a.distanceKm, EventText.clock(a.durationMs))
        s?.let {
            drivingSpeed.text = "%.0f".format(Locale.US, it.speedKmh)
            val limit = it.speedLimitKmh
            drivingSpeedUnit.text = if (limit == null) "km/h" else "km/h · 제한 %.0f".format(Locale.US, limit)
        }
        val recent = lastEventText?.takeIf { SystemClock.uptimeMillis() - lastEventAt < EVENT_SHOW_MS }
        drivingEvent.text = recent ?: getString(R.string.driving_safe)
        drivingEvent.setTextColor(getColor(if (recent != null) lastEventColor else R.color.text_muted))
    }

    /** Camera speed and section average for the replayed route, or hidden when the drive has no zones. */
    private fun renderEnforcement() {
        val m = enforcement
        if (m == null || tripZones.isEmpty()) {
            enforcementView.visibility = View.GONE
            return
        }
        enforcementView.visibility = View.VISIBLE
        val parts = tripZones.map { z ->
            val name = if (z.kind == ZoneKind.CAMERA) "카메라" else "구간 평균"
            val r = m.results.firstOrNull { it.zone == z }
            when {
                r != null -> "$name %.0f %s".format(Locale.US, r.speedKmh, if (r.violated) "✗" else "✓")
                tripRunning && m.current == z -> if (z.kind == ZoneKind.CAMERA) "카메라 접근 중" else "구간단속 중"
                else -> "$name -"
            }
        }
        enforcementView.text = "과속단속(km/h): " + parts.joinToString("  ·  ")
        enforcementView.setTextColor(
            getColor(if (m.results.any { it.violated }) R.color.bad else R.color.text_muted)
        )
    }

    private fun renderRule() {
        ruleButton.text = "비교 기준: ${rules.label}  ▸"
        ruleSource.text = "출처: ${rules.source}"
    }

    private fun renderGauges(s: DrivingSample) {
        speedView.text = "%.0f".format(Locale.US, s.speedKmh)
        val limit = s.speedLimitKmh
        speedUnit.text = if (limit == null) "km/h" else "km/h · 제한 %.0f".format(Locale.US, limit)
        speedView.setTextColor(
            getColor(
                when {
                    limit == null || s.speedKmh <= limit -> R.color.text
                    s.speedKmh <= limit + SpeedRuleSets.TMAP.speedingMarginKmh -> R.color.warn
                    else -> R.color.bad
                }
            )
        )
        wheel.angleDeg = s.steeringDeg
        steerDeg.text = "%+d°".format(Locale.US, s.steeringDeg.roundToInt())

        // Blink the active turn signal like a cluster indicator
        val blinkOn = (SystemClock.uptimeMillis() / 400) % 2 == 0L
        val on = getColor(R.color.signal)
        val off = getColor(R.color.panel_line)
        turnLeft.setTextColor(if (s.turnSignal == Turn.LEFT && blinkOn) on else off)
        turnRight.setTextColor(if (s.turnSignal == Turn.RIGHT && blinkOn) on else off)
        brakeView.setTextColor(getColor(if (s.brakePressed) R.color.bad else R.color.panel_line))

        conditionsView.text = "주행 조건: ${EventText.conditionsLabel(s.conditions)}" +
            if (s.conditions.isNormal) "" else " (조향 감점 ×%.2f)".format(Locale.US, s.conditions.riskMultiplier())
    }

    private fun renderTrip() {
        val a = analyzer
        val caption = when {
            tripRunning -> "주행 중"
            a != null -> "지난 주행"
            else -> "대기 중"
        }
        scoreBaseline.score = a?.baselineScore(rules.id) ?: 100
        scoreSteer.score = a?.steerScore(rules.id) ?: 100
        scoreBaseline.caption = caption
        scoreSteer.caption = caption
        breakdown.text = if (a == null) "" else
            "속도 감점 ${penaltyText(a.speedPenalty(rules.id))}  ·  조향 감점 ${penaltyText(a.steeringPenalty)}"
        tripStats.text = if (a == null) "" else
            "$tripLabel\n%.1f km · %s · 차선변경 %d회".format(
                Locale.US, a.distanceKm, EventText.clock(a.durationMs), a.laneChangeCount,
            ) + aiSummary(a)
    }

    /** " · AI 판정 일치 6/7" once the classifier has judged some steering events. */
    private fun aiSummary(a: TripAnalyzer): String {
        if (maneuverModel == null) return ""
        val (judged, agreed) = a.maneuvers.agreement(a.allEvents, a::spanOf)
        return if (judged == 0) "" else " · AI 판정 일치 $agreed/$judged"
    }

    private fun penaltyText(p: Float) = if (p == 0f) "0" else "-%.1f".format(Locale.US, p)

    private fun renderEvents() {
        eventList.removeAllViews()
        val events = analyzer?.events(rules.id).orEmpty()
        if (events.isEmpty()) {
            eventList.addView(TextView(this).apply {
                text = getString(R.string.no_events)
                textSize = 20f
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }
        val start = analyzer?.startMs ?: 0L
        events.asReversed().forEach { eventList.addView(eventRow(it, start)) }
        aiPending = maneuverModel != null &&
            events.any { Maneuver.of(it) != null && analyzer?.aiVerdict(it) == null }
    }

    /** "AI: 차선변경(좌) 96%" under a steering event, highlighted when it disagrees with the rules. */
    private fun aiLine(e: DrivingEvent): TextView? {
        val a = analyzer ?: return null
        // The classifier only knows lane changes, turns and curves
        if (maneuverModel == null || Maneuver.of(e) == null) return null
        val v = a.aiVerdict(e)
        return TextView(this).apply {
            textSize = 16f
            if (v == null) {
                text = "AI 판정 중…"
                setTextColor(getColor(R.color.text_muted))
            } else {
                val agrees = v.agreesWith(e)
                text = "AI: ${v.maneuver.label} %.0f%%".format(Locale.US, v.confidence * 100) +
                    if (agrees) " ✓" else "  ⚠ 규칙 판정과 다름"
                setTextColor(getColor(if (agrees) R.color.accent else R.color.warn))
            }
        }
    }

    private fun eventRow(e: DrivingEvent, tripStartMs: Long): View {
        val color = getColor(EventText.colorRes(e))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
        }
        row.addView(View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(8, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = 16 }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = EventText.tag(e)
                    textSize = 15f
                    setPadding(10, 2, 10, 2)
                    setTextColor(getColor(R.color.bg))
                    setBackgroundColor(getColor(if (e.isSteering) R.color.accent else R.color.text_muted))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = 10 }
                })
                addView(TextView(context).apply {
                    text = EventText.title(e)
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
            addView(TextView(context).apply {
                text = "${EventText.clock(e.timeMs - tripStartMs)} · ${EventText.detail(e)}"
                textSize = 17f
                setTextColor(getColor(R.color.text_muted))
            })
            aiLine(e)?.let { addView(it) }
        })
        row.addView(TextView(this).apply {
            text = if (e.penalty == 0f) "0" else "-%.1f".format(Locale.US, e.penalty)
            textSize = 26f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
        })
        return row
    }

    /** Latest result per scenario under the selected baseline: the side-by-side numbers for the demo. */
    private fun renderComparison() {
        compareList.removeAllViews()
        val trips = history.latestPerLabel()
        if (trips.isEmpty()) {
            compareList.addView(TextView(this).apply {
                text = getString(R.string.no_history)
                textSize = 19f
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }
        trips.sortedBy { it.label }.forEach { t ->
            val base = t.baseline[rules.id] ?: return@forEach
            val steer = t.steer[rules.id] ?: return@forEach
            compareList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
                addView(TextView(context).apply {
                    text = t.label
                    textSize = 20f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "$base"
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(ScoreRingView.scoreColor(base)))
                })
                addView(TextView(context).apply {
                    text = "  →  "
                    textSize = 20f
                    setTextColor(getColor(R.color.text_muted))
                })
                addView(TextView(context).apply {
                    text = "$steer"
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(ScoreRingView.scoreColor(steer)))
                })
            })
        }
    }

    private fun renderControls() {
        actionButton.text = getString(
            when {
                mode == Mode.REPLAY && tripRunning -> R.string.replay_stop
                mode == Mode.REPLAY -> R.string.replay_start
                tripRunning -> R.string.trip_stop
                else -> R.string.trip_start
            }
        )
        actionButton.isSelected = tripRunning
        // The driver never presses it: a drive starts at gear D and ends at gear P. 시연 모드 needs it (simulated
        // drive, replay), since the emulator's car only moves through the Virtual Console
        actionButton.visibility = if (demoMode) View.VISIBLE else View.GONE
        reportButton.isEnabled = !tripRunning
        scenarioButton.text = "${selectedScenario.title}  ▸"
        scenarioButton.isEnabled = !tripRunning
        status.text = when (mode) {
            Mode.LIVE -> {
                val failed = source.failedSignals
                when {
                    failed.isNotEmpty() -> "받을 수 없는 신호: ${failed.joinToString()}"
                    tripRunning && simDrive -> if (player == null) "내비가 안내 중인 경로를 확인하는 중…" else "$tripLabel · ${((player?.progress ?: 0f) * 100).toInt()}%"
                    tripRunning -> "실주행 기록 중 · ${recorder?.file?.name ?: ""}"
                    !demoMode -> "기어를 D로 바꾸면 주행 기록이 자동으로 시작되고, P로 바꾸면 끝나요"
                    else -> getString(R.string.trip_idle)
                }
            }
            Mode.REPLAY -> {
                val p = player
                if (tripRunning && p != null) "${selectedScenario.title} 재생 중 · ${(p.progress * 100).toInt()}%"
                else selectedScenario.description
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::routePlanner.isInitialized) routePlanner.refresh()
    }

    override fun onDestroy() {
        DriveKeepAliveService.stop(this)
        endTrip()
        handler.removeCallbacksAndMessages(null)
        source.stop()
        voice.release()
        super.onDestroy()
    }

    companion object {
        private const val TAB_TRIP = 0
        private const val TAB_INSIGHTS = 2
        private const val MAX_AREA_BUTTONS = 6
        private const val MIN_SAVED_TRIP_MS = 10_000L
        private const val MODEL_ASSET = "maneuver_model.txt"
        private const val LIVE_LABEL = "실주행"
        private const val SIM_LABEL = "가상 주행 (⑦ 실제 도로)"
        private const val REQUEST_PERMISSIONS = 1
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        /** How long the driving view keeps showing an event. */
        private const val EVENT_SHOW_MS = 6000L
    }
}

/** Filters for the trip report's event list. */
private enum class ReportFilter(val label: String, val accepts: (com.iclab.steerscore.core.DrivingEvent) -> Boolean) {
    ALL("전체", { true }),
    DEDUCTIONS("감점", { it.penalty > 0f }),
    STEERING("조향 감점", { it.penalty > 0f && it.isSteering }),
    SPEED("속도 감점", { it.penalty > 0f && !it.isSteering }),
    NORMAL("정상", { it.penalty == 0f }),
}
