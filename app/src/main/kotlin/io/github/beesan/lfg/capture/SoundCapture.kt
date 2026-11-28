// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.capture

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A rolling buffer of the game's sound, for sentence audio on cards. This is
 * the one part of the app that needs screen recording consent, which Android
 * asks for every session from version 14; it is opt-in and nothing else
 * depends on it. Only sound is taken: no picture is recorded.
 */
object SoundCapture {
    const val SAMPLE_RATE = 44_100

    /** How much sound is kept: longer than any line is read for. */
    const val SECONDS = 90

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val ring = SampleRing(SAMPLE_RATE * SECONDS)

    fun consentIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        // From Android 14 the dialog offers "a single app" first; the game's sound needs the whole screen's.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** Whether Android has been asked by itself since LFG last listened: once is enough, a "no" is not asked again. */
    @Volatile
    private var asked = false

    /**
     * Asks Android to let LFG listen, over the game on screen [displayId],
     * unless it is listening or has asked already. Called when a game comes to
     * the front, so nobody has to go to Settings to press Start; after a "no"
     * the Start there, and on the Lines page, is how to begin.
     */
    fun askOnce(context: Context, displayId: Int) {
        if (_running.value || asked) return
        asked = true
        val intent = Intent(context, io.github.beesan.lfg.ui.ListenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (e: Exception) {
            Log.w("LfgSound", "could not ask to listen", e)
        }
    }

    fun start(context: Context, resultCode: Int, data: Intent) {
        context.startForegroundService(
            Intent(context, SoundService::class.java).putExtra(SoundService.EXTRA_CODE, resultCode).putExtra(SoundService.EXTRA_DATA, data),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, SoundService::class.java))
    }

    /** The sound between two wall-clock times, mono 16-bit, or null when the buffer does not hold it. */
    fun clip(fromMs: Long, toMs: Long): ShortArray? = if (_running.value) ring.clip(fromMs, toMs, SAMPLE_RATE) else null

    internal fun onSamples(samples: ShortArray, count: Int, endMs: Long) = ring.write(samples, count, endMs)

    /** The newest [ms] of sound is heard again by a recorder begun beside the one before: what it gives goes over that. */
    internal fun rewind(ms: Long) = ring.rewind(ms, SAMPLE_RATE)

    internal fun setRunning(value: Boolean) {
        // Once it has listened, the next game opened after it stops may ask again.
        if (value) asked = false
        // Sound from before a break is not counted back from the new sound as if it ran on.
        if (value) ring.clear()
        _running.value = value
    }

    /**
     * Mono 16-bit samples, [rate] a second, as AAC in an .m4a file, which Anki
     * plays everywhere. Returns false when the device's encoder refuses.
     */
    fun encode(samples: ShortArray, to: File, rate: Int = SAMPLE_RATE): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(to.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var fed = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val n = minOf(buffer.remaining() / 2, samples.size - fed)
                        buffer.asShortBuffer().put(samples, fed, n)
                        val timeUs = fed * 1_000_000L / rate
                        fed += n
                        inputDone = fed >= samples.size
                        codec.queueInputBuffer(index, 0, n * 2, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            // Stopping the muxer is what writes the file's index: one that fails here would not play.
            muxer.stop()
            muxer.release()
            muxer = null
            true
        } catch (e: Exception) {
            Log.w("LfgSound", "encoding failed", e)
            to.delete()
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}

/**
 * The last so-many samples, with the wall-clock time of the newest, so a
 * stretch of time can be cut out afterwards. Free of Android, so it is tested
 * on the build machine.
 */
class SampleRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var written = 0L

    /** The most ever written: samples taken back by [rewind] were still written over what was there before them. */
    private var reached = 0L
    private var endMs = 0L

    @Synchronized
    fun clear() {
        written = 0L
        reached = 0L
        endMs = 0L
    }

    @Synchronized
    fun write(samples: ShortArray, count: Int, endMs: Long) {
        for (i in 0 until count) data[((written + i) % capacity).toInt()] = samples[i]
        written += count
        reached = maxOf(reached, written)
        this.endMs = endMs
    }

    /**
     * Takes back the newest [ms] of sound, as much of it as is held, so that what is written next goes in its
     * place: a recorder begun beside the one before has heard that stretch too, and gives it again.
     */
    @Synchronized
    fun rewind(ms: Long, sampleRate: Int) {
        val back = minOf(ms * sampleRate / 1000, written - oldest())
        if (back <= 0) return
        written -= back
        endMs -= back * 1000 / sampleRate
    }

    /** The first sample still held: one taken back did not bring back what it was written over. */
    private fun oldest() = maxOf(0L, reached - capacity)

    /** Samples for `[fromMs, toMs)`, clamped to what is held; null when none of it is. */
    @Synchronized
    fun clip(fromMs: Long, toMs: Long, sampleRate: Int): ShortArray? {
        if (written == 0L || toMs <= fromMs) return null
        // Sample index, counted from the first ever written, of a wall-clock time.
        fun indexAt(ms: Long) = written - (endMs - ms) * sampleRate / 1000
        val from = indexAt(fromMs).coerceIn(oldest(), written)
        val to = indexAt(toMs).coerceIn(oldest(), written)
        if (to <= from) return null
        return ShortArray((to - from).toInt()) { data[((from + it) % capacity).toInt()] }
    }
}

/** Holds the screen recording consent and reads the game's sound into [SoundCapture]. */
class SoundService : Service() {
    private var projection: MediaProjection? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var reading = false

    /** A recorder and when it began to record (uptime): what it heard before the change-over to it, the one before gave already. */
    private class Listening(val record: AudioRecord, val startedAt: Long)

    /** A recorder begun since the one being read, for the reading thread to change over to. */
    private val fresh = AtomicReference<Listening?>()
    private val main = Handler(Looper.getMainLooper())
    private var listenedAt = 0L

    /**
     * On some devices (the AYN Thor) a sound that starts while a recorder is
     * already listening reaches it as silence, and only a recorder begun after
     * the sound hears it. So each time one more thing is playing, listening
     * begins again.
     */
    private val players = object : AudioManager.AudioPlaybackCallback() {
        private var playing = 0

        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (configs.size > playing) {
                main.removeCallbacks(listenAgain)
                main.postAtTime(listenAgain, listenedAt + RELISTEN_EVERY_MS)
            }
            playing = configs.size
        }
    }

    /** Begins a new recorder beside the one being read, which goes on being read until the new one is recording. */
    private val listenAgain = Runnable {
        val granted = projection ?: return@Runnable
        listenedAt = SystemClock.uptimeMillis()
        thread(name = "lfg-sound-again") {
            try {
                fresh.getAndSet(listen(granted))?.record?.release()
                // The service ended while this one was being made: nothing is left to read it.
                if (!reading) fresh.getAndSet(null)?.record?.release()
            } catch (e: Exception) {
                Log.w(TAG, "could not listen again", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires the notification to be up before the consent is used.
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        // Already listening (two screens may each have asked): the second consent is not needed.
        if (projection != null) return START_NOT_STICKY
        try {
            val granted = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                ?: error("Android gave no screen recording consent.")
            projection = granted
            granted.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, main)

            val first = listen(granted).record
            record = first
            listenedAt = SystemClock.uptimeMillis()
            reading = true
            SoundCapture.setRunning(true)
            getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(players, main)
            thread(name = "lfg-sound") {
                val buffer = ShortArray(4096)
                var current = first
                while (reading) {
                    fresh.getAndSet(null)?.let { next ->
                        // What the new one has heard since it began, the old one gave already: its first samples go over that.
                        val heard = SystemClock.uptimeMillis() - next.startedAt
                        val old = current
                        current = next.record
                        record = next.record
                        runCatching { old.stop() }
                        runCatching { old.release() }
                        SoundCapture.rewind(heard)
                    }
                    val n = current.read(buffer, 0, buffer.size)
                    if (n > 0) SoundCapture.onSamples(buffer, n, System.currentTimeMillis()) else if (n < 0) break
                }
                // The recorder died (the sound server restarted): the service ends, so LFG stops saying it listens and Start comes back.
                if (reading) main.post { stopSelf() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not start capturing sound", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** A recorder of everything playing as game or media sound, already recording, and when it began. */
    @SuppressLint("MissingPermission")
    private fun listen(granted: MediaProjection): Listening {
        val config = AudioPlaybackCaptureConfiguration.Builder(granted)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // LFG's own sound (a word's recording, Read aloud, Hear) is no part of the game's line.
            .excludeUid(android.os.Process.myUid())
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SoundCapture.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(SoundCapture.SAMPLE_RATE * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record.startRecording()
        return Listening(record, SystemClock.uptimeMillis())
    }

    override fun onDestroy() {
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(players) }
        main.removeCallbacks(listenAgain)
        reading = false
        runCatching { fresh.getAndSet(null)?.record?.release() }
        SoundCapture.setRunning(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sentence audio", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LFG is listening to game sound")
            .setContentText("For sentence audio on cards. Only sound is kept, for 90 seconds.")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val TAG = "LfgSound"
        private const val CHANNEL = "sound"
        private const val NOTIFICATION = 1

        /** Listening begins again no more often than this, however many sounds start. */
        private const val RELISTEN_EVERY_MS = 1000L
    }
}
