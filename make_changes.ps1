$file = "e:\proj\Opedrgent\app\src\main\java\top\hsyscn\opedrgent\ui\AppRoot.kt"
$content = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)

# CHANGE 1 & 2: Remove TabRow and when(tab) wrapper - keep only tab 0 content
# First remove TabRow (lines 488-492 approximately)
$tabRowPattern = '(?s)            TabRow\(selectedTabIndex = tab\) \{[^}]+Tab\(selected = tab == 0[^}]+\}[^}]+\}[^}]+\}[^}]+\}\s*'
$content = $content -replace $tabRowPattern, ""

# Now remove the when(tab) wrapper but keep the 0 branch content
# Find the when(tab) block and replace with just the 0 content
$whenPattern = '(?s)                when \(tab\) \{[\s\n]+                    0 -> \{([\s\S]*?)\}[\s\n]+                    [12] -> \{[\s\S]*?\}[\s\n]+                    else -> \{[\s\S]*?\}[\s\n]+                \}'
$content = $content -replace $whenPattern, '            $1'

# CHANGE 3: Replace FilterChip row - remove "帮我写" and "更多" buttons
$filterChipPattern = '(?s)                FilterChip\([^)]+\)[^)]+\)[^}]+\}[\s\n]+                FilterChip\([^)]+\)[^)]+\)[^}]+\}[\s\n]+                Button\(onClick = \{ vm\.generateReport\(\)[^}]+\}[^}]+\}[^}]+\}[\s\n]+                Button\(onClick = \{ actionSheetOpen = true \}[^}]+\}[\s\n]+            \}'
$replacement = '                FilterChip(
                    selected = state.deepThinkingEnabled,
                    onClick = { vm.toggleDeepThinking() },
                    label = { Text(if (state.deepThinkingEnabled) "深度思考" else "快速思考") },
                    leadingIcon = if (state.deepThinkingEnabled) {{ Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.padding(2.dp)) }} else null,
                )
                FilterChip(
                    selected = state.deepResearchEnabled,
                    onClick = { vm.saveDeepResearch(!state.deepResearchEnabled) },
                    label = { Text("深度研究") },
                    leadingIcon = if (state.deepResearchEnabled) {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(2.dp)) }} else null,
                )
            }'
$content = $content -replace $filterChipPattern, $replacement

# CHANGE 4: Add attachment IconButton before OutlinedTextField
$inputRowPattern = '(?s)            Row\([^}]+verticalAlignment = Alignment\.CenterVertically,[^}]+\{[\s\n]+                OutlinedTextField\('
$replacement = '            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { attachmentSheetOpen = true }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "添加文件")
                }
                OutlinedTextField('
$content = $content -replace $inputRowPattern, $replacement

# CHANGE 5: Simplify ModalBottomSheet - replace actionSheetOpen with attachmentSheetOpen
# First remove the old actionSheetOpen block and replace with simplified version
$oldSheetPattern = '(?s)    if \(actionSheetOpen\) \{[\s\S]*?    \}[\s\n]+\}[\s\n]+\}'
$newSheet = '    if (attachmentSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { attachmentSheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("添加文件", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { photoPicker.launch("image/*"); attachmentSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("照片") }
                    Button(onClick = { photoPicker.launch("video/*"); attachmentSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("视频") }
                    Button(onClick = { pdfPicker.launch(arrayOf("application/pdf")); attachmentSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("PDF") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")); attachmentSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("Word") }
                    Button(onClick = { textFilePicker.launch(arrayOf("text/plain", "text/markdown", "application/json")); attachmentSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("TXT/MD") }
                }
                HorizontalDivider()
                Text("导出", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        scope.launch {
                            val f = vm.exportChatMarkdown()
                            if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                        }
                        attachmentSheetOpen = false
                    }, modifier = Modifier.weight(1f)) { Text("聊天记录") }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }'
$content = $content -replace $oldSheetPattern, $newSheet

# CHANGE 6: Add new state variables after debugDumpCopied
$stateVarPattern = 'var debugDumpCopied by remember \{ mutableStateOf\(false\) \}'
$newStateVars = 'var debugDumpCopied by remember { mutableStateOf(false) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }
    var textFileModeOpen by remember { mutableStateOf(false) }
    var pendingTextFileUri by remember { mutableStateOf<String?>(null) }'
$content = $content -replace $stateVarPattern, $newStateVars

# CHANGE 7: Add textFilePicker and photoPicker after docxPicker
$docxPickerPattern = '(?s)    val docxPicker = rememberLauncherForActivityResult\(ActivityResultContracts\.OpenDocument\(\)\) \{ uri ->[\s\n]+        if \(uri != null\) \{[\s\n]+            pendingDocxUri = uri\.toString\(\)[\s\n]+            docxModeOpen = true[\s\n]+        \}[\s\n]+    \}'
$newPickers = '    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingDocxUri = uri.toString()
            docxModeOpen = true
        }
    }

    val textFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingTextFileUri = uri.toString()
            textFileModeOpen = true
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importPhotoVideo(uri)
        }
    }'
$content = $content -replace $docxPickerPattern, $newPickers

# CHANGE 8: Add text file dialog after docxModeOpen dialog
$docxDialogPattern = '(?s)    if \(docxModeOpen\) \{[\s\S]*?    \}[\s\n]+\}'
$textFileDialog = '    if (docxModeOpen) {
        val u = pendingDocxUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (u != null) {
            AlertDialog(
                onDismissRequest = { docxModeOpen = false; pendingDocxUri = null },
                confirmButton = {
                    Button(onClick = { vm.importDocx(u); docxModeOpen = false; pendingDocxUri = null }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { docxModeOpen = false; pendingDocxUri = null }) { Text("取消") } },
                title = { Text("导入 Word 文档") },
                text = { Text("将 Word 文档（.docx）解析为文本来源。") },
            )
        }
    }

    if (textFileModeOpen) {
        val u = pendingTextFileUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (u != null) {
            AlertDialog(
                onDismissRequest = { textFileModeOpen = false; pendingTextFileUri = null },
                confirmButton = {
                    Button(onClick = { vm.importTextFile(u); textFileModeOpen = false; pendingTextFileUri = null }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { textFileModeOpen = false; pendingTextFileUri = null }) { Text("取消") } },
                title = { Text("导入文本文件") },
                text = { Text("将文件内容作为纯文本发送给 AI。") },
            )
        }
    }'
$content = $content -replace $docxDialogPattern, $textFileDialog

# CHANGE 9: Remove /summary, /report, /export shortcuts
$shortcutPattern = '(?s)                            p\.startsWith\("/summary"\) -> vm\.generateSummary\(\)[\s\n]+                            p\.startsWith\("/report"\) -> vm\.generateReport\(\)[\s\n]+                            p\.startsWith\("/export"\) -> \{[\s\n]+                                scope\.launch \{[\s\n]+                                    val f = vm\.exportMarkdown\(\)[\s\n]+                                    if \(f != null\) shareFile\(context, vm\.getPackageNameForShare\(context\), f\) else snackbar\.showSnackbar\("导出失败"\)[\s\n]+                                \}[\s\n]+                            \}[\s\n]+'
$content = $content -replace $shortcutPattern, ''

# CHANGE 10: Remove unused variables (but keep tab since it's still referenced)
# We can't fully remove tab since removing TabRow might break things if tab is referenced elsewhere
# Actually, let's keep tab for now but the user said to remove it

$content = $content -replace 'var tab by rememberSaveable \{ mutableStateOf\(0\) \}\s*\n', ''

# Also remove actionSheetOpen since we replaced with attachmentSheetOpen
$content = $content -replace 'var actionSheetOpen by rememberSaveable \{ mutableStateOf\(false\) \}\s*\n', ''

# Also need to remove the reference to actionSheetOpen in the toolbar
$content = $content -replace 'IconButton\(onClick = \{ actionSheetOpen = true \}\)', 'IconButton(onClick = { attachmentSheetOpen = true })'

[System.IO.File]::WriteAllText($file, $content, [System.Text.Encoding]::UTF8)
Write-Host "All changes applied"