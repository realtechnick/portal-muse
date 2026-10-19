package dev.wobsoriano.museportal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
  val Ground = Color(0xFF0B0F14)
  val Card = Color(0xFF141A22)
  val Raised = Color(0xFF1D2530)
  val Line = Color(0xFF2C3642)
  val Text = Color(0xFFF2F5F8)
  val Dim = Color(0xFFA3AEBB)
  val Faint = Color(0xFF6D7987)
  val Accent = Color(0xFF7FA8FF)
  val Good = Color(0xFF5FD68E)
  val Warn = Color(0xFFF2C46B)
  val Bad = Color(0xFFFF7B72)
}

private val type =
    Typography(
        displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 31.sp),
        bodyMedium = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
        labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    )

@Composable
fun MuseTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          darkColorScheme(
              primary = Palette.Accent,
              onPrimary = Color(0xFF0B1630),
              background = Palette.Ground,
              surface = Palette.Card,
              surfaceVariant = Palette.Raised,
              onSurface = Palette.Text,
              onSurfaceVariant = Palette.Dim,
              outline = Palette.Line,
              error = Palette.Bad),
      typography = type,
      content = content)
}

/** The one button shape: a tall pill, easy to hit. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, danger: Boolean = false) {
  val padding = PaddingValues(horizontal = 36.dp, vertical = 16.dp)
  val min = modifier.heightIn(min = 64.dp)
  if (primary) {
    Button(onClick, min, enabled = enabled, shape = CircleShape, contentPadding = padding) { Text(text, style = MaterialTheme.typography.labelLarge) }
  } else {
    val color = if (danger) Palette.Bad else Palette.Text
    OutlinedButton(
        onClick,
        min,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = padding,
        border = BorderStroke(1.5.dp, if (danger) Palette.Bad.copy(alpha = 0.7f) else Palette.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
          Text(text, style = MaterialTheme.typography.labelLarge)
        }
  }
}

@Composable
fun Link(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  TextButton(onClick, modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
  }
}

/** A labelled switch the whole row of which is the target. */
@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
  Row(
      Modifier.fillMaxWidth().heightIn(min = 72.dp),
      verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(title, style = MaterialTheme.typography.titleLarge)
          if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.padding(end = 12.dp)) {
          Switch(
              checked = checked,
              onCheckedChange = onChange,
              enabled = enabled,
              modifier = Modifier.scale(1.4f),
              colors =
                  SwitchDefaults.colors(
                      checkedTrackColor = Palette.Accent,
                      checkedThumbColor = Color.White,
                      uncheckedThumbColor = Palette.Dim,
                      uncheckedTrackColor = Palette.Raised,
                      uncheckedBorderColor = Palette.Faint))
        }
      }
}
