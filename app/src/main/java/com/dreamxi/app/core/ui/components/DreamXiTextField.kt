package com.dreamxi.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.dreamxi.app.core.ui.passwordStrengthColour
import com.dreamxi.app.core.ui.passwordStrengthLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import com.dreamxi.app.ui.theme.SurfaceBase
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
    /**
     * 0 to 1, or null for a field where strength means nothing.
     *
     * Only set where a password is being CHOSEN. On a sign-in field it would
     * be noise at best and a hint about somebody's existing password at worst.
     */
    strength: Float? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val masked = isPassword && !revealed
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()

    // A FIELD SHOULD ANSWER WHEN IT IS TOUCHED. These were three anonymous grey
    // boxes: same fill, same border, same label, whichever one the cursor was
    // in. The one being typed into now takes the accent — gold label, gold
    // edge, a gold bar down its leading side — so the form has a live element
    // in it instead of being a static diagram of a form.
    // Strength, where it applies, takes the field's colour over from the plain
    // focus accent — red through gold to green as it is typed. Animated, and
    // slowly enough not to strobe: the colour crosses the whole range within a
    // few keystrokes, and a hard cut on every letter is exactly the disturbing
    // thing a live meter must not be.
    val live = strength?.takeIf { value.isNotEmpty() }
    val strengthColour by animateColorAsState(
        targetValue = live?.let { passwordStrengthColour(it) } ?: AccentGold,
        animationSpec = tween(durationMillis = 260),
        label = "passwordStrength",
    )

    val edge = when {
        error != null -> ResultLoss
        live != null -> strengthColour
        focused -> AccentGold
        else -> OutlineSubtle
    }
    val labelColour = when {
        error != null -> ResultLoss
        live != null -> strengthColour
        focused -> AccentGold
        else -> OnSurfaceFaint
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = labelColour,
                modifier = Modifier.weight(1f),
            )
            // A word as well as a colour. Colour alone is invisible to a good
            // share of people and ambiguous to everyone else.
            if (live != null) {
                Text(
                    text = passwordStrengthLabel(live),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = strengthColour,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                // Darker than the card it sits on rather than lighter: a well
                // to type into, not another raised panel competing with it.
                .background(SurfaceBase)
                .border(
                    width = if (focused || error != null || live != null) 2.dp else 1.dp,
                    color = if (focused || error != null || live != null) edge else edge.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp),
                )
                .height(IntrinsicSize.Min),
        ) {
            // The leading bar: the clearest possible mark of which field is
            // live, readable before any of the text is.
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        when {
                            live != null -> strengthColour
                            focused -> AccentGold
                            else -> Color.Transparent
                        },
                    ),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 15.dp),
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
                    interactionSource = interactions,
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
