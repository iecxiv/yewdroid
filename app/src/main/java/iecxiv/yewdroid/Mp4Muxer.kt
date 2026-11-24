package iecxiv.yewdroid

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Muxes compatible local video/audio tracks without re-encoding. Blocking: call off main. */
object Mp4Muxer {
    @JvmStatic
    fun merge(videoPath: String, audioPath: String, outputPath: String) {
        val video = File(videoPath).canonicalFile
        val audio = File(audioPath).canonicalFile
        val output = File(outputPath).canonicalFile
        require(video.isFile && audio.isFile) { "Missing input tracks" }
        require(output != video && output != audio) { "Output must differ from inputs" }
        require(!output.exists()) { "Output already exists" }
        val parent = requireNotNull(output.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create output directory" }
        val temp = File.createTempFile("mux-", ".mp4", parent)
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ve.setDataSource(video.path)
            ae.setDataSource(audio.path)
            fun select(ex: MediaExtractor, mime: String): MediaFormat {
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME) == mime) {
                        ex.selectTrack(i)
                        return format
                    }
                }
                error("Required track not found: $mime")
            }
            val vf = select(ve, "video/avc")
            val af = select(ae, "audio/mp4a-latm")
            val m = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vt = m.addTrack(vf)
            val at = m.addTrack(af)
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) {
                m.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
            }
            m.start()
            started = true
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoSamples = 0
            var audioSamples = 0
            while (ve.sampleTime >= 0 || ae.sampleTime >= 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val isVideo = ae.sampleTime < 0 || (ve.sampleTime >= 0 && ve.sampleTime <= ae.sampleTime)
                val ex = if (isVideo) ve else ae
                val size = ex.sampleSize
                require(size in 0..Int.MAX_VALUE.toLong()) { "Invalid sample size" }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val count = ex.readSampleData(buffer, 0)
                check(count >= 0) { "Cannot read sample" }
                check(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted track unsupported" }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, count, ex.sampleTime, flags)
                m.writeSampleData(if (isVideo) vt else at, buffer, info)
                if (isVideo) videoSamples++ else audioSamples++
                ex.advance()
            }
            check(videoSamples > 0 && audioSamples > 0) { "Empty track" }
            m.stop()
            started = false
            m.release()
            muxer = null
            check(temp.length() > 0) { "Empty output" }
            check(temp.renameTo(output)) { "Cannot finalize MP4" }
        } finally {
            try {
                muxer?.let { m ->
                    if (started) runCatching { m.stop() }
                    runCatching { m.release() }
                }
            } finally {
                runCatching { ve.release() }
                runCatching { ae.release() }
                temp.delete()
            }
        }
    }
}
