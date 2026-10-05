package com.github.tvbox.osc.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.ImageView

import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest

import com.github.tvbox.osc.api.ApiConfig

import org.json.JSONException
import org.json.JSONObject

import java.util.Random

import android.util.LruCache
import me.jessyan.autosize.utils.AutoSizeUtils

object ImgUtil {
    // BugReview #25:无界静态缓存,每张占位图约 170KB(180x240 ARGB_8888),长列表浏览累积数百 MB;
    // 改 LruCache 限流,仅手动"清理缓存"才释放的问题同步消除
    private const val DRAWABLE_CACHE_MAX = 64
    private val drawableCache = LruCache<String, Drawable>(DRAWABLE_CACHE_MAX)
    @JvmField
    var defaultWidth = 244
    @JvmField
    var defaultHeight = 320

    class Style(@JvmField var ratio: Float, @JvmField var type: String)

    @JvmStatic
    fun isBase64Image(picUrl: String?): Boolean {
        return picUrl != null && picUrl.startsWith("data:image")
    }

    @JvmStatic
    fun initStyle(): Style? {
        val bStyle = ApiConfig.get().getHomeSourceBean().style
        if (!bStyle!!.isEmpty()) {
            try {
                val jsonObject = JSONObject(bStyle)
                return Style(jsonObject.getDouble("ratio").toFloat(), jsonObject.getString("type"))
            } catch (ignored: JSONException) {
                LOG.d("ImgUtil", "home style json invalid, use default grid")
            }
        }
        return null
    }

    @JvmStatic
    fun spanCountByStyle(style: Style, defaultCount: Int): Int {
        var spanCount = defaultCount
        if ("rect" == style.type) {
            if (style.ratio >= 1.7) {
                spanCount = 3
            } else if (style.ratio >= 1.3) {
                spanCount = 4
            }
        } else if ("list" == style.type) {
            spanCount = 1
        }
        return spanCount
    }

    @JvmStatic
    fun getStyleDefaultWidth(style: Style): Int {
        var styleDefaultWidth = 280
        if (style.ratio < 1) styleDefaultWidth = 214
        if (style.ratio > 1.7) styleDefaultWidth = 380
        return styleDefaultWidth
    }

    @JvmStatic
    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        val base64Data = base64Str.substring(base64Str.indexOf(",") + 1)
        val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }

    /**
     * 播放器封面专用加载入口:与 {@link #load} 的区别是 **①不画占位图/错误图、②把请求句柄交回调用方**。
     *
     * <p>① 为什么不画占位/错误图:那些 drawable 是给**卡片**(180×240)设计的,而播放器封面是
     * MATCH_PARENT 的整块画面区 —— 一旦被 `FIT_CENTER` 放大铺到全屏,就会呈现为一块与视频无关的
     * 浅色/白色矩形(真机实测:纯音频退到多任务时画面区变白、回前台又闪成透明)。
     * 播放器区只认「真正加载成功的图」,加载期间保持黑底(与视频未起播时一致)。
     *
     * <p>② 为什么要句柄:Coil 3 的 {@code ImageViewTarget} 走内部 {@code ViewTargetRequestManager},
     * 该管理器只认识自己注册过的请求、不暴露取消入口;而播放器封面每换集/换线都要换图,
     * 旧请求的迟到回调会把过期海报盖到新画面上。返回 {@link Disposable} 由
     * {@code MyVideoView.clearArtwork()} 负责 {@code dispose()}(Coil 的 dispose 会同步把
     * {@code isDisposed} 置真并取消 job,晚到的 onSuccess 不再落地)。
     *
     * @return 请求句柄;地址无效时返回 null(此时已直接设兜底图,无需取消)
     */
    @JvmStatic
    fun loadPlayerArtwork(url: String, view: ImageView): Disposable? {
        view.setScaleType(ImageView.ScaleType.FIT_CENTER)
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable("TVBox", 0, 0, 1f))
            return null
        }
        val request = ImageRequest.Builder(AppContextHolder.context()!!)
            .data(url)
            .target(ArtworkTarget(view))
            .build()
        return SingletonImageLoader.get(AppContextHolder.context()!!).enqueue(request)
    }

    /**
     * 播放器封面的 Target:**只有 onSuccess 才落地**,onStart(占位)/onError 一律不改视图。
     * 视图保持自身黑底 → 永远不会出现"加载中/加载失败的浅色矩形盖住视频"。
     * 失败也不落错误图:播放器区的正确兜底是黑底,而不是一张与内容无关的图。
     * (与 {@code PlaybackService.updateArtwork} 里取通知封面用的是同一种写法)
     */
    private class ArtworkTarget(private val view: ImageView) : coil3.target.Target {
        override fun onSuccess(image: coil3.Image) {
            view.setImageDrawable(image.asDrawable(view.resources))
        }
    }

    @JvmStatic
    fun getRandomColor(): Int {
        val random = Random()
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
    }

    @JvmStatic
    fun createTextDrawable(text: String): Drawable {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(AppContextHolder.context()!!, 10f).toFloat())
    }

    private fun createTextDrawable(textIn: String, widthIn: Int, heightIn: Int, cornerRadiusIn: Float): Drawable {
        var text = textIn
        var width = widthIn
        var height = heightIn
        var cornerRadius = cornerRadiusIn
        if (TextUtils.isEmpty(text)) text = "TVBox"
        if (width <= 0) width = 180
        if (height <= 0) height = 240
        if (cornerRadius <= 0) cornerRadius = 1f
        val key = text + "_" + width + "x" + height + "_" + cornerRadius.toInt()
        text = text.substring(0, 1)
        val cached = drawableCache.get(key)
        if (cached != null) return cached
        val randomColor = getRandomColor()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setColor(randomColor)
        paint.setStyle(Paint.Style.FILL)
        val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        paint.setColor(Color.WHITE)
        paint.setTextSize(60f)
        paint.setTextAlign(Paint.Align.CENTER)
        val fontMetrics = paint.getFontMetrics()
        val x = width / 2f
        val y = (height - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(text, x, y, paint)
        val drawable: Drawable = BitmapDrawable(AppContextHolder.context()!!.resources, bitmap)
        drawableCache.put(key, drawable)
        return drawable
    }

    @JvmStatic
    fun clearCache() {
        drawableCache.evictAll()
    }

    @JvmStatic
    fun clearMemoryCache() {
        clearCache()
        try {
            SingletonImageLoader.get(AppContextHolder.context()!!).memoryCache!!.clear()
            LOG.i("echo-img-clear-memory-cache")
        } catch (th: Throwable) {
            LOG.i("echo-img-clear-memory-cache-error:" + th.message)
        }
    }

    private fun isInvalidImageUrl(urlIn: String): Boolean {
        var url = urlIn
        if (TextUtils.isEmpty(url)) return true
        url = url.trim { it <= ' ' }
        if (TextUtils.isEmpty(url)) return true
        return hasEmptyProxyParam(url, "img")
    }

    private fun hasEmptyProxyParam(url: String, key: String): Boolean {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false
        val queryIndex = url.indexOf('?')
        val query = if (queryIndex >= 0) url.substring(queryIndex + 1) else url.substring("proxy://".length)
        val pairs = RegexUtils.getPattern("&").split(query)
        for (pair in pairs) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex < 0) continue
            if (key == pair.substring(0, eqIndex) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true
            }
        }
        return false
    }
}
