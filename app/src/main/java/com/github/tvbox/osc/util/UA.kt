package com.github.tvbox.osc.util

import java.io.DataInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Random

object UA {

    @JvmStatic
    fun random(): String {
        try {
            val fis: InputStream = AppContextHolder.context()!!.assets.open("ua.db")
            val dis = DataInputStream(fis)
            val len = dis.readInt()
            val random = Random().nextInt(len)
            dis.skipBytes(random * 4)
            val offset = dis.readInt()
            dis.skipBytes((len - 1 - random) * 4 + offset)
            val s = dis.readUTF()
            return s
        } catch (e: FileNotFoundException) {
            LOG.e("UA", e)
        } catch (e: IOException) {
            LOG.e("UA", e)
        }
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36"
    }
}
