package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

internal val MonitorStore.HelpLevel.isGuided: Boolean
    get() = this == MonitorStore.HelpLevel.GUIDED

/** Shared setup-page marker so new integration modules follow the same help preference. */
@Composable
internal fun SetupGuidanceCaption(helpLevel: MonitorStore.HelpLevel) {
    Text(
        if (helpLevel.isGuided) "GUIDED SETUP" else "EXPERIENCED SETUP",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
}
