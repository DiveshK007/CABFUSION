package com.cabfusion.app.data

import com.cabfusion.app.data.model.RideGroup
import com.cabfusion.app.data.model.RideRequest
import com.cabfusion.app.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app needs from a backend. Two implementations:
 *  - [FirebaseBackend]: Firebase Authentication + Cloud Firestore (real multi-user data)
 *  - [LocalBackend]: on-device demo backend with sample Chennai riders and a simulated driver
 */
interface Backend {
    val isDemo: Boolean

    suspend fun restoreSession(): UserProfile?
    fun currentUser(): UserProfile?
    suspend fun register(name: String, email: String, phone: String, password: String, role: String): UserProfile
    suspend fun login(email: String, password: String): UserProfile
    suspend fun updateRole(role: String): UserProfile
    fun logout()

    suspend fun createRequest(request: RideRequest): RideRequest
    fun observeRequest(id: String): Flow<RideRequest?>
    suspend fun openRequests(): List<RideRequest>
    suspend fun cancelRequest(id: String)
    suspend fun myRequests(uid: String): List<RideRequest>

    /** Atomically creates the group and links every member request; fails if any request was taken. */
    suspend fun formGroup(group: RideGroup): RideGroup
    fun observeGroup(id: String): Flow<RideGroup?>
    fun observeOpenGroups(): Flow<List<RideGroup>>
    suspend fun acceptGroup(groupId: String, driver: UserProfile, vehicle: String)
    suspend fun updateGroupStatus(groupId: String, status: String)
    suspend fun updateDriverLocation(groupId: String, lat: Double, lng: Double)
}

class BackendException(message: String) : Exception(message)
