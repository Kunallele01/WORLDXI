package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * A text field in the app's own language.
 *
 * Material's OutlinedTextField is not used anywhere in this app, and should not
 * start here: it brings its own palette, its own floating label animation and
 * its own idea of corner radius, none of which match the flat surfaces
 * everything else is built from. This is the same construction the club search
 * box already uses — a raised rounded box, a plain field inside it, a gold
 * cursor — with a label, an optional password mask and an error line.
 */
@Composable
fun DreamXiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    error: String? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val masked = isPassword && !revealed

    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = if (error != null) ResultLoss else OnSurfaceFaint,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceRaised2)
                .border(
                    width = 1.dp,
                    color = if (error != null) ResultLoss.copy(alpha = 0.6f) else OutlineSubtle,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceFaint,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = if (enabled) OnSurfacePrimary else OnSurfaceMuted,
                    ),
                    cursorBrush = SolidColor(AccentGold),
                    visualTransformation =
                        if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isPassword) {
                // Typed once, unverifiable, and the commonest reason a sign-in
                // fails twice in a row. Worth a way to look at it.
                Text(
                    text = if (revealed) "HIDE" else "SHOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGold,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable(enabled = enabled) { revealed = !revealed },
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = ResultLoss)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun DreamXiTextFieldPreview() = DreamXITheme {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.background(SurfaceRaised1).padding(20.dp),
    ) {
        DreamXiTextField(
            value = "kunal@example.com",
            onValueChange = {},
            label = "Email",
            keyboardType = KeyboardType.Email,
        )
        DreamXiTextField(value = "hunter2hunter2", onValueChange = {}, label = "Password", isPassword = true)
        DreamXiTextField(
            value = "nope",
            onValueChange = {},
            label = "Email",
            error = "That does not look like an email address.",
        )
    }
}
