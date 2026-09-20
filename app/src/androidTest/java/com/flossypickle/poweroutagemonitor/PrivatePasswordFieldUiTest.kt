package com.flossypickle.poweroutagemonitor

import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flossypickle.poweroutagemonitor.ui.PrivatePasswordField
import org.junit.Rule
import org.junit.Test

class PrivatePasswordFieldUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun revealAndHidePreserveTheEnteredPassword() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            PrivatePasswordField("Backup password", value) { value = it }
        }
        compose.onNode(hasSetTextAction()).performTextInput("fake test password")
        compose.onNodeWithText("Show").performClick()
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        compose.onNode(hasSetTextAction()).assertTextContains("fake test password")
        compose.onNodeWithText("Hide").performClick()
        compose.runOnIdle { assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Show").assertExists()
    }
}
