package org.hyperstarit.keepitapp.data

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

    /** Changes the password. The server revokes all other sessions and returns fresh tokens for this one. */
    @POST("api/auth/changepassword")
    suspend fun changePassword(@Body body: ChangePasswordRequestDto): AuthResponseDto

    // ---- meta ----

    /** The server's public metadata (version). Anonymous. */
    @GET("api/meta")
    suspend fun meta(): MetaDto

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

    // ---- note shares ----

    /** The note's collaborators; for the owner this also includes still-pending invites. */
    @GET("api/notes/{id}/shares")
    suspend fun shares(@Path("id") noteId: String): List<NoteShareDto>

    /** Invites a user by email (owner-only). Access is granted only when they accept. */
    @POST("api/notes/{id}/shares")
    suspend fun createShare(@Path("id") noteId: String, @Body body: CreateShareDto)

    /** Changes a collaborator's role (owner-only). */
    @PATCH("api/notes/{id}/shares/{granteeId}")
    suspend fun updateShareRole(
        @Path("id") noteId: String,
        @Path("granteeId") granteeId: String,
        @Body body: UpdateShareRoleDto,
    )

    /** Revokes a share or cancels a pending invite; a collaborator revokes themselves to leave. */
    @DELETE("api/notes/{id}/shares/{granteeId}")
    suspend fun revokeShare(@Path("id") noteId: String, @Path("granteeId") granteeId: String)

    // ---- lists ----

    @GET("api/lists")
    suspend fun lists(): List<ListDto>

    @POST("api/lists")
    suspend fun createList(@Body body: CreateListDto): ListDto

    @PATCH("api/lists/{id}")
    suspend fun updateList(@Path("id") id: String, @Body body: UpdateListDto): ListDto

    /** Deletes a list. Notes filed in it are untouched — only the memberships go. */
    @DELETE("api/lists/{id}")
    suspend fun deleteList(@Path("id") id: String)

    // ---- notifications ----

    /** The caller's notification inbox, newest first — surfaced as native Android notifications. */
    @GET("api/notifications")
    suspend fun notifications(): List<UserNotificationDto>

    /** Answers a share invite (accept = gain access / decline). Either way the invite is consumed. */
    @POST("api/notifications/{id}/respond")
    suspend fun respondToInvite(@Path("id") id: String, @Body body: ShareResponseDto)

    /** Dismisses (permanently deletes) one notification. */
    @DELETE("api/notifications/{id}")
    suspend fun deleteNotification(@Path("id") id: String)
}
