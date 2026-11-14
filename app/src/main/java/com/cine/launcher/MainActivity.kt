package com.cine.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
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
        
        // Root layout setup for 10-foot TV UI experience
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF07090E.toInt()) // Dark obsidian base
        }
        setContentView(rootLayout)

        // 1. Play "Cine Home" Boot / Splash Sequence
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
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        splashContainer.addView(titleTextView)
        parent.addView(splashContainer)

        // Fade in and scale up the Cine Home cinematic intro
        val fadeIn = ObjectAnimator.ofFloat(titleTextView, View.ALPHA, 0f, 1f).apply { duration = 1000 }
        val scaleX = ObjectAnimator.ofFloat(titleTextView, View.SCALE_X, 0.85f, 1f).apply { duration = 1200 }
        val scaleY = ObjectAnimator.ofFloat(titleTextView, View.SCALE_Y, 0.85f, 1f).apply { duration = 1200 }

        AnimatorSet().apply {
            playTogether(fadeIn, scaleX, scaleY)
            interpolator = DecelerateInterpolator()
            start()
        }

        // Dismiss splash after 2.5 seconds to load the Main Dashboard
        titleTextView.postDelayed({
            val fadeOut = ObjectAnimator.ofFloat(splashContainer, View.ALPHA, 1f, 0f).apply {
                duration = 500
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        parent.removeView(splashContainer)
                        loadMainDashboard()
                    }
                })
            }
            fadeOut.start()
        }, 2500)
    }

    private fun loadMainDashboard() {
        // Initialize your TV Dashboard UI, app tile rows, and storage widgets here
    }

    // 2. Xiaomi TV 5A Remote Key Remapping
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Handle upward tile navigation
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Handle downward navigation (e.g., revealing storage cards or activity details)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Handle left ribbon scrolling
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Handle right ribbon scrolling
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK button on Xiaomi remote acts as Confirm / Launch ( like Cross 'X' )
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
                // Menu button on Xiaomi remote acts as the PS Button to toggle Control Center
                isControlCenterOpen = !isControlCenterOpen
                toggleControlCenter(isControlCenterOpen)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun toggleControlCenter(show: Boolean) {
        // Show or hide the frosted-glass control overlay dock
    }
}
