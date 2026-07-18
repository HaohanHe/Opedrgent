@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.R
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.service.AutoSproutWorker
import top.hsyscn.opedrgent.service.DailyDigestNotifier
import top.hsyscn.opedrgent.storage.PersonaDetector
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

// DataStore 单例扩展
val Context.invisiblePartnerDataStore by preferencesDataStore(name = "invisible_partner_settings")

// Preferences Keys
private val KEY_AUTO_SAVE = booleanPreferencesKey("key_auto_save")
private val KEY_AUTO_SPROUT = booleanPreferencesKey("key_auto_sprout")
private val KEY_DAILY_DIGEST = booleanPreferencesKey("key_daily_digest")
private val KEY_WARM_FEEDBACK = booleanPreferencesKey("key_warm_feedback")
private val KEY_SPROUT_HOUR = intPreferencesKey("key_sprout_hour")
private val KEY_DIGEST_HOUR = intPreferencesKey("key_digest_hour")
private val KEY_MAX_SPROUT = intPreferencesKey("key_max_sprout")
private val KEY_PARTNER_PERSONA = stringPreferencesKey("partner_persona")
private val KEY_AUTO_PERSONA = booleanPreferencesKey("auto_persona_switch")

/**
 * 伙伴人格枚举 -- 定义无感伙伴的三种使用模式。
 */
enum class PartnerPersona {
    LIFE,
    WORK,
    CREATIVE;

    companion object {
        fun fromName(name: String): PartnerPersona =
            entries.find { it.name == name } ?: LIFE
    }
}

val PartnerPersona.labelResId: Int
    get() = when (this) {
        PartnerPersona.LIFE -> R.string.invisible_partner_sheng_huo_ji_lu
        PartnerPersona.WORK -> R.string.invisible_partner_xiao_lv_gong_zuo
        PartnerPersona.CREATIVE -> R.string.invisible_partner_chuang_zuo_ling_gan
    }

val PartnerPersona.descriptionResId: Int
    get() = when (this) {
        PartnerPersona.LIFE -> R.string.invisible_partner_wen_nuan_pei_ban_shi_he_ri_chang_ji_lu_he_sheng_huo_hui_yi
        PartnerPersona.WORK -> R.string.invisible_partner_zhuan_zhu_chan_chu_shi_he_hui_yi_ji_lu_he_gong_zuo_zheng_li
        PartnerPersona.CREATIVE -> R.string.invisible_partner_lian_jie_si_kao_shi_he_xie_zuo_he_chuang_yi_gong_zuo
    }

/**
 * 无感伙伴模式设置面板。
 *
 * 提供录音自动保存、自动发芽、每日推送、温暖点评、最大发芽数等 5 项配置。
 * 所有设置通过 DataStore Preferences 持久化，开关变化立即生效（调度/取消 Worker）。
 */
@Composable
fun InvisiblePartnerSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dataStore = context.invisiblePartnerDataStore
    val scope = rememberCoroutineScope()

    // 从 DataStore 读取初始值，带默认值
    var autoSaveEnabled by rememberSaveable { mutableStateOf(true) }
    var autoSproutEnabled by rememberSaveable { mutableStateOf(false) }
    var dailyDigestEnabled by rememberSaveable { mutableStateOf(false) }
    var warmFeedbackEnabled by rememberSaveable { mutableStateOf(true) }
    var sproutHour by rememberSaveable { mutableIntStateOf(23) }
    var digestHour by rememberSaveable { mutableIntStateOf(8) }
    var maxSproutCount by rememberSaveable { mutableIntStateOf(3) }
    var selectedPersona by rememberSaveable { mutableStateOf(PartnerPersona.LIFE) }
    var autoPersonaEnabled by rememberSaveable { mutableStateOf(true) }

    // 时间选择下拉菜单状态
    var showSproutHourPicker by rememberSaveable { mutableStateOf(false) }
    var showDigestHourPicker by rememberSaveable { mutableStateOf(false) }

    // 首次进入时从 DataStore 加载已保存的值
    scope.launch {
        dataStore.data.first().let { prefs ->
            autoSaveEnabled = prefs[KEY_AUTO_SAVE] ?: true
            autoSproutEnabled = prefs[KEY_AUTO_SPROUT] ?: false
            dailyDigestEnabled = prefs[KEY_DAILY_DIGEST] ?: false
            warmFeedbackEnabled = prefs[KEY_WARM_FEEDBACK] ?: true
            sproutHour = prefs[KEY_SPROUT_HOUR] ?: 23
            digestHour = prefs[KEY_DIGEST_HOUR] ?: 8
            maxSproutCount = prefs[KEY_MAX_SPROUT] ?: 3
            selectedPersona = PartnerPersona.fromName(prefs[KEY_PARTNER_PERSONA] ?: "LIFE")
            autoPersonaEnabled = prefs[KEY_AUTO_PERSONA] ?: true
        }
    }

    // 持久化到 DataStore 的辅助函数
    fun persistBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        scope.launch {
            dataStore.edit { it[key] = value }
        }
    }

    fun persistInt(key: Preferences.Key<Int>, value: Int) {
        scope.launch {
            dataStore.edit { it[key] = value }
        }
    }

    fun persistString(key: Preferences.Key<String>, value: String) {
        scope.launch {
            dataStore.edit { it[key] = value }
        }
    }

    fun onPersonaChanged(persona: PartnerPersona) {
        selectedPersona = persona
        persistString(KEY_PARTNER_PERSONA, persona.name)
        // 根据模式自动调整温暖点评状态
        when (persona) {
            PartnerPersona.WORK -> {
                warmFeedbackEnabled = false
                persistBoolean(KEY_WARM_FEEDBACK, false)
            }
            PartnerPersona.CREATIVE -> {
                if (!warmFeedbackEnabled) {
                    warmFeedbackEnabled = true
                    persistBoolean(KEY_WARM_FEEDBACK, true)
                }
            }
            PartnerPersona.LIFE -> {
                // Life 模式下恢复用户可控，不做强制修改
            }
        }
    }

    // 开关切换处理：立即持久化 + 调度/取消 Worker
    fun onAutoSaveChanged(enabled: Boolean) {
        autoSaveEnabled = enabled
        persistBoolean(KEY_AUTO_SAVE, enabled)
    }

    fun onAutoSproutChanged(enabled: Boolean) {
        autoSproutEnabled = enabled
        persistBoolean(KEY_AUTO_SPROUT, enabled)
        if (enabled) {
            AutoSproutWorker.schedule(context, sproutHour, maxSproutCount)
        } else {
            AutoSproutWorker.cancel(context)
        }
    }

    fun onDailyDigestChanged(enabled: Boolean) {
        dailyDigestEnabled = enabled
        persistBoolean(KEY_DAILY_DIGEST, enabled)
        if (enabled) {
            DailyDigestNotifier.schedule(context, digestHour)
        } else {
            DailyDigestNotifier.cancel(context)
        }
    }

    fun onWarmFeedbackChanged(enabled: Boolean) {
        warmFeedbackEnabled = enabled
        persistBoolean(KEY_WARM_FEEDBACK, enabled)
    }

    fun onSproutHourChanged(hour: Int) {
        sproutHour = hour
        persistInt(KEY_SPROUT_HOUR, hour)
        // 如果自动发芽已开启，重新调度以更新时间
        if (autoSproutEnabled) {
            AutoSproutWorker.schedule(context, hour, maxSproutCount)
        }
    }

    fun onDigestHourChanged(hour: Int) {
        digestHour = hour
        persistInt(KEY_DIGEST_HOUR, hour)
        // 如果每日推送已开启，重新调度以更新时间
        if (dailyDigestEnabled) {
            DailyDigestNotifier.schedule(context, hour)
        }
    }

    fun onMaxSproutChanged(count: Int) {
        maxSproutCount = count
        persistInt(KEY_MAX_SPROUT, count)
        if (autoSproutEnabled) {
            AutoSproutWorker.schedule(context, sproutHour, count)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_invisible_partner), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(SpacingTokens.md)
                .padding(bottom = 100.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        ) {

            // ── 0. 使用模式选择器 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.lg)) {
                    Text(stringResource(R.string.invisible_partner_shi_yong_mo_shi), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(SpacingTokens.sm))

                    // 自动切换开关
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.invisible_partner_zi_dong_qie_huan), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = stringResource(R.string.invisible_partner_gen_ju_shi_jian_nei_rong_he),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Switch(
                            checked = autoPersonaEnabled,
                            onCheckedChange = { enabled ->
                                autoPersonaEnabled = enabled
                                persistBoolean(KEY_AUTO_PERSONA, enabled)
                            },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.md))

                    if (autoPersonaEnabled) {
                        // 自动模式：显示检测结果，RadioButtons 禁用
                        val detectedPersona = remember {
                            PersonaDetector.detect(context)
                        }
                        val detectionReason = remember {
                            PersonaDetector.explainReason(context)
                        }

                        PartnerPersona.entries.forEach { persona ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = SpacingTokens.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = (detectedPersona == persona),
                                    onClick = null,  // 自动模式下不可点击
                                    enabled = false,
                                )
                                Spacer(Modifier.width(SpacingTokens.sm))
                                Column {
                                    Text(
                                        text = stringResource(persona.labelResId),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (detectedPersona == persona)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(persona.descriptionResId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(SpacingTokens.sm))

                        // 检测原因说明
                        Text(
                            text = stringResource(
                                R.string.invisible_partner_1_zi_dong_jian_ce_2,
                                stringResource(detectedPersona.labelResId),
                                detectionReason,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(Modifier.height(SpacingTokens.xs))

                        Text(
                            text = stringResource(R.string.invisible_partner_xi_tong_hui_gen_ju_shi_jian),
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                    } else {
                        // 手动模式：原有行为，RadioButton 可点击
                        PartnerPersona.entries.forEach { persona ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPersonaChanged(persona) }
                                    .padding(vertical = SpacingTokens.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = (selectedPersona == persona),
                                    onClick = { onPersonaChanged(persona) },
                                )
                                Spacer(Modifier.width(SpacingTokens.sm))
                                Column {
                                    Text(stringResource(persona.labelResId), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(persona.descriptionResId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(SpacingTokens.md))

            // ── 1. 录音自动保存 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.invisible_partner_lu_yin_zi_dong_bao_cun), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.invisible_partner_lu_yin_jie_shu_hou_zi_dong),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Switch(
                            checked = autoSaveEnabled,
                            onCheckedChange = { onAutoSaveChanged(it) },
                        )
                    }
                }
            }

            // ── 2. 自动发芽 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md), verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.invisible_partner_zi_dong_fa_ya), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.invisible_partner_mei_tian_ye_jian_zi_dong_fen),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Switch(
                            checked = autoSproutEnabled,
                            onCheckedChange = { onAutoSproutChanged(it) },
                        )
                    }
                    if (autoSproutEnabled) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showSproutHourPicker = true },
                            ) {
                                Text(
                                    text = String.format("%02d:00", sproutHour),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(SpacingTokens.xs))
                            }
                            DropdownMenu(
                                expanded = showSproutHourPicker,
                                onDismissRequest = { showSproutHourPicker = false },
                            ) {
                                (0..23).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = {
                                            onSproutHourChanged(h)
                                            showSproutHourPicker = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. 每日推送 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md), verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.invisible_partner_mei_ri_tui_song), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.invisible_partner_mei_tian_zao_shang_tui_song),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Switch(
                            checked = dailyDigestEnabled,
                            onCheckedChange = { onDailyDigestChanged(it) },
                        )
                    }
                    if (dailyDigestEnabled) {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showDigestHourPicker = true },
                            ) {
                                Text(
                                    text = String.format("%02d:00", digestHour),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(SpacingTokens.xs))
                            }
                            DropdownMenu(
                                expanded = showDigestHourPicker,
                                onDismissRequest = { showDigestHourPicker = false },
                            ) {
                                (0..23).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = {
                                            onDigestHourChanged(h)
                                            showDigestHourPicker = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 4. 温暖点评 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.invisible_partner_wen_nuan_dian_ping), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.invisible_partner_mei_ci_bao_cun_bi_ji_hou_ai),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Switch(
                            checked = warmFeedbackEnabled,
                            onCheckedChange = { onWarmFeedbackChanged(it) },
                            enabled = (selectedPersona != PartnerPersona.WORK),
                        )
                    }
                }
            }

            // ── 5. 最大发芽数 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md), verticalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.invisible_partner_zui_da_fa_ya_shu), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.invisible_partner_mei_ri_zui_duo_zi_dong_sheng),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        Text(
                            text = stringResource(R.string.invisible_partner_1_pian, maxSproutCount),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = maxSproutCount.toFloat(),
                        onValueChange = { onMaxSproutChanged(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }
            }

            HorizontalDivider()

            // 底部说明文字
            Column(modifier = Modifier.padding(vertical = SpacingTokens.sm), verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                Text(
                    text = stringResource(R.string.invisible_partner_guan_yu_wu_gan_huo_ban_mo_shi),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.invisible_partner_wu_gan_huo_ban_mo_shi_rang_ai),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        }
    }
}
