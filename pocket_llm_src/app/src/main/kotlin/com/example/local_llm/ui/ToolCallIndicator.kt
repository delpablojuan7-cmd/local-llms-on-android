package com.example.local_llm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI компонент для отображения статуса выполнения инструмента
 * Показывает спиннер и название текущего выполняемого инструмента
 */
@Composable
fun ToolCallIndicator(
    toolName: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = toolName != null,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                .padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.let {
                        it.then(Modifier.requiredWidth(20.dp))
                    },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = getToolDisplayName(toolName.orEmpty()),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun getToolDisplayName(toolName: String): String {
    return when (toolName) {
        "web_search" -> "🔍 Searching web..."
        "file_system" -> "📁 Reading file system..."
        else -> "⚙️ Executing tool: $toolName"
    }
}
