package com.notesis

import android.media.MediaPlayer

const val PLAY_STOP = 0
const val PLAY_NEXT = 1
const val PLAY_LOOP_ONE = 2

/** The speeds the player steps through, in order. */
val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** How far the skip buttons jump. */
const val SKIP_MS = 15_000

/** The speed after [current], wrapping from the fastest back to the slowest. */
fun nextPlaybackSpeed(current: Float): Float {
    val at = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return PLAYBACK_SPEEDS[(at + 1).mod(PLAYBACK_SPEEDS.size)]
}

/** 1, 1.25, 0.5 - no trailing zeros. */
fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')

/**
 * Sets the speed with the pitch left alone. Setting it starts a paused
 * player, so a paused one is paused again.
 */
fun setPlaybackSpeed(player: MediaPlayer, speed: Float) {
    runCatching {
        val playing = player.isPlaying
        player.playbackParams = player.playbackParams.setSpeed(speed).setPitch(1f)
        if (!playing) player.pause()
    }
}

/** Moves the playhead by [deltaMs], held inside the recording. */
fun seekBy(player: MediaPlayer, deltaMs: Int) {
    runCatching {
        val target = (player.currentPosition + deltaMs).coerceIn(0, maxOf(0, player.duration - 1))
        player.seekTo(target)
    }
}
