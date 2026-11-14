package com.cine.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var splashContainer: FrameLayout
    private lateinit var titleTextView: TextView
    private var isControlCenterOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF07090E.toInt()) // Obsidian dark base
        }
        setContentView(rootLayout)

        playCineHomeSplash(rootLayout)
    }

    private fun playCineHomeSplash(parent: FrameLayout) {
        splashContainer = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        titleTextView = TextView(this).apply {
            text = "CINE HOME"
            textSize = 36f
            setTextColor(0xFFFFFFFF.toInt())
            letterSpacing = 0.2f
            alpha = 0f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        splashContainer.addView(titleTextView)
        parent.addView(splashContainer)

        val fadeIn = ObjectAnimator.ofFloat(titleTextView, View.ALPHA, 0f, 1f).apply { duration = 1000 }
        val scaleX = ObjectAnimator.ofFloat(titleTextView, View.SCALE_X, 0.85f, 1f).apply { duration = 1200 }
        val scaleY = ObjectAnimator.ofFloat(titleTextView, View.SCALE_Y, 0.85f, 1f).apply { duration = 1200 }

        AnimatorSet().apply {
            playTogether(fadeIn, scaleX, scaleY)
            interpolator = DecelerateInterpolator()
            start()
        }

        titleTextView.postDelayed({
            val fadeOut = ObjectAnimator.ofFloat(splashContainer, View.ALPHA, 1f, 0f).apply {
                duration = 500
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        parent.removeView(splashContainer)
                        loadMainDashboard()
                    }
                })
            }
            fadeOut.start()
        }, 2500)
    }

    private fun loadMainDashboard() {
        // Dashboard loaded after splash
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isControlCenterOpen) {
                    toggleControlCenter(false)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_STEM_1 -> {
                isControlCenterOpen = !isControlCenterOpen
                toggleControlCenter(isControlCenterOpen)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun toggleControlCenter(show: Boolean) {
        // Control Center toggle
    }
}
