package com.github.tvbox.osc.player.host

/**
 * 渲染宿主测量算法(M7b):逐行为等价移植 doikki `MeasureHelper.doMeasure`(六种画面比例 + 旋转交换宽高)。
 *
 * <p>与旧实现的唯一表达差异:旧实现交换的是 MeasureSpec 再取 size,本实现直接交换已解析的 size
 * (旋转交换发生在取 size 之后、比例计算之前;MATCH_PARENT 分支按旧语义返回 spec,旋转时同样交换 spec)。
 */
object RenderMeasure {

    const val SCALE_DEFAULT = 0
    const val SCALE_16_9 = 1
    const val SCALE_4_3 = 2
    const val SCALE_MATCH_PARENT = 3
    const val SCALE_ORIGINAL = 4
    const val SCALE_CENTER_CROP = 5

    /**
     * @param widthSpec  原始宽度 MeasureSpec(仅 MATCH_PARENT 分支原样返回)
     * @param heightSpec 原始高度 MeasureSpec(同上)
     * @param width      已解析的可用宽度(MeasureSpec.getSize)
     * @param height     已解析的可用高度(MeasureSpec.getSize)
     */
    fun measure(
        widthSpec: Int,
        heightSpec: Int,
        width: Int,
        height: Int,
        scaleType: Int,
        videoWidth: Int,
        videoHeight: Int,
        videoRotationDegree: Int,
    ): IntArray {
        var w = width
        var h = height
        if (videoRotationDegree == 90 || videoRotationDegree == 270) {
            val swapped = w
            w = h
            h = swapped
        }
        if (videoWidth == 0 || videoHeight == 0) {
            return intArrayOf(w, h)
        }
        when (scaleType) {
            SCALE_ORIGINAL -> {
                w = videoWidth
                h = videoHeight
            }
            SCALE_16_9 -> {
                if (h > w / 16 * 9) {
                    h = w / 16 * 9
                } else {
                    w = h / 9 * 16
                }
            }
            SCALE_4_3 -> {
                if (h > w / 4 * 3) {
                    h = w / 4 * 3
                } else {
                    w = h / 3 * 4
                }
            }
            SCALE_MATCH_PARENT -> {
                // 旧实现交换的是 MeasureSpec ⇒ 旋转时返回的是交换后的 spec(逐字等价,含高位 mode 位)
                return if (videoRotationDegree == 90 || videoRotationDegree == 270) {
                    intArrayOf(heightSpec, widthSpec)
                } else {
                    intArrayOf(widthSpec, heightSpec)
                }
            }
            SCALE_CENTER_CROP -> {
                if (videoWidth * h > w * videoHeight) {
                    w = h * videoWidth / videoHeight
                } else {
                    h = w * videoHeight / videoWidth
                }
            }
            else -> {
                if (videoWidth * h < w * videoHeight) {
                    w = h * videoWidth / videoHeight
                } else if (videoWidth * h > w * videoHeight) {
                    h = w * videoHeight / videoWidth
                }
            }
        }
        return intArrayOf(w, h)
    }
}
