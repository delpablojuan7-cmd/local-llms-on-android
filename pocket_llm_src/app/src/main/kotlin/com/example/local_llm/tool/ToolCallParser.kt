package com.example.local_llm.tool

import com.example.local_llm.agent.ToolCall
import org.json.JSONObject

/**
 * Парсер для извлечения вызовов инструментов из текста
 */
object ToolCallParser {
    private val TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun extractToolCalls(text: String): List<ToolCall> {
        return try {
            TOOL_CALL_REGEX.findAll(text).map { match ->
                val jsonString = match.groupValues[1]
                val json = JSONObject(jsonString)
                ToolCall(
                    name = json.getString("name"),
                    arguments = json.getJSONObject("arguments").let { argsJson ->
                        argsJson.keys().asSequence().associateWith { key ->
                            argsJson.getString(key)
                        }
                    }
                )
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun stripToolCalls(text: String): String {
        return TOOL_CALL_REGEX.replace(text, "").trim()
    }
}
