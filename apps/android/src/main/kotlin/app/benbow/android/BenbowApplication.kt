package app.benbow.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.sqlite.db.SupportSQLiteDatabase
import app.benbow.app.AppServices
import app.benbow.client.db.BenbowDb
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class BenbowApplication : Application() {
    /** One per process: the local DB, outbox and sync outlive activities. */
    val services: AppServices by lazy {
        val driver = AndroidSqliteDriver(
            schema = BenbowDb.Schema,
            context = this,
            name = "benbow.db",
            callback = object : AndroidSqliteDriver.Callback(BenbowDb.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                    // Drafts and the outbox exist only on this device until synced: no lost commits on power loss.
                    db.query("PRAGMA synchronous = FULL").close()
                }
            },
        )
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        AppServices(driver, platform = "android", deviceName = name)
    }
}
