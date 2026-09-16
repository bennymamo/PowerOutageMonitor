package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Short, reusable setup steps. Only the step number is saved; secrets remain in memory. */
@Composable
internal fun SetupFlowHeader(steps: List<String>, step: Int, guided: Boolean, busy: Boolean,
    onStep: (Int) -> Unit) {
    BackHandler(guided && step > 0 && !busy) { onStep(step - 1) }
    if (guided) {
        Text("Step ${step + 1} of ${steps.size} · ${steps[step]}", fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { (step + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        ExpandableSettingsSection("Jump to a step", "Return to an earlier section") {
            steps.forEachIndexed { index, title ->
                TextButton({ onStep(index) }, enabled = !busy) { Text("${index + 1}. $title") }
            }
        }
    } else Text("Open only the sections you need. Save changes before leaving.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SetupFlowSection(index: Int, step: Int, guided: Boolean, title: String,
    content: @Composable ColumnScope.() -> Unit) {
    if (guided) {
        if (step == index) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else ExpandableSettingsSection(title, "Tap to open", content = content)
}

@Composable
internal fun SetupFlowFooter(steps: List<String>, step: Int, guided: Boolean, busy: Boolean,
    onStep: (Int) -> Unit, onFinish: () -> Unit, finishEnabled: Boolean = true) {
    if (!guided) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step > 0) OutlinedButton({ onStep(step - 1) }, enabled = !busy,
            modifier = Modifier.weight(1f)) { Text("Previous") }
        Button({ if (step < steps.lastIndex) onStep(step + 1) else onFinish() },
            enabled = !busy && (step < steps.lastIndex || finishEnabled), modifier = Modifier.weight(1f)) {
            Text(if (step < steps.lastIndex) "Next" else "Finish")
        }
    }
    if (step == steps.lastIndex && !finishEnabled) Text("Save your configuration before finishing.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ExpandableSettingsSection(title: String, summary: String = "", initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SettingsCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
        if (expanded) content()
    }
}
