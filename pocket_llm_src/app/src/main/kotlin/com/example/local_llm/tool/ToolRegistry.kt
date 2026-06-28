package com.example.local_llm.tool

/**
 * Реестр доступных инструментов для Agent Mode
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? {
        return tools[name]
    }

    fun getAll(): List<Tool> {
        return tools.values.toList()
    }

    fun getSystemPromptDescription(): String {
        val toolDescriptions = tools.values.joinToString("\n") { tool ->
            val params = tool.parameters.entries.joinToString(", ") { (k, v) ->
                "$k ($v)"
            }
            "- ${tool.name}: ${tool.description} Parameters: $params"
        }
        return """You are a helpful assistant with access to tools.
When you need to use a tool, output exactly:
<tool_call>{"name":"tool_name","arguments":{"key":"value"}}</tool_call>

Available tools:
$toolDescriptions

After using a tool, you will receive the results and should continue your response based on those results."""
    }
}
