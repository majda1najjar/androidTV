package com.xtreamtv.ui.common

import android.view.View

/**
 * Shared D-pad focus treatment: focused items scale up slightly on top of the
 * bright-border state drawables, so the current selection is always obvious
 * from ten feet away.
 */
object FocusDecor {

    fun install(view: View, onFocus: ((Boolean) -> Unit)? = null) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(ANIMATION_MS).start()
            onFocus?.invoke(hasFocus)
        }
    }

    private const val FOCUS_SCALE = 1.06f
    private const val ANIMATION_MS = 120L
}
