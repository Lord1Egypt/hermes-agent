package com.mobilefork.hermesagent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProotLoaderRoutingRegressionTest {
    @Test fun nativeEnvironmentAndShellPreludeUseApkBackedLoaderShims() {
        val prefix = "/data/user/0/example/files/linux/prefix"
        val trustedLibexec = "/data/user/0/example/files/linux/native-exec/libexec"
        val state = JSONObject()
            .put("uses_termux", true)
            .put("prefix_path", prefix)
            .put("home_path", "$prefix/home")
            .put("tmp_path", "$prefix/tmp")
            .put("lib_path", "$prefix/lib")
            .put("native_library_dir", "/data/app/example/lib/arm64")
            .put("native_libexec_path", trustedLibexec)
            .put("python_path", "/data/app/example/lib/arm64/libhermes_exec_bin_python3_14.so")
        val environment = HermesLinuxSubsystemBridge.buildRunEnvironment(state)
        val command = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, "proot -r / /system/bin/true")
        for ((key, name) in listOf("PROOT_LOADER" to "loader", "PROOT_LOADER_32" to "loader32")) {
            val expected = "$trustedLibexec/proot/$name"
            assertEquals("The writable prefix is not an executable-code location", expected, environment[key])
            assertTrue(command.contains("export $key=" + HermesLinuxSubsystemBridge.shellQuote(expected)))
            assertFalse(command.contains("export $key=" + HermesLinuxSubsystemBridge.shellQuote("$prefix/libexec/proot/$name")))
        }
    }
}
