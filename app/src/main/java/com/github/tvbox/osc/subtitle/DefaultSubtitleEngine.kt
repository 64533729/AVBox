/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.SubtitleHelper

import java.io.File
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.TreeMap

import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * @author AveryZhong.
 */

class DefaultSubtitleEngine : SubtitleEngine {
    private var mWorkHandler: Handler? = null
    private var mSubtitles: MutableList<Subtitle>? = null
    private var mUIRenderTask: UIRenderTask? = null
    private var mMediaPlayer: AbstractPlayer? = null
    private var mOnSubtitlePreparedListener: SubtitleEngine.OnSubtitlePreparedListener? = null
    private var mOnSubtitleChangeListener: SubtitleEngine.OnSubtitleChangeListener? = null
    private var mergeSameTime: Boolean = false

    override fun bindToMediaPlayer(mediaPlayer: AbstractPlayer?) {
        mMediaPlayer = mediaPlayer
    }

    override fun setSubtitlePath(path: String?) {
        initWorkThread()
        reset()
        if (TextUtils.isEmpty(path)) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.")
            return
        }
        val p = path!!

        val loadSeq = mLoadSeq
        SubtitleLoader.loadSubtitle(p, object : SubtitleLoader.Callback {
            override fun onSuccess(result: SubtitleLoadSuccessResult?) {
                if (loadSeq != mLoadSeq) {
                    LOG.i("echo-sub drop stale: " + (if (p.startsWith("data:")) "inline" else p))
                    return
                }
                if (result == null) {
                    Log.d(TAG, "onSuccess: subtitleLoadSuccessResult is null.")
                    return
                }
                if (result.timedTextObject == null) {
                    Log.d(TAG, "onSuccess: timedTextObject is null.")
                    return
                }
                val captions = result.timedTextObject!!.captions
                if (captions == null) {
                    Log.d(TAG, "onSuccess: captions is null.")
                    return
                }
                mSubtitles = buildSubtitles(captions)
                LOG.i("echo-sub ready: lines=" + mSubtitles!!.size +
                        " src=" + (if (p.startsWith("data:")) "inline" else p))
                setSubtitleDelay(SubtitleHelper.getTimeDelay())
                notifyPrepared()

                val subtitlePath = result.subtitlePath
                if (subtitlePath!!.startsWith("http://") || subtitlePath.startsWith("https://")) {
                    val subtitleFileCacheDir = App.getInstance().cacheDir.absolutePath + "/zimu/"
                    val cacheDir = File(subtitleFileCacheDir)
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val subtitleFile = subtitleFileCacheDir + result.fileName
                    val cacheSubtitleFile = File(subtitleFile)
                    val writeResult = FileUtils.writeSimple(result.content!!.toByteArray(Charset.defaultCharset()), cacheSubtitleFile)
                    if (writeResult && playSubtitleCacheKey != null) {
                        AppGraph.cacheRepository.save(MD5.string2MD5(getPlaySubtitleCacheKey())!!, subtitleFile)
                    }
                } else {
                    AppGraph.cacheRepository.save(MD5.string2MD5(getPlaySubtitleCacheKey())!!, p)
                }
            }

            override fun onError(exception: Exception?) {
                if (loadSeq != mLoadSeq) return
                val e = exception!!
                Log.e(TAG, "onError: " + e.message)
                LOG.e("echo-sub fail: " + (if (p.startsWith("data:")) "inline" else p) + " -> " + e.message)
            }
        })
    }

    fun setMergeSameTime(mergeSameTime: Boolean) {
        this.mergeSameTime = mergeSameTime
    }

    private fun buildSubtitles(captions: TreeMap<Int, Subtitle>): MutableList<Subtitle> {
        val subtitles = ArrayList<Subtitle>()
        for (subtitle in captions.values) {
            if (mergeSameTime && !subtitles.isEmpty()) {
                val previous = subtitles[subtitles.size - 1]
                if (previous.start!!.mseconds == subtitle.start!!.mseconds &&
                    previous.end!!.mseconds == subtitle.end!!.mseconds) {
                    if (previous.lines == null) {
                        previous.lines = ArrayList()
                        previous.lines!!.add(previous)
                    }
                    previous.lines!!.add(subtitle)
                    if (!TextUtils.isEmpty(subtitle.content)) {
                        if (!TextUtils.isEmpty(previous.content)) previous.content += "\n"
                        previous.content += subtitle.content
                    }
                    continue
                }
            }
            if (mergeSameTime) {
                subtitle.lines = ArrayList()
                subtitle.lines!!.add(subtitle)
            }
            subtitles.add(subtitle)
        }
        return subtitles
    }

    override fun setSubtitleDelay(milliseconds: Int?) {
        if (milliseconds == 0) {
            return
        }
        if (mSubtitles == null || mSubtitles!!.isEmpty()) {
            return
        }
        val thisSubtitles = mSubtitles!!
        mSubtitles = null
        val ms = milliseconds!!
        for (i in thisSubtitles.indices) {
            val subtitle = thisSubtitles[i]
            val start = subtitle.start!!
            val end = subtitle.end!!
            start.mseconds += ms
            end.mseconds += ms
            if (start.mseconds <= 0) {
                start.mseconds = 0
            }
            if (end.mseconds <= 0) {
                end.mseconds = 0
            }
            subtitle.start = start
            subtitle.end = end
        }
        mSubtitles = thisSubtitles
    }

    private var playSubtitleCacheKey: String? = null

    /** 加载序号:切集/重载/销毁后,老请求的迟到结果必须丢弃(否则会覆盖当前歌词/字幕,表现为"出来得慢或串词") */
    private var mLoadSeq: Int = 0

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        playSubtitleCacheKey = cacheKey
    }

    override fun getPlaySubtitleCacheKey(): String? {
        return playSubtitleCacheKey
    }

    override fun reset() {
        stop()
        mSubtitles = null
        mUIRenderTask = null
        mLoadSeq++
    }

    override fun start() {
        Log.d(TAG, "start: ")
        if (mMediaPlayer == null) {
            Log.w(TAG, "MediaPlayer is not bind, You must bind MediaPlayer to " +
                    SubtitleEngine::class.java.simpleName +
                    " before start() method be called," +
                    " you can do this by call " +
                    "bindToMediaPlayer(MediaPlayer mediaPlayer) method.")
            return
        }
        stop()
        if (mWorkHandler != null) {
            mWorkHandler!!.sendEmptyMessageDelayed(MSG_REFRESH, REFRESH_INTERVAL.toLong())
        }
    }

    override fun pause() {
        stop()
    }

    override fun resume() {
        start()
    }

    override fun stop() {
        if (mWorkHandler != null) {
            mWorkHandler!!.removeMessages(MSG_REFRESH)
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy: ")
        stopWorkThread()
        reset()
    }

    private fun initWorkThread() {
        stopWorkThread()
        mWorkHandler = Handler(Looper.getMainLooper(), Handler.Callback { msg: Message ->
            try {
                var delay: Long = REFRESH_INTERVAL.toLong()
                if (mMediaPlayer != null && mMediaPlayer!!.isPlaying()) {
                    val position = mMediaPlayer!!.currentPosition
                    val subtitle = SubtitleFinder.find(position, mSubtitles)
                    notifyRefreshUI(subtitle)
                    if (subtitle != null) {
                        delay = subtitle.end!!.mseconds - position
                    }
                }
                if (mWorkHandler != null) {
                    mWorkHandler!!.sendEmptyMessageDelayed(MSG_REFRESH, delay)
                }
            } catch (e: Exception) {
                // 刷新循环单次失败不打断后续 tick
                LOG.d("DefaultSubtitleEngine", "subtitle refresh tick failed")
            }
            true
        })
    }

    private fun stopWorkThread() {
        if (mWorkHandler != null) {
            mWorkHandler!!.removeCallbacksAndMessages(null)
            mWorkHandler = null
        }
    }

    private fun notifyRefreshUI(subtitle: Subtitle?) {
        if (mUIRenderTask == null) {
            mUIRenderTask = UIRenderTask(mOnSubtitleChangeListener)
        }
        mUIRenderTask!!.execute(subtitle)
    }

    private fun notifyPrepared() {
        if (mOnSubtitlePreparedListener != null) {
            mOnSubtitlePreparedListener!!.onSubtitlePrepared(mSubtitles)
        }
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        mOnSubtitlePreparedListener = listener
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        mOnSubtitleChangeListener = listener
    }

    companion object {
        private const val TAG = "DefaultSubtitleEngine"
        private const val MSG_REFRESH = 0x888
        private const val REFRESH_INTERVAL = 100
    }
}
