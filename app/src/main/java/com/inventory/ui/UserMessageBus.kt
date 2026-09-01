package com.inventory.ui

import androidx.annotation.StringRes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * App-level bus for one-shot messages (string resources) that a ViewModel on
 * one screen raises for a snackbar on another — e.g. the camera save
 * announcing "Photo added" once the photos grid is back on screen.
 *
 * replay is intentionally 0: the receiving ViewModel lives on in the back
 * stack while the sender's screen is on top, so a buffered emit alone is
 * enough for its collector to pick the message up.
 */
@Singleton
class UserMessageBus @Inject constructor() {

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val messages: SharedFlow<Int> = _messages

    fun post(@StringRes resId: Int) {
        _messages.tryEmit(resId)
    }
}
