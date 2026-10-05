package com.github.tvbox.osc.server

import android.content.Context

import java.io.IOException

class ControlManager private constructor() {
    private var mServer: RemoteServer? = null

    fun getAddress(local: Boolean): String {
        if (mServer?.isStarting() != true) {
            startServer()
        }
        val server = mServer ?: return ""
        if (!server.isStarting()) return ""
        return if (local) server.getLoadAddress() else server.getServerAddress()
    }

    fun startServer() {
        if (mServer?.isStarting() == true) {
            return
        }
        do {
            val server = RemoteServer(RemoteServer.serverPort, mContext!!)
            mServer = server
            try {
                server.start()
                com.github.catvod.Proxy.set(RemoteServer.serverPort)
                break
            } catch (ex: IOException) {
                RemoteServer.serverPort++
                server.stop()
            }
        } while (RemoteServer.serverPort < 9999)
    }

    fun stopServer() {
        mServer?.let {
            if (it.isStarting()) it.stop()
        }
        mServer = null
    }

    companion object {
        // volatile:DCL 单例必须(2026-09-12 修复 Bug)。jar 加载线程(JarLoader.injectProxyPort)与主线程
        // (AppBootstrap/MainScreen)都会调 get()
        @Volatile
        private var instance: ControlManager? = null

        @JvmField
        var mContext: Context? = null

        @JvmStatic
        fun get(): ControlManager {
            if (instance == null) {
                synchronized(ControlManager::class.java) {
                    if (instance == null) {
                        instance = ControlManager()
                    }
                }
            }
            return instance!!
        }

        @JvmStatic
        fun init(context: Context) {
            mContext = context
        }
    }
}
