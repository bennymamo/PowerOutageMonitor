package com.flossypickle.poweroutagemonitor.ui

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Revealing characters must not change the IME from password input to ordinary text. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun PrivatePasswordField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(value.isEmpty()) { if (value.isEmpty()) visible = false }
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                val connection = request.createInputConnection(info)
                configurePrivatePasswordInput(info)
                connection
            })
        }
    }
    InterceptPlatformTextInput(interceptor) {
        OutlinedTextField(value, { if (it.length <= 200) onChange(it) }, Modifier.fillMaxWidth(),
            label = { Text(label) }, singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } })
    }
}

internal fun configurePrivatePasswordInput(info: EditorInfo) {
    info.inputType = (info.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()
    if (Build.VERSION.SDK_INT >= 26) info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}
