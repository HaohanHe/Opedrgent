package top.hsyscn.opedrgent.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

/**
 * 导入文件屏幕。
 *
 * 功能：
 * - 支持导入文本文件、PDF、图片
 * - 自动识别文件类型并创建对应类型的笔记
 * - 导入成功后跳转到笔记编辑器
 *
 * 修复说明：
 * 原来首页"导入文件"按钮点击无响应（subScreen="import" 未处理），
 * 此屏幕补全了该操作链路，确保用户可以正常导入文件创建笔记。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFileScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onImportSuccess: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 导入状态
    var isImporting by remember { mutableStateOf(false) }
    var importedFileName by remember { mutableStateOf<String?>(null) }
    var importedNoteId by remember { mutableStateOf<Long?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // 文件选择器（支持多种文件类型）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            isImporting = true
            importError = null

            try {
                // 获取文件名
                val fileName = getFileName(context, uri)
                importedFileName = fileName

                // 读取文件内容
                val mimeType = context.contentResolver.getType(uri)
                val isBinary = mimeType?.startsWith("image/") == true || mimeType == "application/pdf"

                val content = if (isBinary) {
                    // 二进制文件：读取字节数组并转为 Base64，避免乱码
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    } else ""
                } else {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    } ?: ""
                }

                if (content.isBlank()) {
                    importError = "文件内容为空"
                    snackbarHostState.showSnackbar("文件内容为空")
                    return@launch
                }

                // 根据文件类型确定笔记类型
                val noteType = when {
                    mimeType?.startsWith("image/") == true -> NoteType.IMAGE
                    mimeType == "application/pdf" -> NoteType.PDF
                    else -> NoteType.TEXT
                }

                // 创建笔记
                vm.createNoteFromText(
                    title = fileName,
                    content = content,
                    type = noteType
                )

                snackbarHostState.showSnackbar("导入成功")

                // 延迟一下让用户看到成功提示，然后跳转
                kotlinx.coroutines.delay(500)
                onImportSuccess(-1)

            } catch (e: Exception) {
                importError = "导入失败: ${e.message}"
                snackbarHostState.showSnackbar("导入失败: ${e.message}")
            } finally {
                isImporting = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = themeBgGray(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_import_file), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 说明文字
            Text(
                text = "选择要导入的文件",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = themeTextDark(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持文本文件(.txt)、PDF、图片等格式\n导入后将自动创建为笔记",
                fontSize = 14.sp,
                color = themeTextGrey(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 文件类型选择卡片
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 文本文件
                ImportFileTypeCard(
                    icon = Icons.Default.Description,
                    title = "文本文件",
                    description = ".txt, .md 等纯文本格式",
                    onClick = {
                        filePickerLauncher.launch(arrayOf("text/*"))
                    },
                )
                // PDF 文件
                ImportFileTypeCard(
                    icon = Icons.Default.InsertDriveFile,
                    title = "PDF 文档",
                    description = ".pdf 格式文档",
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/pdf"))
                    },
                )
                // 图片
                ImportFileTypeCard(
                    icon = Icons.Default.Image,
                    title = "图片",
                    description = ".jpg, .png 等图片格式",
                    onClick = {
                        filePickerLauncher.launch(arrayOf("image/*"))
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 通用文件选择按钮
            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("选择其他类型文件", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 导入状态显示
            when {
                isImporting -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = AccentBlue)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在导入...", color = themeTextGrey())
                            importedFileName?.let { name ->
                                Text(name, fontSize = 12.sp, color = themeTextGrey())
                            }
                        }
                    }
                }
                importError != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("导入失败", fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(importError!!, fontSize = 13.sp, color = themeTextGrey())
                        }
                    }
                }
                importedNoteId != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("导入成功", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(importedFileName ?: "", fontSize = 13.sp, color = themeTextGrey())
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = onBack) {
                                Text("返回")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

/**
 * 导入文件类型选择卡片。
 */
@Composable
private fun ImportFileTypeCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(AccentBlue.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = themeTextDark())
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, fontSize = 12.sp, color = themeTextGrey())
            }
        }
    }
}

/**
 * 从 URI 获取文件名。
 */
private fun getFileName(context: Context, uri: android.net.Uri): String {
    var result = ""
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) result = it.getString(columnIndex)
            }
        }
    }
    if (result.isBlank()) {
        result = uri.path?.substringAfterLast('/') ?: "未知文件"
    }
    return result
}
