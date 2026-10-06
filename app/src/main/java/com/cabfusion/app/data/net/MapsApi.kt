package com.cabfusion.app.data.net

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** OSRM route service (router.project-osrm.org). Coordinates are "lng,lat;lng,lat;…". */
interface OsrmApi {
    @GET("route/v1/driving/{coords}")
    suspend fun route(
        @Path("coords", encoded = true) coords: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson",
    ): OsrmResponse
}

data class OsrmResponse(val code: String?, val routes: List<OsrmRoute>?)
data class OsrmRoute(val distance: Double, val duration: Double, val geometry: OsrmGeometry?)
data class OsrmGeometry(val coordinates: List<List<Double>>?)

/** Photon geocoder (photon.komoot.io), built on OpenStreetMap data. */
interface PhotonApi {
    @GET("api/")
    suspend fun search(
        @Query("q") q: String,
        @Query("lat") lat: Double = 13.05,
        @Query("lon") lon: Double = 80.22,
        @Query("limit") limit: Int = 6,
        @Query("lang") lang: String = "en",
    ): PhotonResponse

    @GET("reverse")
    suspend fun reverse(@Query("lat") lat: Double, @Query("lon") lon: Double): PhotonResponse
}

data class PhotonResponse(val features: List<PhotonFeature>?)
data class PhotonFeature(val geometry: PhotonGeometry?, val properties: PhotonProps?)
data class PhotonGeometry(val coordinates: List<Double>?)
data class PhotonProps(
    val name: String?, val street: String?, val district: String?, val locality: String?,
    val city: String?, val state: String?,
)
