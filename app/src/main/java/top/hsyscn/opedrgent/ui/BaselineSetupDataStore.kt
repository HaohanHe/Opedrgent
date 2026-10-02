package top.hsyscn.opedrgent.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.baselineSetupDataStore: DataStore<Preferences> by preferencesDataStore(name = "baseline_setup_settings")

/**
 * 记录用户是否已完成（或主动跳过）首次人格基准建立。
 *
 * 仅作为首启流程的路由门控：onboarding 完成后、端侧模型准备前出现一次；
 * 无论保存还是「稍后设置」都标记为 done，不再拦截。日后用户可在批判镜中编辑基准。
 */
object BaselineSetupDataStore {
    private val KEY_DONE = booleanPreferencesKey("baseline_setup_done")

    fun isDone(context: Context): Flow<Boolean> {
        return context.baselineSetupDataStore.data.map { it[KEY_DONE] ?: false }
    }

    suspend fun markDone(context: Context) {
        context.baselineSetupDataStore.edit { it[KEY_DONE] = true }
    }
}
