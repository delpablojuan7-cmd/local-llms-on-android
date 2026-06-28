package com.example.local_llm.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Инструмент для поиска информации в интернете через DuckDuckGo
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Search the internet for current information using DuckDuckGo."
    override val parameters = mapOf(
        "query" to "string - the search query"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(java.util.concurrent.TimeUnit.SECONDS.toMillis(10).toInt(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(java.util.concurrent.TimeUnit.SECONDS.toMillis(15).toInt(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["query"] ?: return ToolResult("No query provided", isError = true)
        return withContext(Dispatchers.IO) {
            try {
                val results = searchDuckDuckGo(query)
                if (results.isEmpty()) {
                    ToolResult("No results found for query: $query")
                } else {
                    ToolResult(results.take(3).joinToString("\n\n"))
                }
            } catch (e: Exception) {
                ToolResult("Search error: ${e.message}", isError = true)
            }
        }
    }

    private fun searchDuckDuckGo(query: String): List<String> {
        return try {
            val url = "https://duckduckgo.com/?q=${query.replace(" ", "+")}&t=h_&ia=web"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return emptyList()

                val html = resp.body?.string() ?: return emptyList()
                parseSearchResults(html)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSearchResults(html: String): List<String> {
        return try {
            val doc = Jsoup.parse(html)
            val results = mutableListOf<String>()

            // Ищем результаты поиска в результатах DuckDuckGo
            doc.select(".result__body").forEach { element ->
                try {
                    val title = element.select(".result__title").text()
                    val snippet = element.select(".result__snippet").text()
                    if (title.isNotEmpty() && snippet.isNotEmpty()) {
                        results.add("**$title**\n$snippet")
                    }
                } catch (e: Exception) {
                    // Пропускаем ошибочные элементы
                }
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
