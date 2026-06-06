package top.hsyscn.opedrgent.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.storage.KbDocument
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val knowledgeBase = remember { KnowledgeBase(context) }
    var documents by remember { mutableStateOf<List<KbDocument>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isLoading = true
            scope.launch {
                try {
                    val result = knowledgeBase.addFile(uri)
                    if (result.success) {
                        documents = knowledgeBase.getAllDocuments()
                        snackbar.showSnackbar("已添加: ${result.document?.title}")
                    } else {
                        snackbar.showSnackbar("添加失败: ${result.error}")
                    }
                } catch (e: Exception) {
                    snackbar.showSnackbar("添加失败: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        knowledgeBase.initialize()
        documents = knowledgeBase.getAllDocuments()
    }

    val filteredDocs = if (searchQuery.isBlank()) {
        documents
    } else {
        documents.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.fileName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识库", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (documents.isNotEmpty()) {
                        Text(
                            text = "${documents.size} 篇",
                            color = TextGrey,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = BgGray,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    filePicker.launch(arrayOf(
                        "application/pdf",
                        "text/plain",
                        "text/markdown",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "image/*",
                    ))
                },
                containerColor = AccentBlue,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加文件",
                    tint = Color.White,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索知识库...", color = TextGrey) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextGrey, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除", tint = TextGrey, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            // Loading indicator
            AnimatedVisibility(visible = isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在导入...", color = TextGrey, fontSize = 13.sp)
                }
            }

            // Document list
            if (filteredDocs.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Article,
                            contentDescription = null,
                            tint = TextGrey.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (documents.isEmpty()) "知识库为空" else "未找到匹配文档",
                            color = TextGrey,
                            fontSize = 14.sp,
                        )
                        if (documents.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "点击右下角 + 添加文件",
                                color = TextGrey.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        DocumentCard(
                            document = doc,
                            onDelete = {
                                knowledgeBase.deleteDocument(doc.id)
                                documents = knowledgeBase.getAllDocuments()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: KbDocument,
    onDelete: () -> Unit,
) {
    val icon = when (document.fileType) {
        "pdf" -> Icons.Default.PictureAsPdf
        "jpg", "jpeg", "png", "bmp", "webp" -> Icons.Default.Image
        "docx", "doc" -> Icons.Default.Description
        else -> Icons.Default.TextSnippet
    }

    val iconTint = when (document.fileType) {
        "pdf" -> Color(0xFFE53935)
        "jpg", "jpeg", "png", "bmp", "webp" -> Color(0xFF43A047)
        "docx", "doc" -> Color(0xFF1E88E5)
        else -> AccentBlue
    }

    val dateStr = remember(document.addedAtMs) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(document.addedAtMs))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconTint.copy(alpha = 0.1f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${document.fileType.uppercase()} · ${formatFileSize(document.fileSizeBytes)} · $dateStr",
                    color = TextGrey,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Text(
                    text = "${document.contentLength} 字",
                    color = TextGrey.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = TextGrey,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
