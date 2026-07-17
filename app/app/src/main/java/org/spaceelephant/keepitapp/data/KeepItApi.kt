package org.spaceelephant.keepitapp.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The keepIT REST endpoints used by the app (v1 scope: auth + notes + lists). Auth-free routes and
 * bearer attachment are handled by the OkHttp interceptor in [ApiClient] — nothing here carries
 * auth explicitly. The refresh call is NOT on this interface on purpose: it must run on the bare
 * client (no auth interceptor) to avoid recursion; see [ApiClient.refreshBlocking].
 */
interface KeepItApi {

    // ---- auth ----

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    /** Requests a reset link (always 204 — the server never reveals whether the email exists).
     *  The reset itself is completed in the browser via the emailed link, not in the app. */
    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequestDto)

    @POST("api/auth/logout")
    suspend fun logout()

    @GET("api/auth/me")
    suspend fun me(): UserDto

    // ---- notes ----

    /** The caller's grid for a view; `listId` may repeat to filter by lists (union). */
    @GET("api/notes")
    suspend fun notes(
        @Query("archived") archived: Boolean = false,
        @Query("trashed") trashed: Boolean = false,
        @Query("listId") listIds: List<String>? = null,
    ): List<NoteDto>

    @GET("api/notes/{id}")
    suspend fun note(@Path("id") id: String): NoteDto

    @POST("api/notes")
    suspend fun createNote(@Body body: CreateNoteDto): NoteDto

    @PUT("api/notes/{id}")
    suspend fun updateNote(@Path("id") id: String, @Body body: UpdateNoteDto): NoteDto

    @PATCH("api/notes/{id}/state")
    suspend fun setNoteState(@Path("id") id: String, @Body body: NoteStateDto): NoteDto

    @PUT("api/notes/{id}/lists")
    suspend fun setNoteLists(@Path("id") id: String, @Body body: SetNoteListsDto): NoteDto

    @DELETE("api/notes/{id}")
    suspend fun deleteNote(@Path("id") id: String)

    /** Sets (or replaces) the caller's per-user reminder — read access suffices, viewers included. */
    @PUT("api/notes/{id}/reminder")
    suspend fun setReminder(@Path("id") id: String, @Body body: SetNoteReminderDto): NoteDto

    /** Clears the caller's reminder (idempotent — 200 even if none was set). */
    @DELETE("api/notes/{id}/reminder")
    suspend fun clearReminder(@Path("id") id: String): NoteDto

    // ---- lists ----

    @GET("api/lists")
    suspend fun lists(): List<ListDto>

    // ---- notifications ----

    /** The caller's notification inbox, newest first — surfaced as native Android notifications. */
    @GET("api/notifications")
    suspend fun notifications(): List<UserNotificationDto>
}
