package com.najdev.snapvault.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Pins the exact ffmpeg flags: a silently wrong flag degrades every encode with no error.
class EncoderConfigTest {

    private fun args(encoder: String) = EncoderConfig.encodeArgs(encoder)

    private fun List<String>.valueAfter(flag: String): String? {
        val i = indexOf(flag)
        return if (i in indices && i + 1 < size) this[i + 1] else null
    }

    @Test
    fun nvencUsesVbrConstantQualityWithUncappedBitrate() {
        val a = args("h264_nvenc")
        // -cq is only honored in VBR mode, and without -b:v 0 NVENC caps at its default
        // average bitrate (~2 Mbps).
        assertEquals("vbr", a.valueAfter("-rc"))
        assertEquals("18", a.valueAfter("-cq"))
        assertEquals("0", a.valueAfter("-b:v"))
    }

    @Test
    fun vaapiUsesExplicitCqpQuality() {
        val a = args("h264_vaapi")
        assertEquals("CQP", a.valueAfter("-rc_mode"))
        assertEquals("18", a.valueAfter("-qp"))
    }

    @Test
    fun amfQpFlagsRequireCqpRateControl() {
        val a = args("h264_amf")
        // -qp_i/-qp_p are ignored unless rate control is cqp.
        assertEquals("cqp", a.valueAfter("-rc"))
        assertEquals("18", a.valueAfter("-qp_i"))
        assertEquals("18", a.valueAfter("-qp_p"))
    }

    @Test
    fun softwareFallbackIsCrf18() {
        val a = args("anything-unknown")
        assertEquals("libx264", a.valueAfter("-c:v"))
        assertEquals("18", a.valueAfter("-crf"))
    }

    @Test
    fun filterChainForcesEvenDimensions() {
        // yuv420p/nv12 encoders reject odd widths/heights; Snapchat media can be odd-sized.
        assertTrue("scale=trunc(iw/2)*2:trunc(ih/2)*2" in EncoderConfig.FILTER_BASE)
    }

    @Test
    fun onlyVaapiGetsDeviceInit() {
        assertTrue(EncoderConfig.initArgs("h264_vaapi").isNotEmpty())
        for (enc in EncoderConfig.CANDIDATES - "h264_vaapi") {
            assertTrue(EncoderConfig.initArgs(enc).isEmpty(), "$enc must not get VAAPI device init")
        }
    }

    @Test
    fun probeUsesTheSameEncodeArgsAsRealEncodes() {
        for (enc in EncoderConfig.CANDIDATES) {
            val probe = EncoderConfig.probeArgs("ffmpeg", enc)
            val encode = EncoderConfig.encodeArgs(enc)
            // Every encode flag must appear in the probe so unsupported flags fail detection,
            // not the user's real files.
            assertTrue(
                probe.joinToString(" ").contains(encode.joinToString(" ")),
                "probe for $enc must embed its encode args"
            )
            assertEquals("-", probe.last())
            assertTrue("-frames:v" in probe, "probe for $enc must be a 1-frame encode")
        }
    }

    @Test
    fun vaapiCombineCopiesAudioAndMapsFilterOutput() {
        val a = EncoderConfig.combineArgs("ffmpeg", "h264_vaapi", "in.mp4", "ov.png", "out.mp4")
        assertEquals("copy", a.valueAfter("-c:a"))
        assertEquals("[vout]", a.valueAfter("-map"))
        assertTrue(a.joinToString(" ").contains("format=nv12,hwupload[vout]"))
    }

    @Test
    fun softwareCombineCopiesAudio() {
        val a = EncoderConfig.combineArgs("ffmpeg", null, "in.mp4", "ov.png", "out.mp4")
        assertEquals("copy", a.valueAfter("-c:a"))
        assertEquals("libx264", a.valueAfter("-c:v"))
        assertEquals("out.mp4", a.last())
    }
}
