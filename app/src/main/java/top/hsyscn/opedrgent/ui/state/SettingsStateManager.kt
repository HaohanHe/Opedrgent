package top.hsyscn.opedrgent.ui.state

import top.hsyscn.opedrgent.settings.ApiSettings

/**
 * 应用设置状态管理器。
 *
 * 封装 ApiSettings 中与 UI 状态相关的常用读写操作，
 * 避免 MainViewModel 直接持有大量设置代理方法。
 */
class SettingsStateManager(private val apiSettings: ApiSettings) {

    fun getAppLanguage(): String = apiSettings.getAppLanguage()
    fun saveAppLanguage(lang: String) = apiSettings.saveAppLanguage(lang)

    fun getEditorMode(): String = apiSettings.getEditorMode()
    fun saveEditorMode(mode: String) = apiSettings.saveEditorMode(mode)

    fun getThemeMode(): String = apiSettings.getThemeMode()
    fun saveThemeMode(mode: String) = apiSettings.saveThemeMode(mode)

    fun isDynamicColorEnabled(): Boolean = apiSettings.isDynamicColorEnabled()
    fun saveDynamicColorEnabled(enabled: Boolean) = apiSettings.saveDynamicColorEnabled(enabled)

    fun getSelectedLocalModel(): String = apiSettings.getSelectedLocalModel()
    fun saveSelectedLocalModel(model: String) = apiSettings.saveSelectedLocalModel(model)

    fun isAutoGenerateNoteTitle(): Boolean = apiSettings.isAutoGenerateNoteTitle()
    fun saveAutoGenerateNoteTitle(enabled: Boolean) = apiSettings.saveAutoGenerateNoteTitle(enabled)

    fun getApiKey(): String? = apiSettings.getApiKey()
}
