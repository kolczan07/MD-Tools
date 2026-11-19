package pl.mdinstal.narzedzia

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Animowany ekran startowy: litery nazwy wjeżdżają po kolei, potem pojawia się podpis. */
@Composable
fun BootLogo(background: Color, word: String = "MD-Tools", subtitle: String = "PANEL TECHNICZNY", size: TextUnit = 40.sp) {
    Column(
        Modifier.fillMaxSize().background(background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row {
            word.forEachIndexed { i, ch ->
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(100L + i * 90L)
                    a.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
                }
                Text(
                    ch.toString(),
                    color = Color.White,
                    fontSize = size,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.graphicsLayer {
                        alpha = a.value
                        translationY = (1f - a.value) * 24.dp.toPx()
                    }
                )
            }
        }
        val sub = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(950)
            sub.animateTo(1f, tween(500))
        }
        Text(
            subtitle,
            color = Color(0xFF94A3B8),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp).graphicsLayer {
                alpha = sub.value
                translationY = (1f - sub.value) * 8.dp.toPx()
            }
        )
    }
}
