package com.example.local_llm.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Инструмент для работы с файловой системой
 * Поддерживает: чтение, запись, листинг и поиск файлов
 */
class FileSystemTool(private val context: Context) : Tool {
    override val name = "file_system"
    override val description = "Read, write, list, and search files and directories on device."
    override val parameters = mapOf(
        "action" to "string - read|write|list|search",
        "path" to "string - file or directory path",
        "content" to "string - content to write (for write action)",
        "pattern" to "string - search pattern (for search action)"
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val action = arguments["action"] ?: return@withContext ToolResult(
                    "Missing 'action' parameter",
                    isError = true
                )
                val path = arguments["path"] ?: return@withContext ToolResult(
                    "Missing 'path' parameter",
                    isError = true
                )

                when (action.lowercase()) {
                    "read" -> readFile(path)
                    "write" -> {
                        val content = arguments["content"] ?: ""
                        writeFile(path, content)
                    }
                    "list" -> listDirectory(path)
                    "search" -> {
                        val pattern = arguments["pattern"] ?: "*"
                        searchFiles(path, pattern)
                    }
                    else -> ToolResult("Unknown action: $action", isError = true)
                }
            } catch (e: Exception) {
                ToolResult("File system error: ${e.message}", isError = true)
            }
        }
    }

    private fun readFile(path: String): ToolResult {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return ToolResult("File not found: $path", isError = true)
            }
            if (!file.isFile) {
                return ToolResult("Path is not a file: $path", isError = true)
            }
            if (!isPathAllowed(file)) {
                return ToolResult("Access denied: $path", isError = true)
            }
            val content = file.readText(Charsets.UTF_8)
            ToolResult("File contents (${content.length} chars):\n$content")
        } catch (e: Exception) {
            ToolResult("Error reading file: ${e.message}", isError = true)
        }
    }

    private fun writeFile(path: String, content: String): ToolResult {
        return try {
            val file = File(path)
            if (!isPathAllowed(file)) {
                return ToolResult("Access denied: $path", isError = true)
            }
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            ToolResult("File written successfully: $path (${content.length} chars)")
        } catch (e: Exception) {
            ToolResult("Error writing file: ${e.message}", isError = true)
        }
    }

    private fun listDirectory(path: String): ToolResult {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return ToolResult("Directory not found: $path", isError = true)
            }
            if (!file.isDirectory) {
                return ToolResult("Path is not a directory: $path", isError = true)
            }
            if (!isPathAllowed(file)) {
                return ToolResult("Access denied: $path", isError = true)
            }

            val items = file.listFiles()?.map { f ->
                val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                val size = if (f.isFile) " (${f.length()} bytes)" else ""
                "$type ${f.name}$size"
            }?.sorted() ?: emptyList()

            if (items.isEmpty()) {
                ToolResult("Directory is empty: $path")
            } else {
                ToolResult("Contents of $path (${items.size} items):\n" + items.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult("Error listing directory: ${e.message}", isError = true)
        }
    }

    private fun searchFiles(basePath: String, pattern: String): ToolResult {
        return try {
            val baseFile = File(basePath)
            if (!baseFile.exists()) {
                return ToolResult("Path not found: $basePath", isError = true)
            }
            if (!isPathAllowed(baseFile)) {
                return ToolResult("Access denied: $basePath", isError = true)
            }

            val results = mutableListOf<String>()
            val regex = pattern.replace(".", "\\.").replace("*", ".*").toRegex()

            fun searchRecursive(dir: File) {
                if (results.size > 50) return // Ограничение на количество результатов
                try {
                    dir.listFiles()?.forEach { file ->
                        if (file.name.matches(regex)) {
                            results.add(file.absolutePath)
                        }
                        if (file.isDirectory && isPathAllowed(file)) {
                            searchRecursive(file)
                        }
                    }
                } catch (e: Exception) {
                    // Пропускаем ошибки доступа
                }
            }

            searchRecursive(baseFile)

            if (results.isEmpty()) {
                ToolResult("No files matching pattern '$pattern' found in $basePath")
            } else {
                ToolResult("Found ${results.size} files matching '$pattern':\n" + results.take(20).joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult("Error searching files: ${e.message}", isError = true)
        }
    }

    private fun isPathAllowed(file: File): Boolean {
        return try {
            val path = file.canonicalPath
            // Разрешаем доступ к публичным директориям и кэшу приложения
            path.startsWith("/data/data/com.example.local_llm") ||
            path.startsWith("/storage/emulated") ||
            path.startsWith("/sdcard") ||
            path.startsWith("/cache")
        } catch (e: Exception) {
            false
        }
    }
}
