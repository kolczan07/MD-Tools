package pl.mdinstal.narzedzia

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wynik prośby o dostęp do Drive: token albo ekran zgody Google do pokazania użytkownikowi. */
sealed class DriveAuth {
    class Token(val token: String, val email: String?) : DriveAuth()
    class NeedsUi(val intent: PendingIntent) : DriveAuth()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Kopia zapasowa przypisań kontrahentów na Google Drive: ukryty folder aplikacji (appDataFolder)
 * na koncie wybranym w ustawieniach. Zakres drive.appdata widzi wyłącznie pliki tej aplikacji.
 */
object DriveBackup {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILE_NAME = "md-tools-kontrahenci.json"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    private val http = OkHttpClient()
    private val lock = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Prosi Google o token do Drive. Przy pierwszym razie zwraca ekran zgody (NeedsUi). */
    suspend fun authorize(ctx: Context, account: String?): DriveAuth {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (!account.isNullOrBlank()) b.setAccount(Account(account, "com.google"))
        val r = Identity.getAuthorizationClient(ctx).authorize(b.build()).awaitResult()
        val pi = r.pendingIntent
        return if (r.hasResolution() && pi != null) {
            DriveAuth.NeedsUi(pi)
        } else {
            val t = r.accessToken ?: throw IOException("Google nie zwrócił tokenu dostępu.")
            DriveAuth.Token(t, r.toGoogleSignInAccount()?.email)
        }
    }

    /** Scala lokalne przypisania z kopią na Drive (w obie strony). Zwraca liczbę aktywnych przypisań. */
    suspend fun sync(ctx: Context, token: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = findFileId(token)
            val remoteText = if (id != null) download(token, id) else null
            val remote = ContractorStore.parse(remoteText)
            val merged = ContractorStore.merge(ContractorStore.all(ctx), remote)
            ContractorStore.replaceAll(ctx, merged)
            val json = ContractorStore.toJson(merged)
            if (id == null || json != ContractorStore.toJson(remote)) upload(token, id, json)
            Prefs.setLastSync(ctx, System.currentTimeMillis())
            merged.values.count { it.contractor.isNotEmpty() }
        }
    }

    /** Synchronizacja po cichu (bez okien): jeśli konto jest połączone i nie wymaga ponownej zgody. */
    suspend fun syncQuiet(ctx: Context): Boolean {
        if (!Prefs.driveLinked(ctx)) return false
        return try {
            val a = authorize(ctx, Prefs.googleAccount(ctx))
            if (a is DriveAuth.Token) {
                sync(ctx, a.token)
                true
            } else {
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Wysyła kopię w tle po zmianie przypisania (nie blokuje interfejsu). */
    fun syncInBackground(ctx: Context) {
        val app = ctx.applicationContext
        bg.launch { syncQuiet(app) }
    }

    private fun findFileId(token: String): String? {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()
        val body = call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        val files: JSONArray = JSONObject(body).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).optString("id").ifEmpty { null } else null
    }

    private fun download(token: String, id: String): String {
        val url = "$API/$id".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        return call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
    }

    private fun upload(token: String, id: String?, json: String) {
        val jsonType = "application/json; charset=UTF-8".toMediaType()
        if (id == null) {
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(Headers.headersOf("Content-Type", "application/json; charset=UTF-8"), meta.toRequestBody(jsonType))
                .addPart(Headers.headersOf("Content-Type", "application/json; charset=UTF-8"), json.toRequestBody(jsonType))
                .build()
            val url = UPLOAD.toHttpUrl().newBuilder().addQueryParameter("uploadType", "multipart").build()
            call(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build())
        } else {
            val url = "$UPLOAD/$id".toHttpUrl().newBuilder().addQueryParameter("uploadType", "media").build()
            call(Request.Builder().url(url).header("Authorization", "Bearer $token").patch(json.toRequestBody(jsonType)).build())
        }
    }

    private fun call(req: Request): String {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                } catch (e: Exception) {
                    ""
                }
                throw IOException("Drive: HTTP ${r.code}" + if (msg.isNotEmpty()) " ($msg)" else "")
            }
            return text
        }
    }
}
