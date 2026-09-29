package com.mobilefork.hermesagent

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.DeviceCapabilityState
import com.mobilefork.hermesagent.data.DeviceCapabilityStore
import com.mobilefork.hermesagent.device.DeviceStateWriter
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.NativeAndroidShellTool
import com.mobilefork.hermesagent.ui.device.DeviceScreen
import com.mobilefork.hermesagent.ui.device.DeviceViewModel
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.settings.SettingsPage
import com.mobilefork.hermesagent.ui.settings.SettingsScreen
import com.mobilefork.hermesagent.ui.settings.SettingsViewModel
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceMemoryRegressionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private lateinit var settings: AppSettings
    private lateinit var capability: DeviceCapabilityState
    private lateinit var previousCopies: Set<String>

    @Before fun prepare() {
        settings = AppSettingsStore(app).load()
        capability = DeviceCapabilityStore(app).load()
        previousCopies = DeviceStateWriter.workspaceDir(app).listFiles().orEmpty().map { it.name }.toSet()
        AppSettingsStore(app).save(settings.copy(languageTag = "en", offlineAirplaneMode = true,
            portalEnabled = false, onDeviceBackend = "none"))
    }

    @After fun restore() {
        owner.clear()
        AppSettingsStore(app).save(settings)
        DeviceCapabilityStore(app).saveSharedFolder(capability.sharedFolderUri, capability.sharedFolderLabel)
        val resolver = app.contentResolver
        resolver.persistedUriPermissions.filter { it.uri.authority == WorkspaceFixtureDocumentsProvider.AUTHORITY }
            .forEach { permission ->
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                resolver.releasePersistableUriPermission(permission.uri, flags)
            }
        DeviceStateWriter.workspaceDir(app).listFiles().orEmpty()
            .filter { it.name !in previousCopies && it.name.startsWith("shared-") }
            .forEach { assertTrue("Test snapshot cleanup failed", it.deleteRecursively()) }
    }

    @Test fun realSystemGrantCopiesNestedDocumentsReadableByNativeAndProotShells() {
        assertFalse("Workspace tools belong to the Full edition", BuildConfig.HERMES_PLAY_EDITION)
        val vm = DeviceViewModel(app)
        owner.put("device", vm)
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalHermesStrings provides hermesStringsFor(AppLanguage.ENGLISH)) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { DeviceScreen(viewModel = vm) }
                }
            }
        }
        compose.onNodeWithTag("HermesDevicePage_Files").performClick()
        compose.onNodeWithTag("GrantSharedFolderButton").performScrollTo().performClick()
        val rootTitle = WorkspaceFixtureDocumentsProvider.TITLE
        if (!clickNodeWithin(3_000) { it.text?.toString() == rootTitle }) {
            assertTrue("Open the actual system picker root drawer", clickNodeWithin(10_000) {
                it.contentDescription?.toString()?.contains("Show roots", true) == true
            })
            assertTrue("Find the installed fixture provider", clickNodeWithin(10_000) { it.text?.toString() == rootTitle })
        }
        assertTrue("Grant the selected tree through DocumentsUI", clickNodeWithin(10_000) {
            it.text?.toString()?.equals("Use this folder", true) == true
        })
        assertTrue("Confirm Android tree permission", clickNodeWithin(10_000) {
            it.text?.toString()?.equals("Allow", true) == true
        })
        compose.waitUntil(60_000) { !vm.uiState.value.sharedFolderCopyInProgress &&
            (vm.uiState.value.sharedFolderCopyPath.isNotBlank() || vm.uiState.value.sharedFolderCopyError.isNotBlank()) }
        assertEquals("", vm.uiState.value.sharedFolderCopyError)
        val copiedRoot = File(vm.uiState.value.sharedFolderCopyPath)
        val note = File(copiedRoot, "Notes/hello.txt")
        assertEquals(WorkspaceFixtureDocumentsProvider.CONTENT, note.readText())
        assertEquals(DeviceStateWriter.workspaceDir(app).canonicalFile, copiedRoot.canonicalFile.parentFile)
        val granted = Uri.parse(DeviceCapabilityStore(app).load().sharedFolderUri)
        assertEquals(WorkspaceFixtureDocumentsProvider.AUTHORITY, granted.authority)
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == granted && it.isReadPermission })
        val command = "cat " + HermesLinuxSubsystemBridge.shellQuote(note.absolutePath)
        val native = NativeAndroidShellTool.run(app, command, timeoutSeconds = 60, includeLinuxSandboxStatus = false)
        assertEquals(native.toString(), 0, native.optInt("exit_code", -1))
        assertTrue(native.toString(), native.getString("output").contains(WorkspaceFixtureDocumentsProvider.CONTENT.trim()))
        // Prefer a retained distro when supplied. Otherwise exercise the real packaged
        // proot binary against the existing Android root; never download/create a guest.
        val sandbox = InstrumentationRegistry.getArguments().getString("existing_sandbox")
        val guestPath = "/workspace/${copiedRoot.name}/Notes/hello.txt"
        val proot = if (!sandbox.isNullOrBlank()) {
            println("WORKSPACE_PROOT_MODE=retained-distro")
            HermesLinuxSandboxBridge.runUserCommand(app, sandbox,
                "cat ${HermesLinuxSubsystemBridge.shellQuote(guestPath)}", timeoutSeconds = 90)
        } else {
            println("WORKSPACE_PROOT_MODE=retained-android-root")
            val binding = DeviceStateWriter.workspaceDir(app).absolutePath + ":/workspace"
            NativeAndroidShellTool.run(app,
                "proot -r / -b ${HermesLinuxSubsystemBridge.shellQuote(binding)} " +
                    "/system/bin/cat ${HermesLinuxSubsystemBridge.shellQuote(guestPath)}", timeoutSeconds = 90, includeLinuxSandboxStatus = false)
        }
        assertEquals(proot.toString(), 0, proot.optInt("exit_code", -1))
        assertTrue(proot.toString(), proot.getString("output").contains(WorkspaceFixtureDocumentsProvider.CONTENT.trim()))
        note.writeText("Local terminal edit; never written to the provider")
        compose.onNodeWithTag("SharedFolderWorkspaceCopyButton").performScrollTo().performClick()
        compose.waitUntil(60_000) { !vm.uiState.value.sharedFolderCopyInProgress &&
            vm.uiState.value.sharedFolderCopyPath.isNotBlank() && vm.uiState.value.sharedFolderCopyPath != copiedRoot.absolutePath }
        assertEquals("", vm.uiState.value.sharedFolderCopyError)
        assertEquals("Local terminal edit; never written to the provider", note.readText())
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(granted, "note")
        val sourceText = app.contentResolver.openInputStream(sourceUri)!!.bufferedReader().use { it.readText() }
        assertEquals(WorkspaceFixtureDocumentsProvider.CONTENT, sourceText)
        assertEquals(WorkspaceFixtureDocumentsProvider.CONTENT,
            File(vm.uiState.value.sharedFolderCopyPath, "Notes/hello.txt").readText())
    }

    @Test fun memoryDiagnosticsCanBeCopiedBeforeStartingAnyModel() {
        val vm = SettingsViewModel(app)
        owner.put("settings", vm)
        compose.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SettingsScreen(viewModel = vm, initialPage = SettingsPage.Models)
                }
            }
        }
        compose.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag("ModelSettings-runtime"))
        compose.onNodeWithTag("ModelSettings-runtime").performClick()
        compose.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag("CopyLocalModelDiagnostics"))
        compose.onNodeWithTag("CopyLocalModelDiagnostics").assertIsEnabled().performClick()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull(clip)
        val report = JSONObject(clip!!.getItemAt(0).text.toString())
        assertEquals("ActivityManager.MemoryInfo", report.getString("memory_source"))
        assertTrue(report.getJSONObject("current_memory").getLong("total_bytes") > 0)
        assertEquals("none", AppSettingsStore(app).load().onDeviceBackend)
        assertTrue(report.getString("usable_ram_definition").contains("not the Java heap"))
    }

    private fun clickNodeWithin(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::addLast)
            var seen = 0
            while (queue.isNotEmpty() && ++seen < 500) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && predicate(node)) {
                    var target: AccessibilityNodeInfo? = node
                    repeat(5) {
                        if (target?.isClickable == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        target = target?.parent
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
            SystemClock.sleep(100)
        }
        return false
    }
}
