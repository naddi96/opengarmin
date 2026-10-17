package dev.opengarmin.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

object PulseSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val mdPlus = 10.dp
    val lg = 12.dp
    val lgPlus = 14.dp
    val xl = 16.dp
    val xlPlus = 18.dp
    val xxl = 20.dp
    val screenTop = 28.dp
    val tabBarHeight = 84.dp
    val touchTarget = 44.dp
}
