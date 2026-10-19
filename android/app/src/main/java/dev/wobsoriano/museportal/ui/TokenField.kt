package dev.wobsoriano.museportal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.setup.SdkToken
import dev.wobsoriano.museportal.setup.TokenCheck
import dev.wobsoriano.museportal.setup.savedTokenCheck

/**
 * The token entry, in setup and in Settings: a masked field with Show, and "Save & verify". The
 * typed token lives only in this field until it is saved; it is never shown back once saved.
 */
@Composable
fun TokenField(state: MuseState, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
  var text by remember { mutableStateOf("") }
  var show by remember { mutableStateOf(false) }
  var check by remember { mutableStateOf<TokenCheck?>(null) }
  var saved by remember { mutableStateOf(false) }
  val shown = if (saved) savedTokenCheck(state.paired, state.connection) else check
  fun submit() {
    val token = SdkToken.parse(text)
    if (token == null) {
      check = TokenCheck.Malformed
      saved = false
      return
    }
    onSave(token)
    text = ""
    show = false
    saved = true
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
    OutlinedTextField(
        value = text,
        onValueChange = {
          text = it
          check = null
          saved = false
        },
        label = { Text("Enter your token", fontSize = 18.sp) },
        placeholder = { Text("mgst_…", fontFamily = FontFamily.Monospace, color = Palette.Faint) },
        singleLine = true,
        isError = shown == TokenCheck.Malformed,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
          TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", fontSize = 18.sp, color = Palette.Accent) }
        },
        shape = MaterialTheme.shapes.large,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line, focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card),
        modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Pill("Save & verify", ::submit, enabled = text.isNotBlank())
      shown?.let { CheckLine(it) }
    }
  }
}

@Composable
private fun CheckLine(check: TokenCheck) {
  val (text, color) =
      when (check) {
        TokenCheck.Malformed -> "That isn't a Muse token. It starts with mgst_." to Palette.Bad
        TokenCheck.Checking -> "Saved. Reconnecting to Muse…" to Palette.Dim
        TokenCheck.Accepted -> "✓ Saved. Muse is online with it." to Palette.Good
        TokenCheck.UntilPairing -> "✓ Saved. Muse checks it when you pair." to Palette.Good
        is TokenCheck.Offline -> "Saved, but Muse is offline: ${check.reason}" to Palette.Warn
      }
  Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

/** The saved token as it may be shown. */
@Composable
fun MaskedToken(hint: String) {
  Text(hint.ifEmpty { "No token yet" }, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, color = if (hint.isEmpty()) Palette.Warn else Color.Unspecified)
}
