package com.github.tvbox.osc.player.thirdparty

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.github.tvbox.osc.base.App
import java.net.URLEncoder
import java.util.HashMap

class Kodi {

    class KodiPackageInfo(
        @JvmField val packageName: String,
        @JvmField val activityName: String,
    )

    companion object {
        const val TAG = "ThirdParty.Kodi"

        private const val PACKAGE_NAME = "org.xbmc.kodi"
        private const val PLAYBACK_ACTIVITY = "org.xbmc.kodi.Splash"

        private val PACKAGES = arrayOf(KodiPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

        /**
         * @return null if any Kodi packages not exist.
         */
        @JvmStatic
        fun getPackageInfo(): KodiPackageInfo? {
            for (pkg in PACKAGES) {
                try {
                    val info = App.getInstance()!!.packageManager.getApplicationInfo(pkg.packageName, 0)
                    if (info.enabled) {
                        return pkg
                    } else {
                        Log.v(TAG, "Kodi package `${pkg.packageName}` is disabled.")
                    }
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.v(TAG, "Kodi package `${pkg.packageName}` does not exist.")
                }
            }
            return null
        }

        @JvmStatic
        fun run(
            activity: Activity,
            url: String,
            title: String?,
            subtitle: String?,
            headers: HashMap<String, String>?,
        ): Boolean {
            val packageInfo = getPackageInfo() ?: return false

            try {
                var target = url
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setPackage(packageInfo.packageName)
                intent.setClassName(packageInfo.packageName, packageInfo.activityName)
                if (headers != null && headers.size > 0) {
                    target += "|"
                    var idx = 0
                    for (hk in headers.keys) {
                        target += hk + "=" + URLEncoder.encode(headers[hk], "UTF-8")
                        if (idx < headers.keys.size - 1) {
                            target += "&"
                        }
                        idx++
                    }
                }
                intent.setData(Uri.parse(target))
                intent.putExtra("title", title)
                intent.putExtra("name", title)

                if (subtitle != null && subtitle.isNotEmpty()) {
                    intent.putExtra("subs", subtitle)
                }
                activity.startActivity(intent)
                return true
            } catch (ex: Exception) {
                Log.e(TAG, "Can't run Kodi", ex)
                return false
            }
        }
    }
}
