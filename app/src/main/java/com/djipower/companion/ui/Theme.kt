package com.djipower.companion.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val PowerGreen = Color(0xFF2E7D32)
private val PowerGreenLight = Color(0xFF5DD57B)
private val Amber = Color(0xFFF2A600)
private val Teal = Color(0xFF00897B)
private val TealLight = Color(0xFF4DB6AC)

val DjiLightScheme = lightColorScheme(
    primary = PowerGreen, secondary = Amber, tertiary = Teal,
)
val DjiDarkScheme = darkColorScheme(
    primary = PowerGreenLight, secondary = Amber, tertiary = TealLight,
)
