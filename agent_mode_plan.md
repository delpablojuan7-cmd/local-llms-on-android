# План добавления Agent Mode в Pocket LLM (Android)

> **Цель**: Добавить в приложение Pocket LLM режим агента с поддержкой tool calling, web search, чтения/записи/поиска файлов и каталогов, собрать APK и выложить в репозиторий.

---

## 1. Анализ текущей архитектуры проекта

### Структура слоёв
- **UI-слой**: `PocketChatActivity.kt` (View-based + Compose theme), `ChatAdapter.kt` (RecyclerView)
- **ViewModel/Controller**: `PersistentChatController.kt` — управляет состоянием чата, историей, сессиями
- **Backend**: `ChatBackend` (interface) → `OnnxChatBackend`, `GemmaLiteRtBackend`, `QwenLiteRtBackend`
- **Модели**: `ChatTurn` (сообщения), `BackendResponse` (ответ бэкенда)
- **Inference**: ONNX Runtime (Qwen2.5/3) и LiteRT (Gemma 4, Qwen3)

### Ключевое ограничение
Локальные модели 0.6B–1.5B плохо генерируют структурированный JSON для tool calling. Нужно:
- Использовать **Qwen 3** (лучше следует инструкциям) с жёстким prompt-инжинирингом
- Или добавить **пост-обработку парсинга** «намерений» из текста
- Или использовать **Rule-based Agent** (упрощённая схема — см. раздел 5)

---

## 2. Архитектура Agent Mode

### 2.1. Новые сущности (пакет `tool/`)

```
tool/
  ├── Tool.kt                    // interface Tool { name, description, execute(params) }
  ├── ToolRegistry.kt            // список доступных инструментов
  ├── WebSearchTool.kt           // HTTP-поиск (DuckDuckGo / Bing scrape)
  ├── FileSystemTool.kt          // чтение/запись/поиск файлов и каталогов
  └── ToolCallParser.kt          // парсит JSON/текст → ToolCall

agent/
  ├── AgentModeController.kt     // orchestrator: LLM → parse → execute → LLM
  ├── ToolCall.kt                // data class(name, arguments)
  └── ToolResult.kt              // data class(output, error)

ui/
  └── ToolCallIndicator.kt       // UI: спиннер «Выполняю поиск...»
```

### 2.2. Поток данных в Agent Mode

```
Пользователь → Prompt
       ↓
[AgentModeController] добавляет system prompt с описанием инструментов
       ↓
LLM генерирует текст (streaming)
       ↓
[ToolCallParser] мониторит поток на паттерн:
   - <tool_call>{"name":"web_search",...}</tool_call>
   - или markdown ```json {...}```
       ↓
Если найден tool_call:
   1. Остановить generation (или дождаться конца)
   2. Показать индикатор в UI
   3. Выполнить Tool.execute() в IO-потоке
   4. Сформировать новый prompt: «Результат: ... Продолжи ответ»
   5. Повторный вызов LLM (1-3 итерации максимум)
       ↓
Финальный ответ пользователю
```

---

## 3. Пошаговая реализация

### Шаг 1. Разрешения и зависимости

#### `app/build.gradle.kts` — добавить зависимости:
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")          // HTTP для web search
implementation("org.json:json:20231013")                      // JSON parsing
implementation("androidx.documentfile:documentfile:1.0.1")    // Scoped Storage
```

#### `AndroidManifest.xml` — добавить разрешения:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
    android:maxSdkVersion="32" />
```

> **Важно**: приложение позиционируется как offline. Agent Mode с web search требует интернет **только для поиска** — чат остаётся локальным. Это нужно явно указать в UI (иконка 🌐 когда агент лезет в сеть).

---

### Шаг 2. Интерфейс инструментов (`tool/Tool.kt`)

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, String> // paramName → type/description

    suspend fun execute(arguments: Map<String, String>): ToolResult
}

data class ToolResult(
    val output: String,
    val isError: Boolean = false
)
```

---

### Шаг 3. Реализация инструментов

#### 3.1. `WebSearchTool.kt` — поиск без API-ключа

Использовать **DuckDuckGo HTML** или **Bing scrape** через OkHttp:

```kotlin
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Search the internet for current information. " +
        "Parameter: query (string) — the search query."

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["query"] ?: return ToolResult("No query provided", true)
        return withContext(Dispatchers.IO) {
            try {
                val results = searchDuckDuckGo(query) // парсинг HTML
                ToolResult(results.take(3).joinToString("\n"))
            } catch (e: Exception) {
                ToolResult("Search error: ${e.message}", true)
            }
        }
    }
}
```

#### 3.2. `FileSystemTool.kt` — работа с файлами и каталогами

```kotlin
class FileSystemTool(private val context: Context) : Tool {
    override val name = "file_system"
    override val description = "Read, write, list, and search files and directories. " +
        "Parameters: action (read|write|list|search), path (string), content (string, optional), pattern (string, optional)."

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val action = arguments["action"]
        val path = arguments["path"]
        return when (action) {
            "read" -> readFile(path)
            "write" -> writeFile(path, arguments["content"])
            "list" -> listDirectory(path)
            "search" -> searchFiles(path, arguments["pattern"])
            else -> ToolResult("Unknown action: $action", true)
        }
    }
}
```

**Scoped Storage для Android 10+**:
- Для чтения: `Intent.ACTION_OPEN_DOCUMENT` (SAF) — пользователь выбирает файл
- Для записи: `Intent.ACTION_CREATE_DOCUMENT`
- Для поиска: `MediaStore` API или `DocumentsContract`

---

### Шаг 4. Tool Call Parser (`ToolCallParser.kt`)

Для локальных моделей используем **текстовый парсинг** вместо нативного function calling (модели слишком малы для стабильного JSON):

```kotlin
object ToolCallParser {
    private val TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""", 
        RegexOption.DOT_MATCHES_ALL
    )

    fun extractToolCalls(text: String): List<ToolCall> {
        return TOOL_CALL_REGEX.findAll(text).map { match ->
            val json = JSONObject(match.groupValues[1])
            ToolCall(
                name = json.getString("name"),
                arguments = json.getJSONObject("arguments").toMap()
            )
        }.toList()
    }

    fun stripToolCalls(text: String): String {
        return TOOL_CALL_REGEX.replace(text, "").trim()
    }
}
```

**System Prompt для активации tool calling** (добавлять в `PersistentChatController`):
```
You are a helpful assistant with access to tools. 
When you need to use a tool, output exactly:
<tool_call>{"name":"tool_name","arguments":{"key":"value"}}</tool_call>

Available tools:
- web_search: query (string)
- file_system: action (read|write|list|search), path (string), content (string, optional)
```

---

### Шаг 5. Agent Orchestrator (`AgentModeController.kt`)

```kotlin
class AgentModeController(
    private val backend: ChatBackend,
    private val toolRegistry: ToolRegistry,
    private val scope: CoroutineScope
) {
    private val maxIterations = 3

    suspend fun runAgentTurn(
        history: List<ChatTurn>,
        userPrompt: String,
        onPartial: (BackendResponse) -> Unit,
        onToolCall: (ToolCall) -> Unit
    ): BackendResponse {
        var currentHistory = history + ChatTurn(role = USER, text = userPrompt)
        var finalResponse: BackendResponse? = null

        repeat(maxIterations) {
            // 1. Генерация LLM
            val response = backend.streamReply(
                history = currentHistory,
                thinkingEnabled = false,
                modelInstruction = AGENT_SYSTEM_PROMPT,
                onPartial = onPartial
            )

            // 2. Проверка на tool calls
            val toolCalls = ToolCallParser.extractToolCalls(response.text)
            if (toolCalls.isEmpty()) {
                finalResponse = response
                return@runAgentTurn response
            }

            // 3. Выполнение инструментов
            val toolResults = toolCalls.map { call ->
                onToolCall(call)
                val tool = toolRegistry.get(call.name)
                tool.execute(call.arguments)
            }

            // 4. Добавляем результаты в историю
            val toolResultText = toolResults.joinToString("\n") { it.output }
            currentHistory = currentHistory + 
                ChatTurn(role = ASSISTANT, text = response.text) +
                ChatTurn(
                    role = USER,
                    text = "[Tool results]\n$toolResultText\n[Continue your response]"
                )
        }

        return finalResponse ?: BackendResponse("Agent iteration limit reached")
    }
}
```

---

### Шаг 6. Интеграция в `PersistentChatController.kt`

1. **Добавить флаг** `isAgentModeEnabled: Boolean` в `ChatUiState` и `AppSettingsStore`
2. **В `sendPrompt()`** добавить ветвление:
   ```kotlin
   if (isAgentModeEnabled) {
       agentController.runAgentTurn(...)
   } else {
       backend.streamReply(...) // обычный режим
   }
   ```
3. **Добавить `ToolRegistry`** в `init`:
   ```kotlin
   val toolRegistry = ToolRegistry().apply {
       register(WebSearchTool())
       register(FileSystemTool(appContext))
   }
   ```

---

### Шаг 7. UI-изменения

#### 7.1. Переключатель режима агента
В тулбар или в меню настроек добавить:
- Toggle «🤖 Agent Mode»
- При включении: показывать чипы доступных инструментов под полем ввода

#### 7.2. Индикатор выполнения инструмента
В `ChatAdapter.kt` добавить ViewHolder для статуса:
- «🔍 Searching web...»
- «📁 Reading file...»
- Прогресс-бар

#### 7.3. Результаты поиска
- Показывать сворачиваемый блок «Web search results» над ответом ассистента
- Для файлов — кнопку «Open file»

---

### Шаг 8. Хранение и настройки

**`AppSettingsStore.kt`** — добавить поля:
```kotlin
data class AppSettings(
    // ... existing fields ...
    val agentModeEnabled: Boolean = false,
    val agentMaxIterations: Int = 3,
    val webSearchEnabled: Boolean = true,
    val fileSystemEnabled: Boolean = true
)
```

---

### Шаг 9. Сборка APK

#### 9.1. Обновление версии
В `build.gradle.kts`:
```kotlin
versionCode = 15
versionName = "1.6.0-agent"
```

#### 9.2. Build → Generate Signed APK / App Bundle
```bash
# Или через Gradle CLI
./gradlew :app:assembleRelease
```

#### 9.3. ProGuard (если включаем minification)
Добавить правила:
```proguard
-keep class okhttp3.** { *; }
-keep class org.json.** { *; }
```

#### 9.4. Размещение в репозитории
Структура релиза:
```
releases/
  v1.6.0-agent/
    pocket_llm_v1.6.0-agent.apk
    agent-mode-docs.md
```

---

## 4. Технические риски и решения

| Риск | Решение |
|------|---------|
| **Модель 0.6B не генерирует валидный JSON** | Использовать regex-парсинг + retry с упрощённым prompt |
| **Web search ломает offline-концепцию** | Добавить явный toggle + иконка сети. Поиск только по запросу |
| **Scoped Storage ограничивает доступ к файлам** | Использовать SAF (Intent.ACTION_OPEN_DOCUMENT) — пользователь явно выбирает файлы |
| **Зацикливание agent** (бесконечные tool calls) | Hard limit 3 итерации + таймаут 30 сек |
| **Производительность** | Tool execution в `Dispatchers.IO`, не блокировать UI-поток |

---

## 5. Альтернативная упрощённая схема (Rule-based Agent)

Если Qwen 0.6B / Gemma 2B не справляются с JSON tool calling:

**Решение**: **Rule-based Agent** без LLM-оркестрации
1. Пользователь пишет: «Найди в интернете погоду в Москве»
2. **Intent Classifier** (простой regex / keyword) определяет намерение
3. Выполняется `WebSearchTool` напрямую
4. Результат подаётся в LLM как контекст: «Вот что я нашёл: ... Сформулируй ответ»

Это надёжнее для мобильных локальных моделей.

---

## 6. Итоговый чек-лист

- [ ] Добавить зависимости OkHttp, DocumentFile
- [ ] Добавить разрешения INTERNET, READ_EXTERNAL_STORAGE
- [ ] Создать пакеты `tool/` и `agent/`
- [ ] Реализовать `Tool`, `ToolRegistry`, `WebSearchTool`, `FileSystemTool`
- [ ] Реализовать `ToolCallParser` с regex-парсингом
- [ ] Реализовать `AgentModeController` с лимитом 3 итераций
- [ ] Интегрировать Agent Mode в `PersistentChatController`
- [ ] Добавить toggle Agent Mode в UI
- [ ] Добавить индикаторы выполнения инструментов
- [ ] Обновить `AppSettings` для хранения настроек агента
- [ ] Обновить versionCode / versionName
- [ ] Собрать release APK (`./gradlew :app:assembleRelease`)
- [ ] Выложить APK в releases/ репозитория

---

*Составлено: 2026-06-28*
