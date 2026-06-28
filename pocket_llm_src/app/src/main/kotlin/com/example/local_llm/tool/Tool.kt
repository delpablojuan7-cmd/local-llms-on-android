package com.example.local_llm.tool

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Интерфейс для инструментов, доступных агенту
 */
interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, String> // paramName → type/description

    /**
     * Выполнить инструмент с заданными параметрами
     */
    suspend fun execute(arguments: Map<String, String>): ToolResult
}

/**
 * Результат выполнения инструмента
 */
data class ToolResult(
    val output: String,
    val isError: Boolean = false
)
