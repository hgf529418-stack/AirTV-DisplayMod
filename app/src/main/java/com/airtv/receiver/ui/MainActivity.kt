package com.airtv.receiver.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.airtv.receiver.R
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.service.AirPlayService

/**
 * Full-screen mirroring surface with an idle status card. The AirPlay server itself lives
 * in [AirPlayService]; this activity only supplies the surface to render onto.
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView

    private val widthPrefs by lazy { getSharedPreferences("display_settings", Context.MODE_PRIVATE) }
    private var widthStep: Int
        get() = widthPrefs.getInt("portrait_width_step", 2).coerceIn(0, 12)
        set(value) { widthPrefs.edit().putInt("portrait_width_step", value.coerceIn(0, 12)).apply() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val state = receiver?.state as? ReceiverState.Streaming
        if (state != null && state.width > 0 && state.width < state.height &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            widthStep += if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            applyVideoAspect(state.width, state.height)
            Toast.makeText(this, "Portrait width: ${100 + widthStep * 25}%", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var service: AirPlayService.LocalBinder? = null
    private var receiver: AirPlayReceiver? = null
    private var pendingSurface: SurfaceHolder? = null

    private val stateListener: (ReceiverState) -> Unit = { render(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AirPlayService.LocalBinder ?: return
            service = local
            receiver = local.receiver
            local.receiver.addListener(stateListener)
            pendingSurface?.let { local.receiver.setSurface(it.surface) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiver?.removeListener(stateListener)
            receiver = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        detailsText = findViewById(R.id.details)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingSurface = null
                receiver?.setSurface(null)
            }
        })

        AirPlayService.start(this)
        bindService(
            Intent(this, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onDestroy() {
        receiver?.removeListener(stateListener)
        receiver?.setSurface(null)
        runCatching { unbindService(connection) }
        if (isFinishing) {
            // Leaving the app tears the receiver down; there is nowhere to render to.
            AirPlayService.stop(this)
        }
        super.onDestroy()
    }

    /** Centre the decoder surface; portrait is widened while its full height remains visible. */
    private fun applyVideoAspect(sourceWidth: Int, sourceHeight: Int) {
        val root = findViewById<View>(android.R.id.content)
        val availableWidth = root.width
        val availableHeight = root.height
        if (availableWidth <= 0 || availableHeight <= 0) {
            // Layout has not settled yet; try again once it has.
            root.post { applyVideoAspect(sourceWidth, sourceHeight) }
            return
        }
        val rect = VideoLayout.automatic(
            sourceWidth, sourceHeight, availableWidth, availableHeight, 1f + widthStep * 0.25f,
        ) ?: return
        val current = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (current != null && current.width == rect.width && current.height == rect.height) {
            return
        }
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            rect.width, rect.height, Gravity.CENTER,
        )
    }

    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Streaming -> {
                overlay.visibility = View.GONE
                statusText.text = getString(R.string.status_streaming, state.clientName)
                applyVideoAspect(state.width, state.height)
            }

            is ReceiverState.Advertising -> {
                overlay.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_ready, state.name)
                val display = receiver?.advertisedDisplay
                detailsText.text = listOfNotNull(
                    state.address,
                    display?.let { "${it.width}×${it.height} @ ${it.fps}Hz" },
                ).joinToString("   ·   ")
            }

            is ReceiverState.AudioOnly -> {
                overlay.visibility = View.VISIBLE
                if (state.playable) {
                    statusText.text = getString(R.string.status_audio_only, state.clientName)
                    detailsText.text = getString(R.string.status_audio_only_detail)
                } else {
                    statusText.text = getString(
                        R.string.status_audio_unsupported, state.clientName, state.codec,
                    )
                    detailsText.text =
                        getString(R.string.status_audio_unsupported_detail, state.codec)
                }
            }

            is ReceiverState.Failed -> {
                overlay.visibility = View.VISIBLE
                statusText.text = state.reason
                detailsText.text = ""
            }

            ReceiverState.Stopped -> {
                overlay.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_starting)
                detailsText.text = ""
            }
        }
    }
}
