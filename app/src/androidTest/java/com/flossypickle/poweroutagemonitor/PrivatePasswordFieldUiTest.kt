package com.flossypickle.poweroutagemonitor

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
        compose.onNode(hasSetTextAction()).assertTextContains("fake test password")
        compose.onNodeWithText("Hide").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Show").assertExists()
    }
}
