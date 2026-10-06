package com.github.tvbox.osc.player.thirdparty

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.github.tvbox.osc.base.App

class VlcPlayer {

    class VlcPackageInfo(
        @JvmField val packageName: String,
        @JvmField val activityName: String,
    )

    companion object {
        const val TAG = "ThirdParty.VLC"

        private const val PACKAGE_NAME = "org.videolan.vlc"
        private const val PLAYBACK_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"

        private val PACKAGES = arrayOf(VlcPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

        @JvmStatic
        fun getPackageInfo(): VlcPackageInfo? {
            for (pkg in PACKAGES) {
                try {
                    val info = App.getInstance()!!.packageManager.getApplicationInfo(pkg.packageName, 0)
                    if (info.enabled) {
                        return pkg
                    } else {
                        Log.v(TAG, "VLC Player package `${pkg.packageName}` is disabled.")
                    }
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.v(TAG, "VLC Player package `${pkg.packageName}` does not exist.")
                }
            }
            return null
        }

        @JvmStatic
        fun run(activity: Activity, url: String, title: String?, subtitle: String?, progress: Long): Boolean {
            val packageInfo = getPackageInfo() ?: return false

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setPackage(packageInfo.packageName)
            intent.setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
            intent.putExtra("title", title)

            if (subtitle != null && subtitle.isNotEmpty()) {
                intent.putExtra("subtitles_location", subtitle)
            }

            if (progress > 0) {
                intent.putExtra("from_start", false)
                intent.putExtra("position", progress)
            }

            try {
                activity.startActivity(intent)
                return true
            } catch (ex: ActivityNotFoundException) {
                Log.e(TAG, "Can't run VLC Player(Pro)", ex)
                return false
            }
        }
    }
}
