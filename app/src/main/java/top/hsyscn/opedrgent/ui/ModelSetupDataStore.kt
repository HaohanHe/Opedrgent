package top.hsyscn.opedrgent.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.modelSetupDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_setup_settings")

/**
 * 记录用户是否已在「离线模型准备」引导中选择「稍后」。
 *
 * 仅作为首次路由门控：deferred 后不再拦截启动，各功能面自行以内联门控卡引导下载。
 */
object ModelSetupDataStore {
    private val KEY_DEFERRED = booleanPreferencesKey("model_setup_deferred")

    fun isDeferred(context: Context): Flow<Boolean> {
        return context.modelSetupDataStore.data.map { it[KEY_DEFERRED] ?: false }
    }

    suspend fun markDeferred(context: Context) {
        context.modelSetupDataStore.edit { it[KEY_DEFERRED] = true }
    }
}
