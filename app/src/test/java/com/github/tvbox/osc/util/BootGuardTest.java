package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class BootGuardTest {

    private static final long LOAD = 5_000L;

    @Test
    public void startupCrash_disablesAfterSingleAttempt() {
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 28, true));
    }

    @Test
    public void startupCrash_atThresholdCounts() {
        assertTrue(BootGuard.crashedDuringStartup(LOAD + 10_000, LOAD));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 10_000, true));
    }

    @Test
    public void slowCrash_oneMillisecondPastThreshold_doesNotDisable() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD + 10_001, LOAD));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 10_001, false));
    }

    @Test
    public void slowCrash_singleAttemptNeverDisables() {
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 60_000, false));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 2, LOAD + 60_000, false));
    }

    @Test
    public void repeatedLoads_disableAtFallbackThreshold() {
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 3, LOAD + 60_000, false));
    }

    @Test
    public void noCrashNeverDisables() {
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 9, 0, true));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 9, -1, true));
    }

    @Test
    public void crashWithoutLoadingJarNeverDisables() {
        assertFalse(BootGuard.shouldDisable("", 4, LOAD + 1, true));
        assertFalse(BootGuard.shouldDisable(null, 4, LOAD + 1, true));
    }

    @Test
    public void crashBeforeLoadStartIsNotStartupCrash() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD - 1, LOAD));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD - 1, false));
    }

    @Test
    public void missingLoadStartFallsBackToCount() {
        assertFalse(BootGuard.crashedDuringStartup(LOAD + 1, 0));
        assertFalse(BootGuard.shouldDisable("/x/spider.jar", 1, LOAD + 1, false));
        assertFalse("阈值以下是 2", BootGuard.shouldDisable("/x/spider.jar", 2, LOAD + 1, false));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 3, LOAD + 1, false));
    }

    @Test
    public void midSessionSwitchCrash_countsAsLoadStageCrash() {
        long firstLoadOfProcess = LOAD;
        long switchLoad = firstLoadOfProcess + 30 * 60_000L;
        long crash = switchLoad + 28;
        assertTrue(BootGuard.crashedDuringStartup(crash, switchLoad));
        assertTrue(BootGuard.shouldDisable("/x/spider.jar", 1, crash, true));
        assertFalse("拿进程第一次装载当起点(旧写法)会判成 false —— 这正是要修掉的", BootGuard.crashedDuringStartup(crash, firstLoadOfProcess));
    }

    @Test
    public void addDisabledSource_dedups() {
        ArrayList<String> list = BootGuard.addDisabledSource(new ArrayList<String>(), "http://a/1");
        list = BootGuard.addDisabledSource(list, "http://a/1");
        assertEquals(1, list.size());
        assertEquals("http://a/1", list.get(0));
    }

    @Test
    public void addDisabledSource_ignoresBlank() {
        assertTrue(BootGuard.addDisabledSource(new ArrayList<String>(), "").isEmpty());
        assertTrue(BootGuard.addDisabledSource(new ArrayList<String>(), null).isEmpty());
    }

    @Test
    public void removeDisabledSource_removesMatch() {
        ArrayList<String> list = new ArrayList<>(Arrays.asList("http://a/1", "http://b/2"));
        list = BootGuard.removeDisabledSource(list, "http://a/1");
        assertEquals(1, list.size());
        assertEquals("http://b/2", list.get(0));
    }

    @Test
    public void removeDisabledSource_missingIsNoop() {
        ArrayList<String> list = new ArrayList<>(Arrays.asList("http://a/1"));
        assertEquals(1, BootGuard.removeDisabledSource(list, "http://z/9").size());
        assertEquals(1, BootGuard.removeDisabledSource(list, "").size());
    }

    @Test
    public void blacklist_toleratesNullList() {
        assertEquals(1, BootGuard.addDisabledSource(null, "http://a/1").size());
        assertTrue(BootGuard.removeDisabledSource(null, "http://a/1").isEmpty());
    }

    @Test
    public void uiCrashIsNotSourceRelated() {
        RuntimeException crash = new RuntimeException("ui bug");
        crash.setStackTrace(new StackTraceElement[]{
                frame("java.util.ArrayList$Itr", "checkForComodification"),
                frame("com.github.tvbox.osc.ui.page.SettingsPageKt", "SettingsPage$lambda$6$0$1$3"),
                frame("androidx.compose.runtime.internal.ComposableLambdaImpl", "invoke"),
                frame("com.github.tvbox.osc.ui.components.SettingsGroupKt", "SettingsCard"),
                frame("androidx.compose.runtime.Recomposer", "performRecompose"),
                frame("androidx.compose.ui.platform.AndroidUiFrameClock$withFrameNanos$2$callback$1", "doFrame"),
                frame("android.view.Choreographer$CallbackRecord", "run"),
                frame("android.os.Handler", "dispatchMessage"),
                frame("android.os.Looper", "loop"),
                frame("android.app.ActivityThread", "main"),
                frame("java.lang.reflect.Method", "invoke"),
                frame("com.android.internal.os.RuntimeInit$MethodAndArgsCaller", "run"),
                frame("com.android.internal.os.ZygoteInit", "main"),
        });
        assertFalse(BootGuard.looksSourceRelated(crash));
    }

    @Test
    public void frameworkCrashTailIsNotSourceRelated() {
        RuntimeException crash = new RuntimeException("ui bug");
        crash.setStackTrace(new StackTraceElement[]{
                frame("androidx.compose.runtime.Recomposer", "run"),
                frame("com.android.internal.os.RuntimeInit", "main"),
                frame("com.android.internal.os.ZygoteInit", "main"),
        });
        assertFalse(BootGuard.looksSourceRelated(crash));
    }

    @Test
    public void uiStackWithSpiderFrameIsStillSourceRelated() {
        RuntimeException crash = new RuntimeException("spider boom");
        crash.setStackTrace(new StackTraceElement[]{
                frame("androidx.compose.runtime.Recomposer", "run"),
                frame("com.github.tvbox.osc.ui.page.SettingsPageKt", "SettingsPage"),
                frame("com.github.catvod.spider.GoProxy", "<clinit>"),
                frame("com.android.internal.os.RuntimeInit", "main"),
        });
        assertTrue(BootGuard.looksSourceRelated(crash));
    }

    @Test
    public void spiderCrashIsSourceRelated() {
        RuntimeException crash = new RuntimeException("spider boom");
        crash.setStackTrace(new StackTraceElement[]{
                frame("com.github.catvod.spider.GoProxy", "<clinit>"),
                frame("com.github.catvod.crawler.JarLoader", "invokeInit"),
                frame("java.lang.Thread", "run"),
        });
        assertTrue(BootGuard.looksSourceRelated(crash));
    }

    @Test
    public void causeChainIsScanned() {
        RuntimeException inner = new RuntimeException("spider boom");
        inner.setStackTrace(new StackTraceElement[]{frame("com.github.catvod.spider.DouDou", "homeContent")});
        RuntimeException outer = new RuntimeException("wrapped", inner);
        outer.setStackTrace(new StackTraceElement[]{frame("com.github.tvbox.osc.ui.page.HomeViewModel", "loadHome")});
        assertTrue(BootGuard.looksSourceRelated(outer));
    }

    @Test
    public void suppressedChainIsScanned() {
        RuntimeException crash = new RuntimeException("ui bug");
        crash.setStackTrace(new StackTraceElement[]{frame("androidx.compose.runtime.ComposerImpl", "applyChanges")});
        RuntimeException suppressed = new RuntimeException("spider boom");
        suppressed.setStackTrace(new StackTraceElement[]{frame("com.github.catvod.spider.GoProxy", "init")});
        crash.addSuppressed(suppressed);
        assertTrue(BootGuard.looksSourceRelated(crash));
    }

    @Test
    public void unknownCrashCountsAsSourceRelated() {
        RuntimeException empty = new RuntimeException("no frames");
        empty.setStackTrace(new StackTraceElement[0]);
        assertTrue(BootGuard.looksSourceRelated(empty));
        assertTrue(BootGuard.looksSourceRelated(null));
    }

    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, className + ".java", 1);
    }
}
