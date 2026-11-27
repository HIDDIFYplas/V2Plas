// DEST: ui/main/FlagIcon.kt
package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Circular country flags backed by local drawable resources. No runtime network
 * The modifier.clip(CircleShape) clipping contract remains in place for the artwork.
 * access is used. Every flag gets the same lightweight, one-shot entrance effect.
 */
@Composable
fun V2PlasFlag(
    countryCode: String?,
    modifier: Modifier = Modifier.size(42.dp),
) {
    val code = countryCode?.trim()?.uppercase(Locale.ROOT)
    val context = LocalContext.current
    val flagResource = remember(code, context.packageName) {
        if (code != null && code.matches(Regex("^[A-Z]{2}$")) && code in ISO_COUNTRY_CODES) {
            context.resources.getIdentifier(
                "flag_${code.lowercase(Locale.ROOT)}",
                "drawable",
                context.packageName,
            )
        } else 0
    }

    // Animatable guarantees a real 0 -> 1 transition even on the first frame.
    // It runs once per country change and then remains completely idle.
    val progress = remember(code) { Animatable(0f) }
    LaunchedEffect(code) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        )
    }
    val reveal = progress.value.coerceIn(0f, 1f)

    Box(
        modifier = modifier.graphicsLayer {
            alpha = reveal
            val settle = 1f - reveal
            scaleX = 0.62f + 0.38f * reveal
            scaleY = 0.62f + 0.38f * reveal
            rotationY = 105f * settle
            rotationZ = -12f * settle
            translationY = 7.dp.toPx() * settle
            cameraDistance = 18f * density
        },
    ) {
        if (flagResource != 0) {
            Image(
                painter = painterResource(id = flagResource),
                contentDescription = "پرچم ${code ?: "ناشناخته"}",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
                val w = size.width
                val h = size.height
                val blue = Color(0xFF087CB8)
                drawRect(Color(0xFF08234D), Offset.Zero, Size(w, h))
                drawCircle(blue, radius = w * 0.43f, center = center)
                drawCircle(Color(0xFF8FE6F3), radius = w * 0.31f, center = center,
                    style = Stroke(w * 0.025f))
                drawOval(Color(0xFF8FE6F3), topLeft = Offset(w * 0.34f, h * 0.08f),
                    size = Size(w * 0.32f, h * 0.84f), style = Stroke(w * 0.025f))
                drawLine(Color(0xFF8FE6F3), Offset(w * 0.12f, h * 0.5f),
                    Offset(w * 0.88f, h * 0.5f), w * 0.025f)
                drawLine(Color(0xFF8FE6F3), Offset(w * 0.20f, h * 0.32f),
                    Offset(w * 0.80f, h * 0.32f), w * 0.018f)
                drawLine(Color(0xFF8FE6F3), Offset(w * 0.20f, h * 0.68f),
                    Offset(w * 0.80f, h * 0.68f), w * 0.018f)
            }
        }

        // Expanding ring and a moving light sweep, drawn only during entrance.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = reveal
            val side = size.minDimension
            val ringProgress = (p / 0.78f).coerceIn(0f, 1f)
            if (p < 0.98f) {
                drawCircle(
                    color = Color(0xFF9BE7FF).copy(alpha = 0.58f * (1f - ringProgress)),
                    radius = side * (0.34f + 0.20f * ringProgress),
                    style = Stroke(width = (side * 0.055f).coerceAtLeast(1.dp.toPx())),
                )
            }
            if (p > 0.02f && p < 1f) {
                val sweep = ((p - 0.02f) / 0.98f).coerceIn(0f, 1f)
                drawArc(
                    color = Color.White.copy(alpha = 0.62f * (1f - sweep * 0.45f)),
                    startAngle = -150f + 300f * sweep,
                    sweepAngle = 62f,
                    useCenter = false,
                    style = Stroke(width = (side * 0.065f).coerceAtLeast(1.dp.toPx())),
                    topLeft = Offset(side * 0.055f, side * 0.055f),
                    size = Size(side * 0.89f, side * 0.89f),
                )
            }
        }
    }
}

/** Resolves country names, ISO alpha-2 codes and conservative city aliases. */
fun countryCodeFromText(value: String?): String? {
    val raw = value?.trim()?.replace('_', ' ') ?: return null
    if (raw.isBlank()) return null
    val normalized = raw.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[\\p{Punct}\\s]+"), " ")
        .trim()

    // An explicit two-letter token is accepted only if it is a real ISO country code.
    if (raw.length == 2 && raw.all { it in 'a'..'z' || it in 'A'..'Z' }) {
        val code = raw.uppercase(Locale.ROOT).let { if (it == "UK") "GB" else it }
        if (code in ISO_COUNTRY_CODES) return code
    }
    val tokenPattern = Regex("(?:^|[^A-Za-z])([A-Z]{2})(?:$|[^A-Za-z])")
    tokenPattern.findAll(raw).forEach { match ->
        val code = match.groupValues[1].let { if (it == "UK") "GB" else it }
        if (code in ISO_COUNTRY_CODES) return code
    }

    val aliases = listOf(
        listOf("united states", "usa", "america", "new york", "los angeles", "آمریکا", "امریکا") to "US",
        listOf("united kingdom", "great britain", "britain", "england", "london", "uk", "انگلیس", "بریتانیا", "لندن") to "GB",
        listOf("netherlands", "holland", "amsterdam", "هلند", "آمستردام") to "NL",
        listOf("germany", "deutschland", "frankfurt", "berlin", "آلمان", "فرانکفورت") to "DE",
        listOf("sweden", "stockholm", "sverige", "سوئد") to "SE",
        listOf("turkey", "türkiye", "istanbul", "ترکیه", "استانبول") to "TR",
        listOf("iran", "ایران") to "IR",
        listOf("france", "paris", "فرانسه", "پاریس") to "FR",
        listOf("canada", "toronto", "montreal", "کانادا") to "CA",
        listOf("singapore", "سنگاپور") to "SG",
        listOf("japan", "tokyo", "ژاپن") to "JP",
        listOf("russia", "moscow", "روسیه") to "RU",
        listOf("finland", "helsinki", "فنلاند") to "FI",
        listOf("switzerland", "zurich", "geneva", "سوئیس") to "CH",
        listOf("austria", "vienna", "اتریش", "وین") to "AT",
        listOf("poland", "warsaw", "لهستان", "ورشو") to "PL",
        listOf("italy", "rome", "milan", "ایتالیا", "رم") to "IT",
        listOf("spain", "madrid", "اسپانیا", "مادرید") to "ES",
        listOf("ukraine", "kyiv", "اوکراین") to "UA",
        listOf("india", "mumbai", "delhi", "هند") to "IN",
        listOf("china", "beijing", "چین") to "CN",
        listOf("hong kong", "هنگ kong", "هنگ کنگ") to "HK",
        listOf("brazil", "sao paulo", "برزیل") to "BR",
        listOf("australia", "sydney", "استرالیا") to "AU",
        listOf("south korea", "korea", "seoul", "کره جنوبی") to "KR",
        listOf("denmark", "copenhagen", "دانمارک") to "DK",
        listOf("norway", "oslo", "نروژ") to "NO",
        listOf("iceland", "reykjavik", "ایسلند") to "IS",
        listOf("belgium", "brussels", "بلژیک") to "BE",
        listOf("ireland", "dublin", "ایرلند") to "IE",
        listOf("romania", "bucharest", "رومانی") to "RO",
        listOf("bulgaria", "sofia", "بلغارستان") to "BG",
        listOf("hungary", "budapest", "مجارستان") to "HU",
        listOf("indonesia", "jakarta", "اندونزی") to "ID",
        listOf("uae", "united arab emirates", "dubai", "emirates", "امارات", "دبی") to "AE",
        listOf("portugal", "lisbon", "پرتغال") to "PT",
        listOf("greece", "athens", "یونان") to "GR",
        listOf("czechia", "czech republic", "prague", "چک") to "CZ",
        listOf("new zealand", "auckland", "نیوزیلند") to "NZ",
        listOf("taiwan", "taipei", "تایوان") to "TW",
        listOf("thailand", "bangkok", "تایلند") to "TH",
        listOf("vietnam", "hanoi", "ویتنام") to "VN",
        listOf("malaysia", "kuala lumpur", "مالزی") to "MY",
        listOf("mexico", "مکزیک") to "MX",
        listOf("saudi arabia", "riyadh", "عربستان") to "SA",
        listOf("south africa", "cape town", "آفریقای جنوبی") to "ZA",
        listOf("argentina", "buenos aires", "آرژانتین") to "AR",
        listOf("serbia", "belgrade", "صربستان") to "RS",
        listOf("georgia", "tbilisi", "گرجستان") to "GE",
        listOf("cyprus", "nicosia", "قبرس") to "CY",
        listOf("estonia", "tallinn", "استونی") to "EE",
        listOf("latvia", "riga", "لتونی") to "LV",
        listOf("lithuania", "vilnius", "لیتوانی") to "LT",
        listOf("croatia", "zagreb", "کرواسی") to "HR",
        listOf("slovakia", "bratislava", "اسلواکی") to "SK",
        listOf("slovenia", "ljubljana", "اسلوونی") to "SI",
        listOf("belarus", "minsk", "بلاروس") to "BY"
    )
    aliases.firstOrNull { (names, _) -> names.any { alias -> normalized.contains(alias.lowercase(Locale.ROOT)) } }
        ?.let { return it.second }

    // Complete country-name lookup from Android's ISO locale database.
    return ISO_COUNTRY_NAMES.entries.firstOrNull { (_, names) ->
        names.any { name -> normalized == name || normalized.contains(" $name ") || normalized.startsWith("$name ") || normalized.endsWith(" $name") }
    }?.key
}

private val ISO_COUNTRY_CODES: Set<String> = Locale.getISOCountries().toSet() + setOf("XK")
private val ISO_COUNTRY_NAMES: Map<String, List<String>> = ISO_COUNTRY_CODES.associateWith { code ->
    val english = Locale("en", code).displayCountry.lowercase(Locale.ROOT)
    val native = Locale("", code).displayCountry.lowercase(Locale.ROOT)
    listOf(english, native).filter { it.isNotBlank() }.distinct()
}


