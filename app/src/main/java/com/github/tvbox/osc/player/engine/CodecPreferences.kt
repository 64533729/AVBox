package com.github.tvbox.osc.player.engine

/**
 * 内核解码偏好(进程级静态位,跨内核实例):
 * 起播链路在创建内核前下发,渲染器工厂的选择器在选解码器时读取。
 *
 * <p>双栈并存期(M7a–M7c)旧 `osc.player.ExoPlayer` 的同名静态位委托到这里,
 * 保证新旧内核读到同一份偏好(存活内核不重选解码器,偏好变更靠"重建内核"生效)。
 */
object CodecPreferences {

    @Volatile
    private var preferSoftware = false

    /** true = 软解(系统软件解码器优先) */
    @JvmStatic
    fun setPreferSoftwareDecode(prefer: Boolean) {
        preferSoftware = prefer
    }

    @JvmStatic
    fun isPreferSoftwareDecode(): Boolean = preferSoftware
}
