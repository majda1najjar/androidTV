package com.xtreamtv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.xtreamtv.R

/**
 * Fullscreen ExoPlayer for VOD and series episodes (live channels play inside
 * LiveTvFragment's docked/fullscreen preview player instead).
 *
 * Hardware keys during playback:
 *   Green — cycle aspect ratio (Fit / Zoom / Stretch)
 */
class PlayerActivity : FragmentActivity() {

    private lateinit var playerView: StyledPlayerView
    private lateinit var overlayToast: TextView
    private var player: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable {
        overlayToast.animate().alpha(0f).setDuration(300)
            .withEndAction { overlayToast.visibility = View.GONE }
            .start()
    }

    private lateinit var streamUrl: String
    private var streamTitle: String = ""
    private var titleShown = false
    private var retryCount = 0
    private var resizeModeIndex = 0
    private var savedPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        streamTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        playerView = findViewById(R.id.player_view)
        overlayToast = findViewById(R.id.overlay_toast)

        if (streamUrl.isBlank()) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        if (player != null) return
        // A pending hide callback may have been cancelled by releasePlayer()
        // while the overlay was visible (e.g. Home pressed mid-reconnect).
        overlayToast.animate().cancel()
        overlayToast.visibility = View.GONE
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(playerListener)
        retryCount = 0
        val item = MediaItem.fromUri(streamUrl)
        // Resume where the user left off after Home/screensaver.
        if (savedPosition > 0) {
            exo.setMediaItem(item, savedPosition)
        } else {
            exo.setMediaItem(item)
        }
        exo.playWhenReady = true
        exo.prepare()
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        player?.let { exo ->
            exo.currentPosition.takeIf { it > 0 }?.let { savedPosition = it }
            playerView.player = null
            exo.release()
        }
        player = null
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    if (!titleShown && streamTitle.isNotBlank()) {
                        titleShown = true
                        showOverlay(streamTitle)
                    }
                }
                Player.STATE_ENDED -> finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Providers drop streams routinely; reset and retry at the saved
            // position before giving up.
            attemptReconnect()
        }
    }

    private fun attemptReconnect() {
        if (retryCount >= MAX_RETRIES) {
            showOverlay(getString(R.string.player_failed), LONG_OVERLAY_MS)
            return
        }
        retryCount++
        showOverlay(getString(R.string.player_reconnecting, retryCount, MAX_RETRIES))
        val position = player?.currentPosition ?: 0L
        handler.postDelayed({
            player?.let { exo ->
                exo.stop()
                val item = MediaItem.fromUri(streamUrl)
                if (position > 0) {
                    exo.setMediaItem(item, position)
                } else {
                    exo.setMediaItem(item)
                }
                exo.prepare()
                exo.play()
            }
        }, RETRY_DELAY_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_PROG_GREEN -> {
                cycleAspectRatio()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun cycleAspectRatio() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        val (mode, labelRes) = RESIZE_MODES[resizeModeIndex]
        playerView.resizeMode = mode
        showOverlay(getString(R.string.player_aspect, getString(labelRes)))
    }

    private fun showOverlay(message: String, durationMs: Long = OVERLAY_MS) {
        handler.removeCallbacks(hideOverlay)
        overlayToast.text = message
        overlayToast.visibility = View.VISIBLE
        overlayToast.animate().cancel()
        overlayToast.alpha = 1f
        handler.postDelayed(hideOverlay, durationMs)
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 3000L
        private const val OVERLAY_MS = 2200L
        private const val LONG_OVERLAY_MS = 5000L

        private val RESIZE_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.aspect_fit,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.aspect_zoom,
            AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.aspect_stretch
        )

        fun vodIntent(context: Context, url: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
