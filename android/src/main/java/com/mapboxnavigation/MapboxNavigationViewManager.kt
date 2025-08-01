package com.mapboxnavigation

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.annotations.ReactPropGroup
import com.facebook.react.common.MapBuilder
import com.mapbox.geojson.Point
import com.facebook.react.bridge.ReadableArray

data class WaypointLegs(val index: Int, val name: String)

class MapboxNavigationViewManager : SimpleViewManager<MapboxNavigationView>() {
    override fun getName(): String {
        return "MapboxNavigationView"
    }

    override fun getShadowNodeClass(): Class<out com.facebook.react.uimanager.ReactShadowNode<*>> {
        return com.facebook.react.uimanager.LayoutShadowNode::class.java
    }

    override fun createViewInstance(reactContext: ThemedReactContext): MapboxNavigationView {
        return MapboxNavigationView(reactContext)
    }

    override fun onDropViewInstance(view: MapboxNavigationView) {
        super.onDropViewInstance(view)
        view.onDropViewInstance()
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> {
        return MapBuilder.of(
            "onLocationChange", MapBuilder.of("registrationName", "onLocationChange"),
            "onRouteProgressChange", MapBuilder.of("registrationName", "onRouteProgressChange"),
            "onError", MapBuilder.of("registrationName", "onError"),
            "onCancelNavigation", MapBuilder.of("registrationName", "onCancelNavigation"),
            "onArrive", MapBuilder.of("registrationName", "onArrive")
        )
    }

    @ReactPropGroup(names = ["startOrigin", "destination"])
    fun setCoordinates(view: MapboxNavigationView?, index: Int, value: ReadableArray?) {
        if (value == null) return
        val point = Point.fromLngLat(value.getDouble(0), value.getDouble(1))
        when (index) {
            0 -> view?.setStartOrigin(point)
            1 -> view?.setDestination(point)
        }
    }

    @ReactProp(name = "destinationTitle")
    fun setDestinationTitle(view: MapboxNavigationView?, title: String?) {
        if (title != null) {
            view?.setDestinationTitle(title)
        }
    }

    @ReactProp(name = "waypoints")
    fun setWaypoints(view: MapboxNavigationView?, value: ReadableArray?) {
        if (value == null) {
            view?.setWaypoints(listOf())
            view?.setWaypointLegs(listOf())
            return
        }
        val legs = mutableListOf<WaypointLegs>()
        val waypoints: List<Point> = value.toArrayList().mapIndexedNotNull { index, item ->
            val map = item as? Map<*, *>
            val latitude = map?.get("latitude") as? Double
            val longitude = map?.get("longitude") as? Double
            val name = map?.get("name") as? String
            val separatesLegs = map?.get("separatesLegs") as? Boolean
            if (latitude != null && longitude != null) {
                if (separatesLegs != false) {
                    legs.add(WaypointLegs(index = index + 1, name = name ?: "waypoint-$index"))
                }
                Point.fromLngLat(longitude, latitude)
            } else {
                null
            }
        }
        view?.setWaypointLegs(legs)
        view?.setWaypoints(waypoints)
    }

    @ReactProp(name = "distanceUnit")
    fun setDistanceUnit(view: MapboxNavigationView?, value: String?) {
        if (value != null) {
            view?.setDistanceUnit(value)
        }
    }

    @ReactProp(name = "language")
    fun setLanguage(view: MapboxNavigationView?, language: String?) {
        if (language != null) {
            view?.setLanguage(language)
        }
    }

    @ReactProp(name = "shouldSimulateRoute")
    fun setShouldSimulateRoute(view: MapboxNavigationView?, value: Boolean) {
        view?.setShouldSimulateRoute(value)
    }

    @ReactProp(name = "separateLegs")
    fun setSeparateLegs(view: MapboxNavigationView?, value: Boolean) {
        // No-op, as separatesLegs is handled in setWaypoints
    }

    @ReactProp(name = "mute")
    fun setMute(view: MapboxNavigationView?, mute: Boolean) {
        view?.setMute(mute)
    }

    @ReactProp(name = "showCancelButton")
    fun setShowCancelButton(view: MapboxNavigationView?, show: Boolean) {
        view?.setShowCancelButton(show)
    }

    @ReactProp(name = "travelMode")
    fun setTravelMode(view: MapboxNavigationView?, mode: String?) {
        if (mode != null) {
            view?.setTravelMode(mode)
        }
    }
}
