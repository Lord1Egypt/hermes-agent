package com.mobilefork.hermesagent.ui.diagnostics

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.diagnosticsExportText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DiagnosticsExportViewModelTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test fun cancellationAndRestorationNeverClaimAFileWasSavedOrDuplicateAnExport() {
        val state = SavedStateHandle()
        val first = DiagnosticsExportViewModel(app, state)
        val name = first.chooseDestination()!!
        assertTrue(name.matches(Regex("agent-diagnostics-[0-9-]+\\.txt")))
        assertEquals(DiagnosticExportPhase.Choosing, first.phase.value)
        assertNull(first.chooseDestination())
        val restored = DiagnosticsExportViewModel(app, state)
        assertEquals(DiagnosticExportPhase.Choosing, restored.phase.value)
        restored.destinationSelected(null)
        assertEquals(DiagnosticExportPhase.Cancelled, restored.phase.value)
        assertNotNull(restored.chooseDestination())
        restored.pickerUnavailable()
        assertEquals(DiagnosticExportPhase.PickerUnavailable, restored.phase.value)
        assertFalse(restored.phase.value.busy)
        val interrupted = DiagnosticsExportViewModel(app,
            SavedStateHandle(mapOf("diagnostic_export_write_pending" to true)))
        assertEquals(DiagnosticExportPhase.Interrupted, interrupted.phase.value)
        assertNotNull(interrupted.chooseDestination())
        val owner = ViewModelStore()
        owner.put("first", first); owner.put("restored", restored); owner.put("interrupted", interrupted)
        owner.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun everyVisibleStateHasLocalizedAccessibleText() {
        for (language in AppLanguage.entries) {
            val keys = listOf("title", "description", "privacy", "export") +
                DiagnosticExportPhase.entries.filter { it != DiagnosticExportPhase.Idle }.map { it.name }
            for (key in keys) assertTrue("$language/$key", diagnosticsExportText(language, key).isNotBlank())
            assertNotEquals(diagnosticsExportText(language, "Saved"), diagnosticsExportText(language, "Failed"))
            assertNotEquals(diagnosticsExportText(language, "Saved"), diagnosticsExportText(language, "Cancelled"))
        }
    }
}
