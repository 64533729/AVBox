package com.github.tvbox.osc.server

import android.content.Context

import com.github.tvbox.osc.util.LocalAddress

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
            LocalAddress.provider = { get().getAddress(true) }
        }
    }
}
