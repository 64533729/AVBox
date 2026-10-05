package com.github.tvbox.osc.player.thirdparty

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import com.github.tvbox.osc.base.App
import java.net.URLEncoder
import java.util.HashMap

class MXPlayer {

    class MXPackageInfo(
        @JvmField val packageName: String,
        @JvmField val activityName: String,
    )

    companion object {
        const val TAG = "ThirdParty.MXPlayer"

        private const val PACKAGE_NAME_PRO = "com.mxtech.videoplayer.pro"
        private const val PACKAGE_NAME_AD = "com.mxtech.videoplayer.ad"
        private const val PLAYBACK_ACTIVITY_PRO = "com.mxtech.videoplayer.ActivityScreen"
        private const val PLAYBACK_ACTIVITY_AD = "com.mxtech.videoplayer.ad.ActivityScreen"

        private val PACKAGES = arrayOf(
            MXPackageInfo(PACKAGE_NAME_PRO, PLAYBACK_ACTIVITY_PRO),
            MXPackageInfo(PACKAGE_NAME_AD, PLAYBACK_ACTIVITY_AD),
        )

        /**
         * @return null if any MX Player packages not exist.
         */
        @JvmStatic
        fun getPackageInfo(): MXPackageInfo? {
            for (pkg in PACKAGES) {
                try {
                    val info = App.getInstance()!!.packageManager.getApplicationInfo(pkg.packageName, 0)
                    if (info.enabled) {
                        return pkg
                    } else {
                        Log.v(TAG, "MX Player package `${pkg.packageName}` is disabled.")
                    }
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.v(TAG, "MX Player package `${pkg.packageName}` does not exist.")
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

                if (subtitle != null && subtitle.isNotEmpty()) {
                    val parcels = arrayOfNulls<Parcelable>(1)
                    parcels[0] = Uri.parse(subtitle)
                    intent.putExtra("subs", parcels)
                    intent.putExtra("subs.enable", parcels)
                }
                activity.startActivity(intent)
                return true
            } catch (ex: Exception) {
                Log.e(TAG, "Can't run MX Player(Pro)", ex)
                return false
            }
        }
    }
}
