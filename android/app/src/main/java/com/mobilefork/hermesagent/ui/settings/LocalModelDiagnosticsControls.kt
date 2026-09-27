package com.mobilefork.hermesagent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings

internal fun memoryDiagnosticsText(language: AppLanguage, copied: Boolean = false): String = when (language) {
    AppLanguage.CHINESE -> if (copied) "内存诊断已复制" else "复制内存诊断"
    AppLanguage.SPANISH -> if (copied) "Diagnóstico de memoria copiado" else "Copiar diagnóstico de memoria"
    AppLanguage.GERMAN -> if (copied) "Speicherdiagnose kopiert" else "Speicherdiagnose kopieren"
    AppLanguage.PORTUGUESE -> if (copied) "Diagnóstico de memória copiado" else "Copiar diagnóstico de memória"
    AppLanguage.FRENCH -> if (copied) "Diagnostic mémoire copié" else "Copier le diagnostic mémoire"
    AppLanguage.ENGLISH -> if (copied) "Memory diagnostics copied" else "Copy memory diagnostics"
}

@Composable
internal fun LocalModelDiagnosticsControls() {
    val context = LocalContext.current
    val language = LocalHermesStrings.current.language
    var copied by remember(language) { mutableStateOf(false) }
    Text(when (language) {
        AppLanguage.CHINESE -> "可用内存是当前系统可用量减去 Android 保留量，不是 Java 堆上限。模型未启动也能复制诊断；内容包括上次启动尝试。"
        AppLanguage.SPANISH -> "La memoria utilizable es la disponible del sistema menos la reserva de Android, no el límite del heap Java. Puedes copiar el diagnóstico antes de iniciar un modelo; incluye el último intento."
        AppLanguage.GERMAN -> "Nutzbarer RAM ist der aktuell verfügbare Systemspeicher abzüglich der Android-Reserve, nicht das Java-Heap-Limit. Die Diagnose ist auch vor dem Modellstart verfügbar und enthält den letzten Startversuch."
        AppLanguage.PORTUGUESE -> "A RAM utilizável é a memória disponível do sistema menos a reserva do Android, não o limite do heap Java. O diagnóstico pode ser copiado antes de iniciar o modelo e inclui a última tentativa."
        AppLanguage.FRENCH -> "La RAM utilisable est la mémoire système disponible moins la réserve Android, pas la limite du tas Java. Le diagnostic peut être copié avant de démarrer un modèle et inclut la dernière tentative."
        AppLanguage.ENGLISH -> "Usable RAM is current available system memory minus Android's reserve, not the Java heap limit. Diagnostics can be copied before a model starts and include the last startup attempt."
    }, style = MaterialTheme.typography.bodySmall)
    TextButton(
        modifier = Modifier.testTag("CopyLocalModelDiagnostics"),
        onClick = {
            context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                clipboard.setPrimaryClip(ClipData.newPlainText(memoryDiagnosticsText(language),
                    LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
                copied = true
            }
        },
    ) { Text(memoryDiagnosticsText(language, copied)) }
}
