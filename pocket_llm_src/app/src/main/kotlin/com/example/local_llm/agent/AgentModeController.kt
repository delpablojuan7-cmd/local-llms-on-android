package com.example.local_llm.agent

import com.example.local_llm.backend.BackendResponse
import com.example.local_llm.backend.ChatBackend
import com.example.local_llm.backend.ChatTurn
import com.example.local_llm.tool.ToolCallParser
import com.example.local_llm.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Оркестратор для Agent Mode
 * Управляет циклом: LLM генерация -> парсинг tool calls -> выполнение инструментов -> повторный вызов LLM
 */
class AgentModeController(
    private val backend: ChatBackend,
    private val toolRegistry: ToolRegistry,
    private val scope: CoroutineScope
) {
    companion object {
        private const val MAX_ITERATIONS = 3
        private const val ITERATION_TIMEOUT_MS = 30000L
    }

    /**
     * System prompt для активации tool calling в локальной модели
     */
    private val agentSystemPrompt = """
You are a helpful assistant with access to tools.
When you need to use a tool, output exactly in this format:
<tool_call>{"name":"tool_name","arguments":{"key":"value"}}</tool_call>

Available tools:
${toolRegistry.getSystemPromptDescription()}

If using a tool, use it first, then incorporate results into your response.
"""

    /**
     * Запустить Agent Mode цикл для обработки пользовательского запроса
     *
     * @param history История чата
     * @param userPrompt Запрос пользователя
     * @param onPartial Callback для частичных ответов (streaming)
     * @param onToolCall Callback при выполнении инструмента
     * @param onIterationStart Callback при начале новой итерации
     * @param onIterationEnd Callback при завершении итерации
     * @return Финальный ответ от ассистента
     */
    suspend fun runAgentTurn(
        history: List<ChatTurn>,
        userPrompt: String,
        onPartial: (BackendResponse) -> Unit = {},
        onToolCall: (ToolCall) -> Unit = {},
        onIterationStart: (Int) -> Unit = {},
        onIterationEnd: (Int, Boolean) -> Unit = { _, _ -> }
    ): BackendResponse = withContext(Dispatchers.Default) {
        var currentHistory = history + ChatTurn(role = "user", text = userPrompt)
        var finalResponse: BackendResponse? = null
        var toolWasUsed: Boolean

        repeat(MAX_ITERATIONS) { iteration ->
            onIterationStart(iteration + 1)

            // 1. Генерация ответа от LLM
            val response = try {
                backend.streamReply(
                    history = currentHistory,
                    thinkingEnabled = false,
                    modelInstruction = agentSystemPrompt,
                    onPartial = onPartial
                )
            } catch (e: Exception) {
                onIterationEnd(iteration + 1, false)
                return@repeat
            }

            // 2. Парсинг tool calls из ответа
            val toolCalls = ToolCallParser.extractToolCalls(response.text)
            toolWasUsed = toolCalls.isNotEmpty()

            if (!toolWasUsed) {
                // Нет tool calls - это финальный ответ
                finalResponse = response
                onIterationEnd(iteration + 1, false)
                return@repeat
            }

            // 3. Выполнение инструментов
            val toolResults = mutableListOf<String>()
            for (toolCall in toolCalls) {
                onToolCall(toolCall)
                val tool = toolRegistry.get(toolCall.name)
                if (tool != null) {
                    try {
                        val result = tool.execute(toolCall.arguments)
                        toolResults.add("Tool: ${toolCall.name}\nResult: ${result.output}")
                    } catch (e: Exception) {
                        toolResults.add("Tool: ${toolCall.name}\nError: ${e.message}")
                    }
                } else {
                    toolResults.add("Tool: ${toolCall.name}\nError: Tool not found")
                }
            }

            // 4. Добавление результатов в историю
            val toolResultText = toolResults.joinToString("\n---\n")
            currentHistory = currentHistory +
                    ChatTurn(role = "assistant", text = response.text) +
                    ChatTurn(
                        role = "user",
                        text = "[Tool execution results]\n$toolResultText\n\n[Please continue your response based on these results, or use more tools if needed]"
                    )

            onIterationEnd(iteration + 1, true)
        }

        // Если исчерпаны итерации, но последний ответ был с tool calls, возвращаем его
        finalResponse ?: BackendResponse(
            text = "Agent mode: maximum iterations reached. Please try again with a simpler request.",
            isError = true
        )
    }
}
