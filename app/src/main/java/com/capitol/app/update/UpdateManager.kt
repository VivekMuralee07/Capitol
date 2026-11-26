package com.capitol.app.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.capitol.app.BuildConfig
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.resume

/**
 * In-app updates via two channels:
 *  1. **Google Play** in-app updates (flexible flow) when installed from the Play Store.
 *  2. **GitHub Releases** for sideloaded installs: checks the latest release of the
 *     Capitol repository, downloads the APK inside the app and launches the installer.
 */
class UpdateManager(private val context: Context, private val http: OkHttpClient) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val notes: String, val apkUrl: String?, val viaPlay: Boolean) : State
        data class Downloading(val progress: Float) : State
        data object ReadyToInstall : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val playManager by lazy { AppUpdateManagerFactory.create(context) }
    private var downloadedApk: File? = null

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val installedFromPlay: Boolean
        get() = runCatching {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
            installer == "com.android.vending"
        }.getOrDefault(false)

    suspend fun check(silent: Boolean = false) {
        if (_state.value is State.Downloading) return
        if (!silent) _state.value = State.Checking
        try {
            if (installedFromPlay && checkPlay()) return
            checkGitHub()
        } catch (e: Exception) {
            _state.value = if (silent) State.Idle else State.Error(e.message ?: "Update check failed")
        }
    }

    private suspend fun checkPlay(): Boolean = suspendCancellableCoroutine { cont ->
        playManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    _state.value = State.Available(info.availableVersionCode().toString(), "A new version is available on Google Play.", null, true)
                    cont.resume(true)
                } else cont.resume(false)
            }
            .addOnFailureListener { cont.resume(false) }
    }

    private suspend fun checkGitHub() = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) { _state.value = State.UpToDate; return@withContext }
            if (!resp.isSuccessful) throw IllegalStateException("GitHub HTTP ${resp.code}")
            val o = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val tag = (o["tag_name"] as? JsonPrimitive)?.content?.removePrefix("v") ?: return@withContext
            val notes = (o["body"] as? JsonPrimitive)?.content.orEmpty()
            val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { ((it["name"] as? JsonPrimitive)?.content ?: "").endsWith(".apk") }
                ?.let { (it["browser_download_url"] as? JsonPrimitive)?.content }
            _state.value = if (isNewer(tag, currentVersion)) State.Available(tag, notes, apk, false) else State.UpToDate
        }
    }

    /** Starts the update: Play flexible flow, or in-app APK download. */
    fun start(activity: Activity) {
        val s = _state.value as? State.Available ?: return
        if (s.viaPlay) {
            playManager.appUpdateInfo.addOnSuccessListener { info ->
                playManager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
            }
            playManager.registerListener { st ->
                if (st.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) playManager.completeUpdate()
            }
            return
        }
        val url = s.apkUrl ?: run { _state.value = State.Error("This release has no APK attached."); return }
        download(url)
    }

    private fun download(url: String) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "capitol-update.apk")
        val dm = context.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("Capitol update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
        )
        _state.value = State.Downloading(0f)
        // Poll progress for the UI.
        Thread {
            var done = false
            while (!done) {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> { done = true; downloadedApk = file; _state.value = State.ReadyToInstall }
                            DownloadManager.STATUS_FAILED -> { done = true; _state.value = State.Error("Download failed") }
                            else -> if (total > 0) _state.value = State.Downloading(got.toFloat() / total)
                        }
                    } else { done = true; _state.value = State.Error("Download cancelled") }
                }
                if (!done) Thread.sleep(400)
            }
        }.start()
    }

    /** Launches the system package installer for the downloaded APK. */
    fun install(activity: Activity) {
        val apk = downloadedApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() { if (_state.value !is State.Downloading) _state.value = State.Idle }

    companion object {
        /** Semantic-ish version comparison: "1.2.10" > "1.2.9". */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
