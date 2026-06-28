package com.example.local_llm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.local_llm.agent.AgentModeController
import com.example.local_llm.backend.BackendResponse
import com.example.local_llm.backend.ChatBackend
import com.example.local_llm.backend.ChatTurn
import com.example.local_llm.tool.FileSystemTool
import com.example.local_llm.tool.ToolRegistry
import com.example.local_llm.tool.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Controller для управления чатом с поддержкой Agent Mode
 * Интегрирует обычный режим чата и Agent Mode с tool calling
 */
class PersistentChatController(
    private val backend: ChatBackend,
    private val appContext: Context,
    private val externalScope: CoroutineScope
) {
    // UI состояние
    var chatHistory by mutableStateOf<List<ChatTurn>>(emptyList())
    var isAgentModeEnabled by mutableStateOf(false)
    var isProcessing by mutableStateOf(false)
    var currentToolName by mutableStateOf<String?>(null) // "web_search", "file_system" или null

    // Инициализация Tool Registry
    private val toolRegistry = ToolRegistry().apply {
        register(WebSearchTool())
        register(FileSystemTool(appContext))
    }

    // Agent Mode Controller
    private val agentController = AgentModeController(
        backend = backend,
        toolRegistry = toolRegistry,
        scope = externalScope
    )

    /**
     * Отправить пользовательский промпт и получить ответ
     */
    fun sendPrompt(userPrompt: String) {
        isProcessing = true
        externalScope.launch {
            try {
                val response = if (isAgentModeEnabled) {
                    // Режим агента - с tool calling
                    agentController.runAgentTurn(
                        history = chatHistory,
                        userPrompt = userPrompt,
                        onPartial = { partialResponse ->
                            // Обновляем UI с частичным ответом
                            updateLastMessage(partialResponse.text)
                        },
                        onToolCall = { toolCall ->
                            // Показываем индикатор инструмента
                            currentToolName = toolCall.name
                        },
                        onIterationStart = { iteration ->
                            // Debug: начало новой итерации
                        },
                        onIterationEnd = { iteration, toolWasUsed ->
                            // Debug: завершение итерации
                            if (!toolWasUsed) {
                                currentToolName = null
                            }
                        }
                    )
                } else {
                    // Обычный режим - без tool calling
                    backend.streamReply(
                        history = chatHistory,
                        thinkingEnabled = false,
                        onPartial = { partialResponse ->
                            updateLastMessage(partialResponse.text)
                        }
                    )
                }

                // Добавляем пользовательское сообщение и ответ в историю
                chatHistory = chatHistory +
                        ChatTurn(role = "user", text = userPrompt) +
                        ChatTurn(role = "assistant", text = response.text)

                currentToolName = null
            } catch (e: Exception) {
                // Обработка ошибки
                chatHistory = chatHistory +
                        ChatTurn(role = "user", text = userPrompt) +
                        ChatTurn(
                            role = "assistant",
                            text = "Error: ${e.message}"
                        )
                currentToolName = null
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Обновить последнее сообщение ассистента (для streaming)
     */
    private fun updateLastMessage(text: String) {
        if (chatHistory.isNotEmpty() && chatHistory.last().role == "assistant") {
            val newHistory = chatHistory.dropLast(1) + ChatTurn(role = "assistant", text = text)
            chatHistory = newHistory
        }
    }

    /**
     * Переключить режим агента
     */
    fun toggleAgentMode(enabled: Boolean) {
        isAgentModeEnabled = enabled
    }

    /**
     * Получить список доступных инструментов (для отображения в UI)
     */
    fun getAvailableTools() = toolRegistry.getAll()

    /**
     * Очистить историю чата
     */
    fun clearHistory() {
        chatHistory = emptyList()
    }
}
