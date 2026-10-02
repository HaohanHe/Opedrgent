package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.cultivation.engine.CultivationEngine
import top.hsyscn.opedrgent.cultivation.model.BaselineTemplates
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline
import top.hsyscn.opedrgent.cultivation.model.VirtueDimension
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 首次人格基准建立（可跳过）。
 *
 * - 进入即载入 [BaselineTemplates.STARTER] 四维度作为可编辑脚手架，仅作参照、不做自动判定；
 * - 「载入起始模板」重置为模板；「稍后设置」与「保存并继续」都视为完成本次门控；
 * - 保存复用 [CultivationEngine.saveBaseline]，不重造基准能力；保存失败停留本屏并提示。
 */
@Composable
fun OnboardingBaselineScreen(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { CultivationEngine(context.applicationContext) }
    val scope = rememberCoroutineScope()

    data class RowState(val name: String, val doText: String)

    fun starterRows(): List<RowState> = BaselineTemplates.STARTER.map {
        RowState(name = it.name, doText = it.doBehaviors.joinToString("\n"))
    }

    var rows by remember { mutableStateOf(starterRows()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun loadTemplate() {
        rows = starterRows()
        error = null
    }

    fun save() {
        val dims = rows.map {
            VirtueDimension(
                name = it.name.trim(),
                doBehaviors = it.doText.split('\n')
                    .map { s -> s.trim() }
                    .filter { s -> s.isNotBlank() },
                dontBehaviors = emptyList(),
            )
        }.filter { it.name.isNotBlank() }
        if (dims.isEmpty()) {
            error = context.getString(R.string.onboarding_baseline_error_empty)
            return
        }
        saving = true
        error = null
        scope.launch {
            val now = System.currentTimeMillis()
            val baseline = VirtueBaseline(
                id = 0,
                version = 0,
                dimensions = dims,
                complete = true,
                createdAt = now,
                updatedAt = now,
            )
            runCatching { engine.saveBaseline(baseline) }
                .onSuccess { onFinished() }
                .onFailure {
                    saving = false
                    error = context.getString(R.string.onboarding_baseline_error_empty)
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = SpacingTokens.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(SpacingTokens.xl))

            Text(
                text = stringResource(R.string.onboarding_baseline_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(SpacingTokens.sm))
            Text(
                text = stringResource(R.string.onboarding_baseline_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(SpacingTokens.lg))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            ) {
                rows.forEachIndexed { index, row ->
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { v ->
                            rows = rows.mapIndexed { i, r ->
                                if (i == index) r.copy(name = v) else r
                            }
                        },
                        label = { Text(stringResource(R.string.onboarding_baseline_dimension_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = row.doText,
                        onValueChange = { v ->
                            rows = rows.mapIndexed { i, r ->
                                if (i == index) r.copy(doText = v) else r
                            }
                        },
                        label = { Text(stringResource(R.string.onboarding_baseline_do_hint)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedButton(
                    onClick = { loadTemplate() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_baseline_load_template))
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onFinished() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.onboarding_baseline_later))
                }
                Button(
                    onClick = { save() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.onboarding_baseline_save))
                    }
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.lg))
        }
    }
}
