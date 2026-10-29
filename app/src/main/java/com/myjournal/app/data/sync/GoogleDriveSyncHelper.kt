package com.myjournal.app.data.sync
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class GoogleDriveSyncHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val BACKUP_FILE_NAME = "my_journal_cloud_backup.json"
    }
    fun getSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
        return if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
            account
        } else {
            null
        }
    }
    fun getSignInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }
    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_APPDATA)
        ).setSelectedAccount(account.account)
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("My Journal").build()
    }
    suspend fun uploadBackupJsonToDrive(jsonPayload: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val account = getSignedInAccount() ?: error("Not signed into Google Drive")
            val service = buildDriveService(account)
            val fileList = service.files().list()
                .setSpaces("appDataFolder")
                .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
                .setFields("files(id, name)")
                .execute()
            val mediaContent = ByteArrayContent.fromString("application/json", jsonPayload)
            if (fileList.files.isNullOrEmpty()) {
                val metadata = DriveFile().apply {
                    name = BACKUP_FILE_NAME
                    parents = Collections.singletonList("appDataFolder")
                }
                service.files().create(metadata, mediaContent)
                    .setFields("id")
                    .execute()
            } else {
                val existingId = fileList.files[0].id
                service.files().update(existingId, null, mediaContent).execute()
            }
            account.email ?: "Google Drive"
        }
    }
    suspend fun downloadBackupJsonFromDrive(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val account = getSignedInAccount() ?: error("Not signed into Google Drive")
            val service = buildDriveService(account)
            val fileList = service.files().list()
                .setSpaces("appDataFolder")
                .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
                .setFields("files(id, name)")
                .execute()
            if (fileList.files.isNullOrEmpty()) {
                error("No backup found in your Google Drive yet")
            }
            val fileId = fileList.files[0].id
            val outputStream = ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.toString(Charsets.UTF_8.name())
        }
    }
}