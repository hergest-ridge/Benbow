package app.benbow.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.benbow.app.AppUpdater
import app.benbow.app.InstallOutcome
import app.benbow.client.AppDownload
import app.benbow.client.BenbowApi
import app.benbow.core.api.AndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Updating Benbow from its own server (ADR-042, as built 7.19 U2). [AppDownload] fetches the APK and checks it against
 * what the server said; here it must also be this app (the same id), signed by the same key and newer, or it is deleted
 * and never handed on. It goes to Android's PackageInstaller in one session; when Android wants the owner's yes it shows
 * its own dialog, and on success this process ends and [UpdatedReceiver] says so.
 */
class AndroidAppUpdater(private val context: Context) : AppUpdater {
    override val packageName: String = context.packageName
    override val versionCode: Int = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    override fun allowInstalls() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun prepare(app: AndroidApp, api: () -> BenbowApi, progress: (Long, Long) -> Unit): String {
        val file = AppDownload(File(context.cacheDir, "updates"), api).fetch(app, progress)
        refusal(file)?.let { reason ->
            file.delete()
            error(reason)
        }
        return file.path
    }

    /** Null when [file] is this app, signed by the same key, newer than this build; else why not. */
    private fun refusal(file: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "это не приложение Android"
        if (archive.packageName != packageName) return "это другое приложение (${archive.packageName})"
        if (archive.longVersionCode <= versionCode) return "это не более новая версия"
        val theirs = archive.signingInfo?.apkContentsSigners?.map(::digest)?.toSet().orEmpty()
        val own = pm.getPackageInfo(packageName, flags).signingInfo?.apkContentsSigners?.map(::digest)?.toSet().orEmpty()
        if (theirs.isEmpty() || theirs != own) return "файл подписан не тем ключом"
        return null
    }

    override suspend fun install(file: String, quiet: Boolean, outcome: (InstallOutcome) -> Unit) = withContext(Dispatchers.IO) {
        InstallReceiver.outcome = outcome
        val apk = File(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Asked for by the owner's tap: Android skips its own dialog when it may (Android 12+, the app updating itself).
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("benbow.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_QUIET, quiet),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // the installer adds its result
            )
            session.commit(status.intentSender)
        }
    }

    override fun announce(app: AndroidApp) = notify(
        context,
        NOTIFICATION_READY,
        context.getString(R.string.update_ready_title, app.versionName),
        context.getString(R.string.update_ready_text),
        Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE),
    )

    private fun digest(signature: Signature) = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

    companion object {
        const val ACTION_UPDATE = "app.benbow.UPDATE"
        const val CHANNEL = "updates"
        const val NOTIFICATION_READY = 7_001
        private const val NOTIFICATION_DONE = 7_002

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** The update after it went in: the «ready» note gone, a «done» one that opens the new app. */
        fun updated(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY)
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            notify(
                context,
                NOTIFICATION_DONE,
                context.getString(R.string.update_done_title, version),
                context.getString(R.string.update_done_text),
                Intent(context, MainActivity::class.java),
            )
        }

        private fun notify(context: Context, id: Int, title: String, text: String, open: Intent) {
            createChannel(context)
            val tap = PendingIntent.getActivity(
                context,
                id,
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        }
    }
}

/**
 * What Android says of an install: its own dialog when it wants the owner's yes (after a tap), or why it refused. A quiet
 * install (U3) never puts a dialog over what the owner is doing: Android's question drops the session, and the update
 * waits for «Обновить».
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> if (intent.getBooleanExtra(EXTRA_QUIET, false)) {
                val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
                outcome?.invoke(InstallOutcome.NeedsYou)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            // this process ends with the update

            else -> outcome?.invoke(
                InstallOutcome.Refused(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused ($status)"),
            )
        }
    }

    companion object {
        const val EXTRA_QUIET = "app.benbow.QUIET"

        /** Where the answer goes: the update's state, set by the install it answers. */
        @Volatile
        var outcome: ((InstallOutcome) -> Unit)? = null
    }
}

/** After Benbow was replaced by its update: the notifications say so. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AndroidAppUpdater.updated(context)
    }
}
