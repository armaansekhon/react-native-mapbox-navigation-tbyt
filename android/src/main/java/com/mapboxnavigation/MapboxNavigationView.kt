package com.mapboxnavigation

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.replay.MapboxReplayer
import com.mapbox.navigation.core.replay.ReplayLocationEngine
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverPrimaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSecondaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSubOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverViewOptions
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.components.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.ui.tripprogress.api.MapboxTripProgressApi
import com.mapbox.navigation.ui.tripprogress.model.DistanceRemainingFormatter
import com.mapbox.navigation.ui.tripprogress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.ui.tripprogress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.ui.tripprogress.model.TimeRemainingFormatter
import com.mapbox.navigation.ui.tripprogress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.voice.api.MapboxSpeechApi
import com.mapbox.navigation.ui.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.ui.voice.model.SpeechAnnouncement
import com.mapbox.navigation.ui.voice.model.SpeechError
import com.mapbox.navigation.ui.voice.model.SpeechValue
import com.mapbox.navigation.ui.voice.model.SpeechVolume
import com.mapboxnavigation.databinding.NavigationViewBinding
import java.util.Locale

data class WaypointLegs(val index: Int, val name: String)

@SuppressLint("ViewConstructor")
class MapboxNavigationView(private val context: ThemedReactContext) : FrameLayout(context.baseContext) {
    private companion object {
        private const val BUTTON_ANIMATION_DURATION = 1500L
    }

    private var origin: Point? = null
    private var destination: Point? = null
    private var destinationTitle: String = "Destination"
    private var waypoints: List<Point> = listOf()
    private var waypointLegs: List<WaypointLegs> = listOf()
    private var distanceUnit: String = DirectionsCriteria.IMPERIAL
    private var locale = Locale.getDefault()
    private var travelMode: String = DirectionsCriteria.PROFILE_DRIVING
    private var shouldSimulateRoute: Boolean = false
    private val mapboxReplayer = MapboxReplayer()
    private val replayLocationEngine = ReplayLocationEngine(mapboxReplayer)
    private val replayProgressObserver = ReplayProgressObserver(mapboxReplayer)

    private var binding: NavigationViewBinding = NavigationViewBinding.inflate(LayoutInflater.from(context), this, true)
    private var viewportDataSource = MapboxNavigationViewportDataSource(binding.mapView.mapboxMap)
    private var navigationCamera = NavigationCamera(
        binding.mapView.mapboxMap,
        binding.mapView.camera,
        viewportDataSource
    )
    private var mapboxNavigation: MapboxNavigation? = null

    private val pixelDensity = Resources.getSystem().displayMetrics.density
    private val overviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            140.0 * pixelDensity,
            40.0 * pixelDensity,
            120.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }
    private val landscapeOverviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            30.0 * pixelDensity,
            380.0 * pixelDensity,
            110.0 * pixelDensity,
            20.0 * pixelDensity
        )
    }
    private val followingPadding: EdgeInsets by lazy {
        EdgeInsets(
            180.0 * pixelDensity,
            40.0 * pixelDensity,
            150.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }
    private val landscapeFollowingPadding: EdgeInsets by lazy {
        EdgeInsets(
            30.0 * pixelDensity,
            380.0 * pixelDensity,
            110.0 * pixelDensity,
            40.0 * pixelDensity
        )
    }

    private lateinit var maneuverApi: MapboxManeuverApi
    private lateinit var tripProgressApi: MapboxTripProgressApi
    private var isVoiceInstructionsMuted = false
        set(value) {
            field = value
            if (value) {
                binding.soundButton.muteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer?.volume(SpeechVolume(0f))
            } else {
                binding.soundButton.unmuteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer?.volume(SpeechVolume(1f))
            }
        }

    private lateinit var speechApi: MapboxSpeechApi
    private var voiceInstructionsPlayer: MapboxVoiceInstructionsPlayer? = null
    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        speechApi.generate(voiceInstructions, speechCallback)
    }
    private val speechCallback =
        MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
            expected.fold(
                { error ->
                    voiceInstructionsPlayer?.play(error.fallback, voiceInstructionsPlayerCallback)
                },
                { value ->
                    voiceInstructionsPlayer?.play(value.announcement, voiceInstructionsPlayerCallback)
                }
            )
        }
    private val voiceInstructionsPlayerCallback =
        MapboxNavigationConsumer<SpeechAnnouncement> { value ->
            speechApi.clean(value)
        }

    private val navigationLocationProvider = NavigationLocationProvider()

    private val routeLineViewOptions: MapboxRouteLineViewOptions by lazy {
        MapboxRouteLineViewOptions.Builder(context)
            .routeLineColorResources(RouteLineColorResources.Builder().build())
            .routeLineBelowLayerId("road-label-navigation")
            .build()
    }
    private val routeLineApiOptions: MapboxRouteLineApiOptions by lazy {
        MapboxRouteLineApiOptions.Builder().build()
    }
    private val routeLineView by lazy { MapboxRouteLineView(routeLineViewOptions) }
    private val routeLineApi: MapboxRouteLineApi by lazy { MapboxRouteLineApi(routeLineApiOptions) }
    private val routeArrowApi: MapboxRouteArrowApi by lazy { MapboxRouteArrowApi() }
    private val routeArrowOptions by lazy {
        RouteArrowOptions.Builder(context)
            .withAboveLayerId(TOP_LEVEL_ROUTE_LINE_LAYER_ID)
            .build()
    }
    private val routeArrowView: MapboxRouteArrowView by lazy { MapboxRouteArrowView(routeArrowOptions) }

    private val locationObserver = object : LocationObserver {
        var firstLocationUpdateReceived = false

        override fun onNewRawLocation(rawLocation: Location) {
            // not handled
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )
            viewportDataSource.onLocationChanged(enhancedLocation)
            viewportDataSource.evaluate()

            if (!firstLocationUpdateReceived) {
                firstLocationUpdateReceived = true
                navigationCamera.requestNavigationCameraToOverview(
                    stateTransitionOptions = NavigationCameraTransitionOptions.Builder()
                        .maxDuration(0)
                        .build()
                )
            }

            val event = Arguments.createMap()
            event.putDouble("longitude", enhancedLocation.longitude)
            event.putDouble("latitude", enhancedLocation.latitude)
            event.putDouble("heading", enhancedLocation.bearing ?: 0.0)
            event.putDouble("accuracy", enhancedLocation.horizontalAccuracy ?: 0.0)
            context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onLocationChange", event)
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        if (routeProgress.fractionTraveled.toDouble() != 0.0) {
            viewportDataSource.onRouteProgressChanged(routeProgress)
        }
        viewportDataSource.evaluate()

        val style = binding.mapView.mapboxMap.style
        if (style != null) {
            val maneuverArrowResult = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
            routeArrowView.renderManeuverUpdate(style, maneuverArrowResult)
        }

        val maneuvers = maneuverApi.getManeuvers(routeProgress)
        maneuvers.fold(
            { error ->
                Log.w("Maneuvers error:", error.throwable)
            },
            {
                val maneuverViewOptions = ManeuverViewOptions.Builder()
                    .primaryManeuverOptions(
                        ManeuverPrimaryOptions.Builder()
                            .textAppearance(com.mapboxnavigation.R.style.PrimaryManeuverTextAppearance)
                            .build()
                    )
                    .secondaryManeuverOptions(
                        ManeuverSecondaryOptions.Builder()
                            .textAppearance(com.mapboxnavigation.R.style.ManeuverTextAppearance)
                            .build()
                    )
                    .subManeuverOptions(
                        ManeuverSubOptions.Builder()
                            .textAppearance(com.mapboxnavigation.R.style.ManeuverTextAppearance)
                            .build()
                    )
                    .stepDistanceTextAppearance(com.mapboxnavigation.R.style.StepDistanceRemainingAppearance)
                    .build()

                binding.maneuverView.visibility = View.VISIBLE
                binding.maneuverView.updateManeuverViewOptions(maneuverViewOptions)
                binding.maneuverView.renderManeuvers(maneuvers)
            }
        )

        binding.tripProgressView.render(tripProgressApi.getTripProgress(routeProgress))

        val event = Arguments.createMap()
        event.putDouble("distanceTraveled", routeProgress.distanceTraveled.toDouble())
        event.putDouble("durationRemaining", routeProgress.durationRemaining)
        event.putDouble("fractionTraveled", routeProgress.fractionTraveled.toDouble())
        event.putDouble("distanceRemaining", routeProgress.distanceRemaining.toDouble())
        context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onRouteProgressChange", event)
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            routeLineApi.setNavigationRoutes(routeUpdateResult.navigationRoutes) { value ->
                binding.mapView.mapboxMap.style?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }
            viewportDataSource.onRouteChanged(routeUpdateResult.navigationRoutes.first())
            viewportDataSource.evaluate()
        } else {
            val style = binding.mapView.mapboxMap.style
            if (style != null) {
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(style, value)
                }
                routeArrowView.render(style, routeArrowApi.clearArrows())
            }
            viewportDataSource.clearRouteData()
            viewportDataSource.evaluate()
        }
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) {
            onArrival(routeProgress)
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
            // do something when the user starts a new leg
        }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            onArrival(routeProgress)
        }
    }

    private fun onArrival(routeProgress: RouteProgress) {
        val leg = routeProgress.currentLegProgress
        if (leg != null) {
            val event = Arguments.createMap()
            event.putInt("index", leg.legIndex)
            event.putDouble("latitude", leg.legDestination?.location?.latitude() ?: 0.0)
            event.putDouble("longitude", leg.legDestination?.location?.longitude() ?: 0.0)
            context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onArrive", event)
        }
    }

    init {
        onCreate()
    }

    @SuppressLint("MissingPermission")
    private fun onCreate() {
        mapboxNavigation = if (MapboxNavigationProvider.isCreated()) {
            MapboxNavigationProvider.retrieve()
        } else {
            MapboxNavigationProvider.create(
                NavigationOptions.Builder(context)
                    .locationEngine(if (shouldSimulateRoute) replayLocationEngine else navigationLocationProvider)
                    .build()
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun initNavigation() {
        if (origin == null || destination == null) {
            sendErrorToReact("origin and destination are required")
            return
        }

        val initialCameraOptions = CameraOptions.Builder()
            .zoom(14.0)
            .center(origin)
            .build()
        binding.mapView.mapboxMap.setCamera(initialCameraOptions)

        startNavigation()

        binding.mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera)
        )
        navigationCamera.registerNavigationCameraStateChangeObserver { navigationCameraState ->
            when (navigationCameraState) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING,
                NavigationCameraState.FOLLOWING -> binding.recenter.visibility = View.INVISIBLE
                NavigationCameraState.TRANSITION_TO_OVERVIEW,
                NavigationCameraState.OVERVIEW,
                NavigationCameraState.IDLE -> binding.recenter.visibility = View.VISIBLE
            }
        }

        if (this.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            viewportDataSource.overviewPadding = landscapeOverviewPadding
            viewportDataSource.followingPadding = landscapeFollowingPadding
        } else {
            viewportDataSource.overviewPadding = overviewPadding
            viewportDataSource.followingPadding = followingPadding
        }

        val unitType = if (distanceUnit == "imperial") UnitType.IMPERIAL else UnitType.METRIC
        val distanceFormatterOptions = DistanceFormatterOptions.Builder(context)
            .unitType(unitType)
            .build()

        maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatterOptions))

        tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(context)
                .distanceRemainingFormatter(DistanceRemainingFormatter(distanceFormatterOptions))
                .timeRemainingFormatter(TimeRemainingFormatter(context))
                .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
                .estimatedTimeToArrivalFormatter(
                    EstimatedTimeToArrivalFormatter(context, TimeFormat.NONE_SPECIFIED)
                )
                .build()
        )

        speechApi = MapboxSpeechApi(context, locale.language)
        voiceInstructionsPlayer = MapboxVoiceInstructionsPlayer(context, locale.language)

        binding.mapView.mapboxMap.loadStyle(NavigationStyles.NAVIGATION_DAY_STYLE) {
            routeLineView.initializeLayers(it)
        }

        binding.stop.setOnClickListener {
            val event = Arguments.createMap()
            event.putString("message", "Navigation Cancel")
            context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onCancelNavigation", event)
        }

        binding.recenter.setOnClickListener {
            navigationCamera.requestNavigationCameraToFollowing()
            binding.routeOverview.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.routeOverview.setOnClickListener {
            navigationCamera.requestNavigationCameraToOverview()
            binding.recenter.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.soundButton.setOnClickListener {
            isVoiceInstructionsMuted = !isVoiceInstructionsMuted
        }

        if (this.isVoiceInstructionsMuted) {
            binding.soundButton.mute()
            voiceInstructionsPlayer?.volume(SpeechVolume(0f))
        } else {
            binding.soundButton.unmute()
            voiceInstructionsPlayer?.volume(SpeechVolume(1f))
        }
    }

    private fun onDestroy() {
        maneuverApi.cancel()
        routeLineApi.cancel()
        routeLineView.cancel()
        speechApi.cancel()
        voiceInstructionsPlayer?.shutdown()
        mapboxReplayer.finish()
        mapboxNavigation?.unregisterRouteProgressObserver(replayProgressObserver)
        mapboxNavigation?.stopTripSession()
        MapboxNavigationProvider.destroy()
    }

    private fun startNavigation() {
        binding.mapView.location.apply {
            setLocationProvider(navigationLocationProvider)
            this.locationPuck = LocationPuck2D(
                bearingImage = ImageHolder.from(com.mapbox.navigation.ui.maps.R.drawable.mapbox_navigation_puck_icon)
            )
            puckBearingEnabled = true
            enabled = true
        }
        startRoute()
    }

    private fun startSimulation(route: NavigationRoute) {
        mapboxReplayer.run {
            stop()
            clearEvents()
            val replayEvents = ReplayRouteMapper().mapRouteToReplayEvents(route)
            pushEvents(replayEvents)
            seekTo(replayEvents.first())
            play()
        }
    }

    private fun findRoute(coordinates: List<Point>) {
        val indices = mutableListOf<Int>()
        val names = mutableListOf<String>()
        indices.add(0)
        names.add("origin")
        waypointLegs.forEachIndexed { index, leg ->
            indices.add(leg.index)
            names.add(leg.name)
        }
        indices.add(coordinates.size - 1)
        names.add(destinationTitle)

        mapboxNavigation?.requestRoutes(
            RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(coordinates)
                .waypointIndicesList(indices)
                .waypointNamesList(names)
                .language(locale.language)
                .steps(true)
                .voiceInstructions(true)
                .voiceUnits(distanceUnit)
                .profile(travelMode)
                .build(),
            object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, @RouterOrigin routerOrigin: String) {
                    // no implementation
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    sendErrorToReact("Error finding route $reasons")
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    @RouterOrigin routerOrigin: String
                ) {
                    setRouteAndStartNavigation(routes)
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun setRouteAndStartNavigation(routes: List<NavigationRoute>) {
        mapboxNavigation?.setNavigationRoutes(routes)
        binding.soundButton.visibility = View.VISIBLE
        binding.routeOverview.visibility = View.VISIBLE
        binding.tripProgressCard.visibility = View.VISIBLE
        if (shouldSimulateRoute && routes.isNotEmpty()) {
            startSimulation(routes.first())
        }
        mapboxNavigation?.startTripSession(withForegroundService = true)
    }

    private fun startRoute() {
        mapboxNavigation?.registerRoutesObserver(routesObserver)
        mapboxNavigation?.registerArrivalObserver(arrivalObserver)
        mapboxNavigation?.registerRouteProgressObserver(routeProgressObserver)
        mapboxNavigation?.registerLocationObserver(locationObserver)
        mapboxNavigation?.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNavigation?.registerRouteProgressObserver(replayProgressObserver)

        val coordinatesList = mutableListOf<Point>()
        this.origin?.let { coordinatesList.add(it) }
        this.waypoints.let { coordinatesList.addAll(waypoints) }
        this.destination?.let { coordinatesList.add(it) }

        findRoute(coordinatesList)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mapboxNavigation?.unregisterRoutesObserver(routesObserver)
        mapboxNavigation?.unregisterArrivalObserver(arrivalObserver)
        mapboxNavigation?.unregisterLocationObserver(locationObserver)
        mapboxNavigation?.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation?.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
        mapboxNavigation?.unregisterRouteProgressObserver(replayProgressObserver)
        mapboxNavigation?.setNavigationRoutes(listOf())
        binding.soundButton.visibility = View.INVISIBLE
        binding.maneuverView.visibility = View.INVISIBLE
        binding.routeOverview.visibility = View.INVISIBLE
        binding.tripProgressCard.visibility = View.INVISIBLE
    }

    private fun sendErrorToReact(error: String?) {
        val event = Arguments.createMap()
        event.putString("error", error)
        context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onError", event)
    }

    fun onDropViewInstance() {
        this.onDestroy()
    }

    fun setStartOrigin(origin: Point?) {
        this.origin = origin
    }

    fun setDestination(destination: Point?) {
        this.destination = destination
    }

    fun setDestinationTitle(title: String) {
        this.destinationTitle = title
    }

    fun setWaypointLegs(legs: List<WaypointLegs>) {
        this.waypointLegs = legs
    }

    fun setWaypoints(waypoints: List<Point>) {
        this.waypoints = waypoints
    }

    fun setDistanceUnit(unit: String) {
        this.distanceUnit = unit
        initNavigation()
    }

    fun setLanguage(language: String) {
        val locals = language.split("-")
        when (locals.size) {
            1 -> locale = Locale(locals.first())
            2 -> locale = Locale(locals.first(), locals.last())
        }
    }

    fun setShouldSimulateRoute(shouldSimulateRoute: Boolean) {
        this.shouldSimulateRoute = shouldSimulateRoute
        mapboxNavigation?.setLocationEngine(if (shouldSimulateRoute) replayLocationEngine else navigationLocationProvider)
        if (!shouldSimulateRoute) {
            mapboxReplayer.stop()
        }
    }

    fun setMute(mute: Boolean) {
        this.isVoiceInstructionsMuted = mute
    }

    fun setShowCancelButton(show: Boolean) {
        binding.stop.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    fun setTravelMode(mode: String) {
        travelMode = when (mode.lowercase()) {
            "walking" -> DirectionsCriteria.PROFILE_WALKING
            "cycling" -> DirectionsCriteria.PROFILE_CYCLING
            "driving" -> DirectionsCriteria.PROFILE_DRIVING
            "driving-traffic" -> DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
            else -> DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
        }
    }
}
