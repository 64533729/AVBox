package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移引入的承重不变量:`SortLoader.hasActionVideo` 的**元素判空**必须还在。
 *
 * Java 原文是 `for (Movie.Video v : videos) if (v != null && v.action != null)` —— 载荷里
 * (JSON 数组含 null 元素)这句是真分支。迁 Kotlin 后容器是 `MutableList<Movie.Video>`、
 * 元素类型非空,若照抄成 `for (v in videos) if (v.action != null)`,编译器会把判空折掉,
 * null 元素直接在取 `action` 时 NPE。故入参声明为 `List<Movie.Video?>?`(见规范 §7.3 第 10 条)。
 *
 * 用反射读私有方法:它是取数实现内部细节,不为了测试开成 public(同 [SourceViewModelWiringTest])。
 */
class SortLoaderActionVideoTest {

    @Suppress("UNCHECKED_CAST")
    private fun field(target: Any, name: String): Any {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as Any
    }

    private fun hasActionVideo(sortLoader: Any, videos: List<Movie.Video?>): Boolean {
        val m = sortLoader.javaClass.getDeclaredMethod("hasActionVideo", List::class.java)
        m.isAccessible = true
        return m.invoke(sortLoader, videos) as Boolean
    }

    private fun sortLoader(): Any = field(SourceViewModel(), "sortLoader")

    private fun video(action: String?): Movie.Video {
        val video = Movie.Video()
        video.action = action
        return video
    }

    @Test
    fun nullElementIsSkippedInsteadOfCrashing() {
        // 含 null 元素:判空还在 ⇒ 跳过它继续看后面的元素(折掉的话这里直接 NPE)
        assertFalse("null 元素应被跳过", hasActionVideo(sortLoader(), listOf(null, video(null))))
        assertTrue("null 元素之后的 action 仍要认出来", hasActionVideo(sortLoader(), listOf(null, video("action"))))
    }

    @Test
    fun actionIsDetectedOnlyWhenPresent() {
        assertFalse(hasActionVideo(sortLoader(), listOf(video(null))))
        assertTrue(hasActionVideo(sortLoader(), listOf(video("action"))))
    }
}
