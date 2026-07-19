package com.example.intentcam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    current: LlmConfig,
    debugEnabled: Boolean,
    onToggleDebug: (Boolean) -> Unit,
    onSave: (LlmConfig) -> Unit,
    onClose: () -> Unit,
) {
    // Use rememberSaveable so the user's in-progress edits survive
    // process death (e.g. OS kills the app while the user is in
    // Settings to copy a token from their password manager).
    // Previous `remember` lost everything on Activity recreation;
    // combined with the manifest's `configChanges` not covering
    // `uiMode` / `density` / system-initiated restart, this was a
    // real data-loss path.  No `key` argument because the explicit
    // `MutableState` overload requires a stateSaver when one is
    // provided; the `rememberSaveable { mutableStateOf(...) }` form
    // (no key) infers the right saver automatically and still saves
    // across process death because the wrapping MutableState IS the
    // SavedStateRegistry entry.
    var baseUrl by rememberSaveable { mutableStateOf(current.baseUrl) }
    // Token field is intentionally left blank — we never display the active
    // token on screen so it can't be shoulder-surfed.  Leaving it blank
    // keeps whatever is currently active in the runtime config; only an
    // explicit non-empty edit changes it.
    var token by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(current.model) }

    // 2026-07-19 rework (user requests):
    //  - 保存/恢复默认 buttons removed: leaving the page (back arrow
    //    or system back) saves.
    //  - ...but ONLY when the user actually touched the LLM fields.
    //    SharedPreferences starts EMPTY of any config keys, so
    //    SettingsStore.load() falls back to the live baked default
    //    (BuildConfig.DEFAULT_AUTH_TOKEN — rotates with new APKs).
    //    Persisting on a no-op visit would freeze today's baked token
    //    into prefs and shadow every future baked rotation.  Once the
    //    user edits any LLM field they own the whole config (blank
    //    token field resolves to the currently-active token at that
    //    point), per the user's rule: 不修改就一直用缺省配置，修改过
    //    一次就自己维护 token。
    fun persistAndClose() {
        val dirty = baseUrl != current.baseUrl ||
            model != current.model ||
            token.isNotBlank()
        if (dirty) {
            val effectiveToken = if (token.isBlank()) current.authToken else token
            onSave(
                LlmConfig(
                    baseUrl = baseUrl,
                    authToken = effectiveToken,
                    model = model,
                )
            )
        } else {
            onClose()
        }
    }
    BackHandler { persistAndClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { persistAndClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                "配置 Anthropic 兼容接口（默认 MiniMax / MiniMax-M3）。",
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

            // Debug-log toggle (moved here from the camera top bar,
            // 2026-07-19; default OFF).  Independent of the LLM-config
            // dirty-tracking above — flips persist immediately.
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("调试日志") },
                supportingContent = {
                    Text(
                        "在相机预览上显示识别过程日志（默认关闭）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = debugEnabled,
                        onCheckedChange = onToggleDebug,
                    )
                },
            )

            // About / copyright (2026-07-19, user request).
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Text(
                "关于",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "开发者：HUANGTAO",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "© 2026 HUANGTAO. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
