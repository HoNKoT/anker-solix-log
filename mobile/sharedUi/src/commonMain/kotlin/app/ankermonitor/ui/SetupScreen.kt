package app.ankermonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.normalizeServerUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    initial: Connection?,
    onSave: suspend (localUrl: String, globalUrl: String, token: String) -> SaveResult,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    var local by rememberSaveable { mutableStateOf(initial?.localUrl.orEmpty()) }
    var global by rememberSaveable { mutableStateOf(initial?.globalUrl.orEmpty()) }
    var token by rememberSaveable { mutableStateOf(initial?.token.orEmpty()) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接続先の設定") },
                actions = { if (onCancel != null) TextButton(onClick = onCancel) { Text("閉じる") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "記録している Mac (amon lan on 済み) のアドレスを入れてください。どちらか片方だけでもかまいません。" +
                    "ローカルに届くときはローカル、届かないときはグローバルで読みます (グローバルの間も 30 秒おきにローカルを試します)。" +
                    "http:// と :19877 は省略できます。",
                style = MaterialTheme.typography.bodyMedium,
            )
            AddressField(
                value = local,
                onValueChange = { local = it; error = null },
                label = "ローカル (例: my-mac.local)",
                hint = "同じ Wi-Fi のとき",
            )
            AddressField(
                value = global,
                onValueChange = { global = it; error = null },
                label = "グローバル (例: my-mac.tailnet.ts.net)",
                hint = "外出先 (Tailscale の IP か MagicDNS 名)",
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text("トークン (amon token の値)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                supportingText = { Text("Mac 側でトークンを設定していなければ空欄") },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && (local.isNotBlank() || global.isNotBlank()),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val r = onSave(local, global, token)) {
                                SaveResult.Saved -> onDone()
                                // 保存はできているので閉じる。読めない理由はメイン画面が 3 回失敗した時点で出す
                                is SaveResult.SavedButUnreachable -> onDone()
                                is SaveResult.Rejected -> error = r.message
                            }
                            busy = false
                        }
                    },
                ) { Text("接続を確認して保存") }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(24.dp))
            }
        }
    }
}

@Composable
private fun AddressField(value: String, onValueChange: (String) -> Unit, label: String, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        supportingText = {
            val normalized = normalizeServerUrl(value)
            Text(if (normalized.isNotEmpty()) "→ $normalized/status" else hint)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
