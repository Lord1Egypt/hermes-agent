package com.mobilefork.hermesagent.ui.i18n

/** All user-visible export states, including failure/retry, are available in each app language. */
internal fun diagnosticsExportText(language: AppLanguage, key: String): String {
    val index = when (language) {
        AppLanguage.ENGLISH -> 0
        AppLanguage.CHINESE -> 1
        AppLanguage.SPANISH -> 2
        AppLanguage.GERMAN -> 3
        AppLanguage.PORTUGUESE -> 4
        AppLanguage.FRENCH -> 5
    }
    return diagnosticExportTranslations.getValue(key)[index]
}

private val diagnosticExportTranslations = mapOf(
    "title" to listOf("Diagnostics and logs", "诊断与日志", "Diagnóstico y registros", "Diagnose und Protokolle", "Diagnóstico e logs", "Diagnostic et journaux"),
    "description" to listOf(
        "Save app, Android, memory and model-start diagnostics to a folder you choose. No running model or crash is required.",
        "将应用、Android、内存和模型启动诊断保存到您选择的文件夹。无需启动模型，也无需发生崩溃。",
        "Guarda el diagnóstico de la app, Android, memoria e inicio del modelo en la carpeta que elijas. No requiere un modelo activo ni un fallo.",
        "App-, Android-, Speicher- und Modellstartdiagnose in einem gewählten Ordner speichern. Kein laufendes Modell oder Absturz erforderlich.",
        "Salve o diagnóstico do app, Android, memória e início do modelo na pasta escolhida. Não exige modelo em execução nem falha.",
        "Enregistrez le diagnostic de l’app, d’Android, de la mémoire et du démarrage du modèle dans le dossier choisi. Aucun modèle actif ni plantage requis.",
    ),
    "privacy" to listOf(
        "Known secrets are redacted, but model filenames and error details may remain. Review the file before sharing. Nothing is uploaded automatically.",
        "已知的敏感信息会被遮盖，但模型文件名和错误详情可能保留。分享前请检查文件。不会自动上传任何内容。",
        "Se ocultan secretos conocidos, pero pueden quedar nombres de modelos y detalles de errores. Revisa el archivo antes de compartirlo. No se sube automáticamente.",
        "Bekannte Geheimnisse werden maskiert; Modelldateinamen und Fehlerdetails können bleiben. Datei vor dem Teilen prüfen. Kein automatischer Upload.",
        "Segredos conhecidos são ocultados, mas nomes de modelos e detalhes de erros podem permanecer. Revise antes de compartilhar. Nada é enviado automaticamente.",
        "Les secrets connus sont masqués, mais les noms de modèles et détails d’erreurs peuvent rester. Vérifiez le fichier avant partage. Aucun envoi automatique.",
    ),
    "export" to listOf("Export diagnostic log", "导出诊断日志", "Exportar registro de diagnóstico", "Diagnoseprotokoll exportieren", "Exportar log de diagnóstico", "Exporter le journal de diagnostic"),
    "Choosing" to listOf("Choose a folder and filename in Android’s save dialog.", "在 Android 保存窗口中选择文件夹和文件名。", "Elige carpeta y nombre en el diálogo de Android.", "Ordner und Dateinamen im Android-Speicherdialog wählen.", "Escolha a pasta e o nome na janela do Android.", "Choisissez le dossier et le nom dans la fenêtre Android."),
    "Saving" to listOf("Saving diagnostic log…", "正在保存诊断日志…", "Guardando el registro…", "Diagnoseprotokoll wird gespeichert…", "Salvando o log…", "Enregistrement du journal…"),
    "Saved" to listOf("Diagnostic log saved to the selected location.", "诊断日志已保存到所选位置。", "Registro guardado en la ubicación elegida.", "Diagnoseprotokoll am gewählten Ort gespeichert.", "Log salvo no local escolhido.", "Journal enregistré à l’emplacement choisi."),
    "Cancelled" to listOf("Export cancelled. No log was saved.", "已取消导出。未保存日志。", "Exportación cancelada. No se guardó ningún registro.", "Export abgebrochen. Kein Protokoll gespeichert.", "Exportação cancelada. Nenhum log foi salvo.", "Export annulé. Aucun journal enregistré."),
    "Failed" to listOf(
        "Could not save the log. A partial file may remain. Check free space or choose another location, then retry.",
        "无法保存日志，可能留下不完整文件。请检查可用空间或选择其他位置后重试。",
        "No se pudo guardar. Puede quedar un archivo parcial. Revisa el espacio o elige otra ubicación e inténtalo de nuevo.",
        "Speichern fehlgeschlagen; eine unvollständige Datei kann verbleiben. Speicherplatz prüfen oder anderen Ort wählen und erneut versuchen.",
        "Não foi possível salvar. Pode restar um arquivo parcial. Verifique o espaço ou escolha outro local e tente novamente.",
        "Échec de l’enregistrement. Un fichier partiel peut rester. Vérifiez l’espace ou choisissez un autre emplacement, puis réessayez.",
    ),
    "PickerUnavailable" to listOf("Android’s file picker could not open. Enable a Files app and retry.", "无法打开 Android 文件选择器。请启用文件应用后重试。", "No se pudo abrir el selector. Activa una app de archivos y reintenta.", "Dateiauswahl nicht verfügbar. Dateien-App aktivieren und erneut versuchen.", "Não foi possível abrir o seletor. Ative um app de arquivos e tente novamente.", "Sélecteur indisponible. Activez une app de fichiers puis réessayez."),
    "Interrupted" to listOf("The export was interrupted. Check the destination for a partial file, then export again.", "导出已中断。请检查目标位置是否有不完整文件，然后重新导出。", "La exportación se interrumpió. Revisa si quedó un archivo parcial y exporta de nuevo.", "Export unterbrochen. Ziel auf unvollständige Datei prüfen und erneut exportieren.", "A exportação foi interrompida. Verifique se há arquivo parcial e exporte novamente.", "Export interrompu. Vérifiez si un fichier partiel existe, puis recommencez."),
)
