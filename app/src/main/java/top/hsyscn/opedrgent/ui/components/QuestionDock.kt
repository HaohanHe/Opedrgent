package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey

@Composable
fun QuestionDock(
    request: QuestionRequest,
    onAnswer: (List<List<String>>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = request.questions
    val isMultiQuestion = questions.size > 1
    var currentQuestionIndex by rememberSaveable { mutableIntStateOf(0) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val answers = remember {
        mutableStateMapOf<Int, Set<String>>().apply {
            questions.indices.forEach { put(it, emptySet()) }
        }
    }
    val customInputs = remember {
        mutableStateMapOf<Int, String>().apply {
            questions.indices.forEach { put(it, "") }
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (collapsed) "选择题 (${questions.size})" else (if (isMultiQuestion) "问题 ${currentQuestionIndex + 1}/${questions.size}" else questions[currentQuestionIndex].header),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { collapsed = ! collapsed },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = if (collapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (collapsed) "展开" else "折叠",
                        tint = TextGrey,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消",
                        tint = TextGrey,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val currentQ = questions[currentQuestionIndex]

                    Text(
                        text = currentQ.question,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = TextDark,
                        lineHeight = 22.sp,
                    )

                    if (currentQ.multiple) {
                        Text("（可多选）", style = MaterialTheme.typography.bodySmall, color = TextGrey)
                    }

                    val focusManager = LocalFocusManager.current
                    val focusRequester = remember { FocusRequester() }
                    var showCustomInput by rememberSaveable { mutableStateOf(false) }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(currentQ.options) { idx, opt ->
                            val isSelected = opt.label in answers.getOrDefault(currentQuestionIndex, emptySet())
                            val isCustomOption = currentQ.allowCustom && idx == currentQ.options.lastIndex

                            OptionRow(
                                label = opt.label,
                                description = opt.description,
                                selected = isSelected,
                                multiple = currentQ.multiple,
                                onClick = {
                                    if (isCustomOption) {
                                        showCustomInput = true
                                    } else {
                                        val current = answers.getOrDefault(currentQuestionIndex, emptySet())
                                        val updated = if (currentQ.multiple) {
                                            if (isSelected) current - opt.label else current + opt.label
                                        } else {
                                            setOf(opt.label)
                                        }
                                        answers[currentQuestionIndex] = updated
                                    }
                                },
                                onKeyEvent = { keyEvent ->
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> {
                                            val prevIdx = (idx - 1).coerceAtLeast(0)
                                            false
                                        }
                                        Key.DirectionDown -> {
                                            val nextIdx = (idx + 1).coerceAtMost(currentQ.options.size - 1)
                                            false
                                        }
                                        Key.Enter -> {
                                            if (!isSelected) {
                                                val current = answers.getOrDefault(currentQuestionIndex, emptySet())
                                                answers[currentQuestionIndex] = if (currentQ.multiple) {
                                                    current + opt.label
                                                } else {
                                                    setOf(opt.label)
                                                }
                                            }
                                            if (!isMultiQuestion) {
                                                submitAllAnswers(answers, customInputs, questions, onAnswer)
                                            } else if (!currentQ.multiple || !keyEvent.isShiftPressed) {
                                                moveToNextOrSubmit(currentQuestionIndex, questions, isMultiQuestion, { currentQuestionIndex = it }, { submitAllAnswers(answers, customInputs, questions, onAnswer) })
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                        }
                    }

                    AnimatedVisibility(visible = showCustomInput && currentQ.allowCustom) {
                        OutlinedTextField(
                            value = customInputs.getOrDefault(currentQuestionIndex, ""),
                            onValueChange = { customInputs[currentQuestionIndex] = it },
                            placeholder = { Text("输入自定义答案...", color = TextGrey, fontSize = 13.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                }
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                                        val customText = customInputs.getOrDefault(currentQuestionIndex, "").trim()
                                        if (customText.isNotBlank()) {
                                            val current = answers.getOrDefault(currentQuestionIndex, emptySet())
                                            answers[currentQuestionIndex] = current + "[自定义] $customText"
                                        }
                                        focusManager.clearFocus()
                                        if (!isMultiQuestion) {
                                            submitAllAnswers(answers, customInputs, questions, onAnswer)
                                        } else {
                                            moveToNextOrSubmit(currentQuestionIndex, questions, isMultiQuestion, { currentQuestionIndex = it }, { submitAllAnswers(answers, customInputs, questions, onAnswer) })
                                        }
                                        true
                                    } else if (keyEvent.key == Key.Escape) {
                                        showCustomInput = false
                                        true
                                    } else {
                                        false
                                    }
                                },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BubbleBlue,
                                unfocusedBorderColor = Color(0xFFE0E0E0),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                cursorColor = BubbleBlue,
                            ),
                        )
                    }

                    if (showCustomInput && currentQ.allowCustom) {
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = TextGrey)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isMultiQuestion && currentQuestionIndex > 0) {
                                TextButton(onClick = { currentQuestionIndex-- }) {
                                    Text("上一步")
                                }
                            }

                            val canProceed = answers.getOrDefault(currentQuestionIndex, emptySet()).isNotEmpty()

                            if (isMultiQuestion && currentQuestionIndex < questions.size - 1) {
                                androidx.compose.material3.Button(
                                    onClick = { currentQuestionIndex++ },
                                    enabled = canProceed,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text("下一步")
                                }
                            } else {
                                androidx.compose.material3.Button(
                                    onClick = { submitAllAnswers(answers, customInputs, questions, onAnswer) },
                                    enabled = canProceed,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("确认提交")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { false },
) {
    val bgColor = if (selected) BubbleBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) BubbleBlue else Color.Transparent
    val textColor = if (selected) BubbleBlue else TextDark
    val descColor = if (selected) BubbleBlue.copy(alpha = 0.8f) else TextGrey

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, BubbleBlue) else null,
        modifier = Modifier
            .fillMaxWidth()
            .onKeyEvent(onKeyEvent),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (multiple) 20.dp else 20.dp)
                    .clip(if (multiple) RoundedCornerShape(4.dp) else CircleShape)
                    .background(if (selected) BubbleBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                this@Row.AnimatedVisibility(visible = selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (multiple) 14.dp else 14.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 14.sp,
                    color = textColor,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = descColor,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun submitAllAnswers(
    answers: Map<Int, Set<String>>,
    customInputs: Map<Int, String>,
    questions: List<QuestionInfo>,
    onAnswer: (List<List<String>>) -> Unit,
) {
    val result = questions.mapIndexed { idx, _ ->
        answers.getOrDefault(idx, emptySet()).toList()
    }
    onAnswer(result)
}

private fun moveToNextOrSubmit(
    currentIndex: Int,
    questions: List<QuestionInfo>,
    isMultiQuestion: Boolean,
    onNext: (Int) -> Unit,
    onSubmit: () -> Unit,
) {
    if (isMultiQuestion && currentIndex < questions.size - 1) {
        onNext(currentIndex + 1)
    } else {
        onSubmit()
    }
}
