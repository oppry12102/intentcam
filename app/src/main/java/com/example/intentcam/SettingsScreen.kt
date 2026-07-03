package com.example.intentcam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    current: LlmConfig,
    onSave: (LlmConfig) -> Unit,
    onResetDefault: () -> Unit,
    onClose: () -> Unit
) {
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    // Token field is intentionally left blank — we never display the active
    // token on screen so it can't be shoulder-surfed.  Leaving it blank (or
    // hitting "恢复默认") keeps whatever is currently active in the runtime
    // config; only an explicit non-empty edit changes it.
    var token by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(current.model) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "配置 Anthropic 兼容接口（默认 MiniMax / MiniMax-M3）。留空则使用内置默认值。",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("ANTHROPIC_BASE_URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("ANTHROPIC_AUTH_TOKEN") },
                placeholder = { Text("（留空使用内置默认）") },
                supportingText = { Text("清空/留空 → 使用当前激活的 token；输入新值将覆盖") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("ANTHROPIC_MODEL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    // Save only overwrites the token if the user actually
                    // typed something; an empty field leaves the runtime token
                    // untouched.  An empty string is still acceptable to the
                    // [AppViewModel] (which falls back to the default).
                    val effectiveToken = if (token.isBlank()) current.authToken else token
                    onSave(LlmConfig(baseUrl, effectiveToken, model))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }

            OutlinedButton(
                onClick = {
                    // Don't leak the default token either.  onResetDefault()
                    // reverts the runtime config to defaults; the field is
                    // visually cleared so the token is never on screen.
                    onResetDefault()
                    baseUrl = LlmConfig.DEFAULT_BASE_URL
                    token = ""
                    model = LlmConfig.DEFAULT_MODEL
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复默认") }

            Spacer(Modifier.height(4.dp))
            Text(
                "提示：接口需支持图片输入（vision）。请求路径为 <BASE_URL>/v1/messages。",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
