package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Keeps unsaved screen drafts through activity recreation without writing secrets to saved state. */
internal class ScreenDraftViewModel : ViewModel() {
    private val states = mutableMapOf<String, MutableState<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, initialValue: T): MutableState<T> = synchronized(states) {
        states.getOrPut(key) { mutableStateOf(initialValue) } as MutableState<T>
    }
}
