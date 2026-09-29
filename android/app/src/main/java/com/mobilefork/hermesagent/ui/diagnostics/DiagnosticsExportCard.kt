package com.mobilefork.hermesagent.ui.diagnostics

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.diagnosticsExportText

@Composable
internal fun DiagnosticsExportCard() {
    val language = LocalHermesStrings.current.language
    OutlinedCard(Modifier.fillMaxWidth().testTag("AgentDiagnosticsCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(diagnosticsExportText(language, "title"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(diagnosticsExportText(language, "description"), style = MaterialTheme.typography.bodySmall)
            Text(diagnosticsExportText(language, "privacy"), style = MaterialTheme.typography.bodySmall)
            DiagnosticsExportControls()
        }
    }
}

/** The same save operation is exposed in Settings and the older Device diagnostics card. */
@Composable
internal fun DiagnosticsExportControls(
    viewModel: DiagnosticsExportViewModel = viewModel(key = "agent-diagnostics-export"),
) {
    val language = LocalHermesStrings.current.language
    val phase by viewModel.phase.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        viewModel.destinationSelected(it)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.fillMaxWidth().testTag("ExportDiagnosticLog"),
            enabled = !phase.busy,
            onClick = {
                viewModel.chooseDestination()?.let { fileName ->
                    try {
                        launcher.launch(fileName)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.pickerUnavailable()
                    }
                }
            },
        ) { Text(diagnosticsExportText(language, "export")) }
        if (phase != DiagnosticExportPhase.Idle) {
            Text(
                diagnosticsExportText(language, phase.name),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("DiagnosticLogExportStatus")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
