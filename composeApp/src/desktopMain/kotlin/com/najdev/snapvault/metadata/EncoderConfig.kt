package com.najdev.snapvault.metadata

// All ffmpeg encoder argument construction lives here as pure functions so the exact
// flags are pinned by unit tests — a silently wrong flag (e.g. NVENC's -cq being
// ignored without -rc vbr) degrades every video with no error to notice.
internal object EncoderConfig {

    // Probe order. Detection runs a real 1-frame test encode per candidate (see
    // DesktopMediaProcessor.detectHwEncoder) — `ffmpeg -encoders` output is useless for
    // this because it lists every encoder compiled into the build regardless of whether
    // the hardware/driver can actually run it (distro builds list NVENC on AMD machines).
    val CANDIDATES = listOf("h264_nvenc", "h264_videotoolbox", "h264_qsv", "h264_vaapi", "h264_amf")

    const val SOFTWARE = "libx264"

    fun isVaapi(encoder: String) = encoder == "h264_vaapi"

    // Overlay compose graph: scale the overlay (input 1) to the video's (input 0)
    // dimensions, composite, then force even dimensions — yuv420p/nv12 encoders reject
    // odd widths/heights ("width not divisible by 2"), and Snapchat media can be odd-sized.
    // shortest=1 stops the encode when the video ends (the PNG loops via -loop 1).
    const val FILTER_BASE =
        "[1:v][0:v]scale2ref[ovr][base];[base][ovr]overlay=0:0:shortest=1:format=auto," +
            "scale=trunc(iw/2)*2:trunc(ih/2)*2"

    // Codec + quality flags. Verified on real hardware where possible:
    // - libx264 + h264_vaapi: verified on Linux/AMD (2026-07-15).
    // - h264_nvenc: -cq only applies in VBR rate-control mode, and without -b:v 0 NVENC
    //   caps at its default average bitrate (~2 Mbps) — a classic silent-quality trap.
    //   Unverified on real NVIDIA hardware; the runtime probe gates it.
    // - h264_videotoolbox: -q:v (constant quality) is Apple Silicon only; on Intel Macs
    //   the probe fails and detection falls through to software.
    // - h264_qsv: ICQ mode via -global_quality; probe-gated, unverified.
    // - h264_amf: -qp_i/-qp_p only apply under -rc cqp; probe-gated, unverified.
    fun encodeArgs(encoder: String): List<String> = when (encoder) {
        "h264_nvenc" -> listOf(
            "-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "18", "-b:v", "0",
            "-pix_fmt", "yuv420p",
        )
        "h264_videotoolbox" -> listOf("-c:v", "h264_videotoolbox", "-q:v", "65", "-pix_fmt", "yuv420p")
        "h264_qsv" -> listOf("-c:v", "h264_qsv", "-global_quality", "18", "-pix_fmt", "nv12")
        "h264_vaapi" -> listOf("-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "18")
        "h264_amf" -> listOf(
            "-c:v", "h264_amf", "-rc", "cqp", "-qp_i", "18", "-qp_p", "18", "-pix_fmt", "yuv420p",
        )
        else -> listOf("-c:v", SOFTWARE, "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p")
    }

    // Device-init flags that must precede the inputs. Only VAAPI needs a device: its
    // encoder consumes GPU-memory frames, so the CPU filter output is uploaded via
    // format=nv12,hwupload. Every other candidate accepts CPU frames directly.
    fun initArgs(encoder: String): List<String> =
        if (isVaapi(encoder)) listOf("-init_hw_device", "vaapi=va", "-filter_hw_device", "va")
        else emptyList()

    // 1-frame synthetic encode used to test whether an encoder actually works on this
    // machine. Uses the exact encodeArgs the real encode would use, so unsupported
    // quality flags (e.g. -q:v on Intel VideoToolbox) fail here instead of at run time.
    fun probeArgs(ffmpegPath: String, encoder: String): List<String> {
        val upload = if (isVaapi(encoder)) listOf("-vf", "format=nv12,hwupload") else emptyList()
        return listOf(ffmpegPath, "-y") +
            initArgs(encoder) +
            listOf("-f", "lavfi", "-i", "color=black:size=320x240:rate=30") +
            upload +
            listOf("-frames:v", "1") +
            encodeArgs(encoder) +
            listOf("-f", "null", "-")
    }

    // Full argument list for combining a video with an overlay image.
    // encoder == null means software (libx264).
    fun combineArgs(
        ffmpegPath: String,
        encoder: String?,
        videoPath: String,
        overlayPath: String,
        outputPath: String,
    ): List<String> {
        val enc = encoder ?: SOFTWARE
        return if (isVaapi(enc)) {
            listOf(ffmpegPath, "-y") + initArgs(enc) + listOf(
                "-i", videoPath,
                "-loop", "1", "-i", overlayPath,
                "-filter_complex", "$FILTER_BASE,format=nv12,hwupload[vout]",
                "-map", "[vout]", "-map", "0:a?",
                "-c:a", "copy",
            ) + encodeArgs(enc) + outputPath
        } else {
            listOf(
                ffmpegPath, "-y",
                "-i", videoPath,
                "-loop", "1", "-i", overlayPath,
                "-filter_complex", FILTER_BASE,
                "-c:a", "copy",
            ) + encodeArgs(enc) + outputPath
        }
    }
}
