package top.hsyscn.opedrgent.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import java.util.Locale

@Composable
fun HoldToDictate(
    onSpeechResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartTime by remember { mutableLongStateOf(0L) }
    var elapsedTime by remember { mutableLongStateOf(0L) }

    val holdToSpeakLabel = stringResource(R.string.cd_hold_to_speak)
    val recordingLabel = stringResource(R.string.cd_recording_in_progress)
    val releaseToSendLabel = stringResource(R.string.cd_release_to_send)
    val startRecordingLabel = stringResource(R.string.cd_start_recording)
    val stateNotRecording = stringResource(R.string.state_not_recording)
    val stateRecording = stringResource(R.string.state_recording)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring"
    )

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingStartTime = System.currentTimeMillis()
            while (isActive && isRecording) {
                elapsedTime = System.currentTimeMillis() - recordingStartTime
                delay(100L)
            }
        } else {
            elapsedTime = 0L
        }
    }

    val speechRecognizer = remember {
        createSpeechRecognizer(context, onSpeechResult) {
            isRecording = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SizeTokens.fabSize)
            .semantics(mergeDescendants = true) {
                contentDescription = if (isRecording) recordingLabel else holdToSpeakLabel
                stateDescription = if (isRecording) stateRecording else stateNotRecording
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
                onClick(label = if (isRecording) releaseToSendLabel else startRecordingLabel) {
                    if (isRecording) {
                        val duration = System.currentTimeMillis() - recordingStartTime
                        if (duration >= 500) {
                            stopListening(speechRecognizer)
                        } else {
                            speechRecognizer?.cancel()
                        }
                        isRecording = false
                    } else {
                        isRecording = true
                        recordingStartTime = System.currentTimeMillis()
                        startListening(speechRecognizer, context)
                    }
                    true
                }
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        try {
                            isRecording = true
                            startListening(speechRecognizer, context)
                            awaitRelease()
                        } finally {
                            isRecording = false
                            val duration = System.currentTimeMillis() - recordingStartTime
                            if (duration >= 500) {
                                stopListening(speechRecognizer)
                            } else {
                                speechRecognizer?.cancel()
                            }
                        }
                    }
                )
            }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(SizeTokens.fabSize)
                    .scale(ringScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.customColors.dangerRed.copy(alpha = ringAlpha))
            )
        }

        Box(
            modifier = Modifier
                .size(if (isRecording) SizeTokens.sectionIcon else SizeTokens.fabSize)
                .scale(if (isRecording) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.customColors.dangerRed else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = if (isRecording) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_btn_speak_now),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(SizeTokens.listActionIconSize)
            )
        }

        if (isRecording && elapsedTime > 0L) {
            Text(
                text = formatDuration(elapsedTime),
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.customColors.dangerRed),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

private fun createSpeechRecognizer(
    context: Context,
    onResult: (String) -> Unit,
    onStop: () -> Unit
): SpeechRecognizer? {
    return if (SpeechRecognizer.isRecognitionAvailable(context)) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    onStop()
                }

                override fun onError(error: Int) {
                    onStop()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        onResult(matches[0])
                    }
                    onStop()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        recognizer
    } else null
}

private fun startListening(recognizer: SpeechRecognizer?, context: Context) {
    recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    })
}

private fun stopListening(recognizer: SpeechRecognizer?) {
    recognizer?.stopListening()
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Preview(showBackground = true, name = "Light")
@Composable
private fun HoldToDictatePreview() {
    OpedrgentTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(SpacingTokens.xxl)) {
            HoldToDictate(onSpeechResult = {})
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun HoldToDictateDarkPreview() {
    OpedrgentTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(SpacingTokens.xxl)) {
            HoldToDictate(onSpeechResult = {})
        }
    }
}
