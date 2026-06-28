package com.example.local_llm.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Данные настроек приложения, включая параметры Agent Mode
 */
data class AppSettings(
    // Общие настройки
    val selectedBackend: String = "onnx",
    val modelName: String = "qwen",
    val temperature: Float = 0.7f,
    
    // Agent Mode настройки
    val agentModeEnabled: Boolean = false,
    val agentMaxIterations: Int = 3,
    val webSearchEnabled: Boolean = true,
    val fileSystemEnabled: Boolean = true
)

/**
 * Store для управления и сохранения настроек приложения
 * Использует SharedPreferences для персистентного хранения
 */
class AppSettingsStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_AGENT_MODE_ENABLED = "agent_mode_enabled"
        private const val KEY_AGENT_MAX_ITERATIONS = "agent_max_iterations"
        private const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        private const val KEY_FILE_SYSTEM_ENABLED = "file_system_enabled"
        private const val KEY_SELECTED_BACKEND = "selected_backend"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_TEMPERATURE = "temperature"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var settings by mutableStateOf(
        AppSettings(
            selectedBackend = prefs.getString(KEY_SELECTED_BACKEND, "onnx") ?: "onnx",
            modelName = prefs.getString(KEY_MODEL_NAME, "qwen") ?: "qwen",
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
            agentModeEnabled = prefs.getBoolean(KEY_AGENT_MODE_ENABLED, false),
            agentMaxIterations = prefs.getInt(KEY_AGENT_MAX_ITERATIONS, 3),
            webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true),
            fileSystemEnabled = prefs.getBoolean(KEY_FILE_SYSTEM_ENABLED, true)
        )
    )
        private set

    /**
     * Сохранить настройку агента
     */
    fun setAgentModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_MODE_ENABLED, enabled).apply()
        settings = settings.copy(agentModeEnabled = enabled)
    }

    /**
     * Установить максимальное количество итераций агента
     */
    fun setAgentMaxIterations(iterations: Int) {
        prefs.edit().putInt(KEY_AGENT_MAX_ITERATIONS, iterations).apply()
        settings = settings.copy(agentMaxIterations = iterations.coerceIn(1, 10))
    }

    /**
     * Включить/отключить web search
     */
    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, enabled).apply()
        settings = settings.copy(webSearchEnabled = enabled)
    }

    /**
     * Включить/отключить доступ к файловой системе
     */
    fun setFileSystemEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_SYSTEM_ENABLED, enabled).apply()
        settings = settings.copy(fileSystemEnabled = enabled)
    }

    /**
     * Установить выбранный backend
     */
    fun setSelectedBackend(backend: String) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend).apply()
        settings = settings.copy(selectedBackend = backend)
    }

    /**
     * Установить модель
     */
    fun setModelName(modelName: String) {
        prefs.edit().putString(KEY_MODEL_NAME, modelName).apply()
        settings = settings.copy(modelName = modelName)
    }

    /**
     * Установить температуру
     */
    fun setTemperature(temperature: Float) {
        prefs.edit().putFloat(KEY_TEMPERATURE, temperature).apply()
        settings = settings.copy(temperature = temperature.coerceIn(0f, 1f))
    }
}
