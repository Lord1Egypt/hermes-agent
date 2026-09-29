package com.mobilefork.hermesagent.ui.diagnostics

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.device.DiagnosticsLogExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class DiagnosticExportPhase(val busy: Boolean = false) {
    Idle, Choosing(true), Saving(true), Saved, Cancelled, Failed, PickerUnavailable, Interrupted,
}

/** Survives activity rotation; process restoration never claims an interrupted write succeeded. */
internal class DiagnosticsExportViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val _phase = MutableStateFlow(
        when {
            savedState.get<Boolean>(WRITING) == true -> DiagnosticExportPhase.Interrupted
            savedState.get<Boolean>(PICKING) == true -> DiagnosticExportPhase.Choosing
            else -> DiagnosticExportPhase.Idle
        },
    )
    val phase = _phase.asStateFlow()

    fun chooseDestination(): String? {
        if (_phase.value.busy) return null
        savedState[WRITING] = false
        savedState[PICKING] = true
        _phase.value = DiagnosticExportPhase.Choosing
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return "agent-diagnostics-$timestamp.txt"
    }

    fun pickerUnavailable() {
        savedState[PICKING] = false
        _phase.value = DiagnosticExportPhase.PickerUnavailable
    }

    fun destinationSelected(uri: Uri?) {
        if (savedState.get<Boolean>(PICKING) != true || _phase.value == DiagnosticExportPhase.Saving) return
        savedState[PICKING] = false
        if (uri == null) {
            _phase.value = DiagnosticExportPhase.Cancelled
            return
        }
        savedState[WRITING] = true
        _phase.value = DiagnosticExportPhase.Saving
        viewModelScope.launch {
            try {
                DiagnosticsLogExport.save(getApplication(), uri)
                _phase.value = DiagnosticExportPhase.Saved
            } catch (cancelled: CancellationException) {
                _phase.value = DiagnosticExportPhase.Interrupted
                throw cancelled
            } catch (_: Exception) {
                // Provider exception messages can contain private URIs or credentials.
                _phase.value = DiagnosticExportPhase.Failed
            } finally {
                savedState[WRITING] = false
            }
        }
    }

    private companion object {
        const val PICKING = "diagnostic_export_picker_pending"
        const val WRITING = "diagnostic_export_write_pending"
    }
}
