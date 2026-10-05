package com.github.tvbox.osc.player.engine

import android.os.Bundle
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlayerEngine 错误分类与 HLS 重试判定单测(纯策略函数) */
class PlayerEngineErrorTest {

    private fun error(code: Int): PlaybackException = PlaybackException("m", null, code, Bundle())

    // ==================== classifyError ====================

    @Test
    fun classifyError_ioAndParsingAreNetwork() {
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_IO_UNSPECIFIED"))
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertEquals(PlayerEngine.ERROR_KIND_NETWORK, PlayerEngine.classifyError("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
    }

    @Test
    fun classifyError_decoderIsDecode() {
        assertEquals(PlayerEngine.ERROR_KIND_DECODE, PlayerEngine.classifyError("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(PlayerEngine.ERROR_KIND_DECODE, PlayerEngine.classifyError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"))
    }

    @Test
    fun classifyError_unknownForOtherOrNull() {
        assertEquals(PlayerEngine.ERROR_KIND_UNKNOWN, PlayerEngine.classifyError("ERROR_CODE_UNSPECIFIED"))
        assertEquals(PlayerEngine.ERROR_KIND_UNKNOWN, PlayerEngine.classifyError(null))
    }

    // ==================== isParsingError(HLS 重试触发条件) ====================

    @Test
    fun isParsingError_ioUnspecifiedAndParsingCodes() {
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)))
        assertTrue(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)))
    }

    @Test
    fun isParsingError_otherErrorsNotRetried() {
        assertFalse(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
        assertFalse(PlayerEngine.isParsingError(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertFalse(PlayerEngine.isParsingError(null))
    }

    // ==================== CodecPreferences(软解偏好真值源) ====================

    @Test
    fun codecPreferences_roundTrip() {
        val original = CodecPreferences.isPreferSoftwareDecode()
        try {
            CodecPreferences.setPreferSoftwareDecode(true)
            assertTrue(CodecPreferences.isPreferSoftwareDecode())
            CodecPreferences.setPreferSoftwareDecode(false)
            assertFalse(CodecPreferences.isPreferSoftwareDecode())
        } finally {
            CodecPreferences.setPreferSoftwareDecode(original)
        }
    }
}
