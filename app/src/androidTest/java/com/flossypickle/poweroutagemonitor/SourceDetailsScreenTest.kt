package com.flossypickle.poweroutagemonitor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowCloudQuota
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.EcoFlowTelemetryMapper
import com.flossypickle.poweroutagemonitor.ui.SourceDetailsScreen
import com.flossypickle.poweroutagemonitor.ui.theme.PowerOutageMonitorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SourceDetailsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dashboardSearchFindsCollapsedSolarFieldsAndBackReturnsToSetup() {
        var returned = false
        val snapshot = EcoFlowTelemetryMapper.snapshot(EcoFlowCloudQuota(emptyList(), null, null, null, null, null,
            reportedValues = mapOf("sysGridPwr" to "1940", "sysLoadPwr" to "1940", "mpptPwr" to "0",
                "bpPwr" to "0", "bpSoc" to "20", "pcsAPhase.vol" to "230.1",
                "mpptHeartBeat[0].mpptPv[1].vol" to "350.5", "futureSensor" to "12")),
            "Test PowerOcean · sample data", System.currentTimeMillis())
        compose.setContent {
            PowerOutageMonitorTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SourceDetailsScreen(snapshot, PaddingValues(), false, null, {}, { returned = true })
                }
            }
        }
        compose.onNodeWithText("Read-only device snapshot").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.getExternalFilesDir("ui-check"), "source-details-dark.png")
        image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("source-details-list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("string 2")
        compose.onNodeWithTag("source-details-list").performScrollToNode(hasText("Tracker 1 · string 2 voltage"))
        compose.onNodeWithText("Tracker 1 · string 2 voltage").assertIsDisplayed()
        compose.onNodeWithTag("source-details-list").performScrollToIndex(0)
        compose.onNodeWithText("‹ Source setup").performClick()
        assertTrue(returned)
    }
}
