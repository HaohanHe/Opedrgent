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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

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
    var isSubmitting by remember { mutableStateOf(false) } // 提交状态反馈
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
    val customAnswerPrefix = stringResource(R.string.question_custom_prefix)

    Card(
        modifier = modifier,
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.lg),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (collapsed) {
                        stringResource(R.string.question_count_label, questions.size)
                    } else if (isMultiQuestion) {
                        stringResource(R.string.question_progress_label, currentQuestionIndex + 1, questions.size)
                    } else {
                        questions[currentQuestionIndex].header.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.question_tool_default_prompt)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = themeTextDark(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { collapsed = ! collapsed },
                    modifier = Modifier.size(SpacingTokens.xl),
                ) {
                    Icon(
                        imageVector = if (collapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(if (collapsed) R.string.action_expand else R.string.action_collapse),
                        tint = themeTextGrey(),
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(SpacingTokens.xl),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = themeTextGrey(),
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                }
            }

            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    val currentQ = questions[currentQuestionIndex]

                    Text(
                        text = currentQ.question,
                        style = MaterialTheme.typography.bodyLarge,
                        color = themeTextDark(),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (currentQ.multiple) {
                        Text(stringResource(R.string.question_multi_select_hint), style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                    }

                    val focusManager = LocalFocusManager.current
                    val focusRequester = remember { FocusRequester() }
                    var showCustomInput by rememberSaveable { mutableStateOf(false) }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = SizeTokens.citationListMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    ) {
                        itemsIndexed(currentQ.options, key = { _, opt -> opt.label }) { idx, opt ->
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
                            placeholder = { Text(stringResource(R.string.question_custom_answer_placeholder), color = themeTextGrey(), style = MaterialTheme.typography.bodySmall) },
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
                                            answers[currentQuestionIndex] = current + customAnswerPrefix.format(customText)
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
                            shape = ShapeTokens.smallShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    if (showCustomInput && currentQ.allowCustom) {
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    }

                    Spacer(modifier = Modifier.height(SpacingTokens.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel), color = themeTextGrey())
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                            if (isMultiQuestion && currentQuestionIndex > 0) {
                                TextButton(onClick = { currentQuestionIndex-- }) {
                                    Text(stringResource(R.string.question_previous))
                                }
                            }

                            val canProceed = answers.getOrDefault(currentQuestionIndex, emptySet()).isNotEmpty()

                            if (isMultiQuestion && currentQuestionIndex < questions.size - 1) {
                                androidx.compose.material3.Button(
                                    onClick = { currentQuestionIndex++ },
                                    enabled = canProceed,
                                    shape = ShapeTokens.smallShape,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                                ) {
                                    Text(stringResource(R.string.question_next))
                                }
                            } else {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        if (isSubmitting) return@Button
                                        isSubmitting = true
                                        submitAllAnswers(answers, customInputs, questions, onAnswer)
                                    },
                                    enabled = canProceed && !isSubmitting,
                                    shape = ShapeTokens.smallShape,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                                ) {
                                    if (isSubmitting) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(SizeTokens.iconSm),
                                            strokeWidth = SizeTokens.borderWidth,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.question_submit_cd), modifier = Modifier.size(SizeTokens.iconSm))
                                    }
                                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                                    Text(stringResource(if (isSubmitting) R.string.question_submitting else R.string.question_confirm_submit))
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val bgColor = if (selected) primaryColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) primaryColor else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    val textColor = if (selected) primaryColor else themeTextDark()
    val descColor = if (selected) primaryColor.copy(alpha = 0.8f) else themeTextGrey()

    val stateSelectedLabel = stringResource(R.string.state_selected)
    val stateNotSelectedLabel = stringResource(R.string.state_not_selected)

    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (selected) androidx.compose.foundation.BorderStroke(SizeTokens.borderWidth, primaryColor) else null,
        modifier = Modifier
            .fillMaxWidth()
            .onKeyEvent(onKeyEvent)
            .semantics(mergeDescendants = true) {
                role = if (multiple) Role.Checkbox else Role.RadioButton
                stateDescription = if (selected) stateSelectedLabel else stateNotSelectedLabel
            },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(SpacingTokens.lg)
                    .clip(if (multiple) ShapeTokens.extraSmallShape else CircleShape)
                    .background(if (selected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                this@Row.AnimatedVisibility(visible = selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SpacingTokens.sm),
                    )
                }
            }
            Spacer(modifier = Modifier.width(SpacingTokens.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = descColor,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
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
