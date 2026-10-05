package com.github.tvbox.osc.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.regex.Pattern


/**
 * 作者：By hdy
 * 日期：On 2018/11/1
 * 时间：At 19:17
 */
object LocalIPAddress {
    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun getLocalIPAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.getConnectionInfo().getIpAddress()
        if (ipAddress == 0) {
            try {
                val enumerationNi = NetworkInterface.getNetworkInterfaces()
                while (enumerationNi.hasMoreElements()) {
                    val networkInterface = enumerationNi.nextElement()
                    val interfaceName = networkInterface.getDisplayName()
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        val enumIpAddr = networkInterface.getInetAddresses()
                        while (enumIpAddr.hasMoreElements()) {
                            val inetAddress = enumIpAddr.nextElement()
                            if (!inetAddress.isLoopbackAddress() && inetAddress is Inet4Address) {
                                return inetAddress.getHostAddress()
                            }
                        }
                    }
                }
            } catch (e: SocketException) {
                LOG.e("LocalIPAddress", e)
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress and 0xff), (ipAddress shr 8 and 0xff), (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff))
        }
        return "127.0.0.1"
    }
    @JvmStatic
    fun getIP(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            //判断wifi是否开启
            var wifiInfo: WifiInfo? = null
            if (wifiManager != null) {
                wifiInfo = wifiManager.getConnectionInfo()
            }
            var ipAddress = 0
            if (wifiInfo != null) {
                ipAddress = wifiInfo.getIpAddress()
            }
            return intToIp(ipAddress)
        } catch (e: Exception) {
            LOG.e("LocalIPAddress", e)
            try {
                return getLocalIPAddress()
            } catch (e1: Exception) {
                LOG.e("LocalIPAddress", e1)
            }
        }
        return "127.0.0.1"
    }

    @JvmStatic
    fun getLocalIPAddress(): String {
        try {
            val mEnumeration = NetworkInterface.getNetworkInterfaces()
            while (mEnumeration.hasMoreElements()) {
                val intf = mEnumeration.nextElement()
                val enumIPAddr = intf.getInetAddresses()
                while (enumIPAddr.hasMoreElements()) {
                    val inetAddress = enumIPAddr.nextElement()
                    // 如果不是回环地址
                    if (!inetAddress.isLoopbackAddress()) {
                        // 直接返回本地IP地址
                        return inetAddress.getHostAddress()
                    }
                }
            }
        } catch (ex: SocketException) {
            System.err.print("error")
        }
        return "127.0.0.1"
    }


    private fun intToIp(i: Int): String {
        return (i and 0xFF).toString() + "." +
                ((i shr 8) and 0xFF) + "." +
                ((i shr 16) and 0xFF) + "." +
                (i shr 24 and 0xFF)
    }

    /**
     * Ipv4 address check.
     */
    private val IPV4_PATTERN = Pattern.compile(
        "^(" + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
                "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    )
    private val IPV6_PATTERN = Pattern.compile("^\\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:)(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))(%.+)?\\s*$")

    /**
     * Check if valid IPV4 address.
     *
     * @param input the address string to check for validity.
     * @return True if the input parameter is a valid IPv4 address.
     */
    @JvmStatic
    fun isIPv4Address(input: String): Boolean {
        return IPV4_PATTERN.matcher(input).matches()
    }

    @JvmStatic
    fun isIPv6Address(str: String?): Boolean {
        return str != null && IPV6_PATTERN.matcher(str).matches()
    }

    @JvmStatic
    fun isIPAddress(str: String): Boolean {
        return isIPv4Address(str) || isIPv6Address(str)
    }

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        var hasWifoCon = false
        var hasMobileCon = false

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netInfos = cm.getAllNetworkInfo()
        for (net in netInfos) {

            val type = net.getTypeName()
            if (type.equals("WIFI", ignoreCase = true)) {
                if (net.isConnected) {
                    hasWifoCon = true
                }
            }

            if (type.equals("MOBILE", ignoreCase = true)) {
                if (net.isConnected) {
                    hasMobileCon = true
                }
            }
        }
        return hasWifoCon || hasMobileCon

    }
}
