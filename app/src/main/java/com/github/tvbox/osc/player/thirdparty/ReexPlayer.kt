package com.github.tvbox.osc.player.thirdparty

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LOG
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap

class ReexPlayer {

    class ReexPackageInfo(
        @JvmField val packageName: String,
        @JvmField val activityName: String,
    )

    companion object {
        const val TAG = "ThirdParty.Reex"

        private const val PACKAGE_NAME = "xyz.re.player.ex"
        private const val PLAYBACK_ACTIVITY = "xyz.re.player.ex.MainActivity"

        private val PACKAGES = arrayOf(ReexPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

        @JvmStatic
        fun getPackageInfo(): ReexPackageInfo? {
            for (pkg in PACKAGES) {
                try {
                    val info = App.getInstance()!!.packageManager.getApplicationInfo(pkg.packageName, 0)
                    if (info.enabled) {
                        return pkg
                    } else {
                        Log.v(TAG, "Reex Player package `${pkg.packageName}` is disabled.")
                    }
                } catch (ex: PackageManager.NameNotFoundException) {
                    Log.v(TAG, "Reex Player package `${pkg.packageName}` does not exist.")
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

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setPackage(packageInfo.packageName)
            intent.setComponent(ComponentName(packageInfo.packageName, packageInfo.activityName))
            intent.setData(Uri.parse(url))
            intent.putExtra("title", title)
            intent.putExtra("name", title)
            intent.putExtra("reex.extra.title", title)
            if (headers != null && headers.size > 0) {
                try {
                    val json = JSONObject()
                    for (key in headers.keys) {
                        json.put(key, headers[key]!!.trim { it <= ' ' })
                    }
                    intent.putExtra("reex.extra.http_header", json.toString())
                } catch (e: JSONException) {
                    LOG.e("ReexPlayer", e)
                }
            }
            if (subtitle != null && subtitle.isNotEmpty()) {
                intent.putExtra("reex.extra.subtitle", subtitle)
            }
            try {
                activity.startActivity(intent)
                return true
            } catch (ex: ActivityNotFoundException) {
                Log.e(TAG, "Can't run Reex Player(Pro)", ex)
                return false
            }
        }
    }
}
