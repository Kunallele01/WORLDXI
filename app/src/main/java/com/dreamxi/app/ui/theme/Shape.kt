package com.dreamxi.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Dream XI shape system (§10.1): deliberately rounded but not pill-shaped
 * everywhere — a premium-sports-card feel rather than a soft generic-app feel.
 * Used for buttons/chips (extraSmall/small), standard cards (medium/large),
 * and hero surfaces like the club-season reveal or results scoreboard
 * (extraLarge).
 */
val DreamXiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
