package com.example.local_llm.agent

/**
 * Представление вызова инструмента
 */
data class ToolCall(
    val name: String,
    val arguments: Map<String, String>
)
