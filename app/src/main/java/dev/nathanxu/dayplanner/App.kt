package dev.nathanxu.dayplanner

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.nathanxu.dayplanner.calendar.CalendarSource
import dev.nathanxu.dayplanner.data.AppDatabase
import dev.nathanxu.dayplanner.data.Repository
import dev.nathanxu.dayplanner.data.Settings
import dev.nathanxu.dayplanner.travel.TravelService
import java.security.MessageDigest

class App : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.build(this)
        repository = Repository(
            context = this,
            db = db,
            calendar = CalendarSource(this),
            travel = TravelService(db.travel(), BuildConfig.MAPS_API_KEY, packageName, signingSha1()),
            settings = Settings(this),
        )
    }

    private fun signingSha1(): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val sig = signatures?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()
}

val Context.repository: Repository get() = (applicationContext as App).repository
