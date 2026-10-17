# دليل واجهة MANHWA (ManhwaKit): الزجاج والنيون

ده شرح لنظام التصميم اللي التطبيق ماشي عليه، ومعاه الكود كامل عشان تنقله لأي برنامج تاني.
الجزء الأساسي مكتوب بـ Jetpack Compose (Kotlin)، وفي الآخر فيه نسخة CSS لبرامج الويب.

---

## 1. فكرة التصميم في سطرين

- **خلفية سودا تقريباً** عليها نور أحمر خفيف وبقع ضوء (bokeh)، كأنك بتبص على مدينة بالليل.
- **كروت من زجاج مدخّن** نصها شفاف، وفوقها لمعة خفيفة، وحواليها **حافة نيون** بتنوّر بلون التطبيق الأساسي.
- **عناوين بيضا وتقيلة (Black weight)** عليها ظل ملوّن، والنصوص الثانوية بلون أبيض شفاف.
- **أيقونات Material Symbols Rounded بس**، من غير إيموجي.

---

## 2. الألوان

### لون التطبيق الأساسي (ثيم Danghwa)
| الاسم | الكود | بيتستخدم في |
|---|---|---|
| `primary` | `#FF3B4E` | النيون، الأزرار، العناوين الصغيرة |
| `primaryContainer` | `#8E0F1F` | الخلفيات المليانة |
| `background` / `surface` | `#07070A` | خلفية الشاشة |
| `surfaceContainer` | `#161116` | أسفل الكارت الزجاج |
| `surfaceContainerHighest` | `#271C22` | أعلى الكارت الزجاج |
| `onSurface` | `#F1E9EA` | النص العادي |
| `Ink` | `#050507` | أغمق أسود |
| خلفية الـ Backdrop | `#030304` | ورا كل الشاشات الزجاج |

> **ملحوظة:** كل حاجة بتاخد لونها من `MaterialTheme.colorScheme.primary`. لو غيّرت الـ primary، النيون والأزرار كلها هتتغير معاه.

### ألوان الحالة (Accent)
| الاسم | الكود | المعنى |
|---|---|---|
| **Ember** | `#FF6A2B` | شغال دلوقتي، تحذير خفيف، حاجة "سخنة" |
| **Mint** | `#3DDC97` | نجح، متصل، تمام |
| **Amber** | `#FFB020` | انتباه، مستني، حصة قربت تخلص |
| Crimson | `#E5173F` | خطر أو حذف |
| Obsidian | `#060204` | خلفية الشاشات الخاصة (زي "الجديد في التحديث") |

### شفافية النص
- عنوان: `Color.White` (100%).
- نص ثانوي: `White.copy(alpha = 0.7f)`.
- تلميح (hint) أو حاجة مش مختارة: `0.5f` لحد `0.75f`.

---

## 3. الأشكال والمقاسات

| العنصر | الـ Shape | الـ Padding |
|---|---|---|
| كارت كبير | `RoundedCornerShape(26.dp)` | `18.dp` |
| زرار (Neon / Ghost) | `RoundedCornerShape(16.dp)` | أفقي `16-18.dp`، رأسي `12.dp` |
| خانة بحث | `RoundedCornerShape(18.dp)`، الارتفاع `50.dp` | أفقي `14.dp` |
| Chip | `RoundedCornerShape(50)` (كبسولة) | `16 × 9.dp` |
| IconOrb | `42.dp`، والركن = المقاس ÷ 3 | الأيقونة 50% من المقاس |
| GlowIcon | دايرة `54.dp` | الأيقونة 55% من المقاس |
| شريط تقدم | ارتفاع `6.dp`، كبسولة | — |

- المسافة بين العناصر جوه الكارت: `Arrangement.spacedBy(12.dp)`.
- المسافة بين الأيقونة والنص: `8.dp` في الزرار، و`12-14.dp` في الصفوف.

---

## 4. الأساس: `glass()` و`neonGlow()`

دول الاتنين اللي بيعملوا الشكل كله. أي كارت أو زرار هو `Modifier.glass(...)` وخلاص.

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * زجاج مدخّن: لوح نصه شفاف، فوقه لمعة، وحواليه حافة نيون ونور خفيف.
 * accent = true بيخلي الحافة أقوى (للكارت المهم أو المختار).
 */
fun Modifier.glass(shape: Shape, accent: Boolean = false, alpha: Float = 0.55f): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val brushes = remember(colors.primary, colors.surfaceContainerHighest, colors.surfaceContainer, accent, alpha) {
        Triple(
            // جسم الزجاج
            Brush.verticalGradient(
                listOf(
                    colors.surfaceContainerHighest.copy(alpha = alpha + 0.1f),
                    colors.surfaceContainer.copy(alpha = alpha),
                ),
            ),
            // اللمعة اللي فوق على الشمال
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.09f),
                0.35f to Color.Transparent,
                1f to Color.Transparent,
            ),
            // الحافة
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (accent) 0.4f else 0.22f),
                    colors.primary.copy(alpha = if (accent) 0.95f else 0.5f),
                    Color.White.copy(alpha = 0.06f),
                ),
            ),
        )
    }
    this
        .neonGlow(shape, colors.primary, if (accent) 0.22f else 0.1f)
        .clip(shape)
        .background(brushes.first, shape)
        .background(brushes.second, shape)
        .border(1.dp, brushes.third, shape)
}

/** نور نيون حوالين الشكل: 4 خطوط بتبهت لبره. أخف بكتير من blur حقيقي. */
fun Modifier.neonGlow(shape: Shape, color: Color, strength: Float = 0.18f, spread: Dp = 10.dp): Modifier =
    if (strength <= 0f) this else neonGlow(shape, color, { strength }, spread)

/** نسخة متحركة: القيمة بتتقري وقت الرسم بس، فالنبض مش بيعمل recomposition للكارت. */
fun Modifier.neonGlow(shape: Shape, color: Color, strength: () -> Float, spread: Dp = 10.dp): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val step = spread.toPx() / 4f
        val strokes = (4 downTo 1).map { i -> i to Stroke(width = step * i * 2f) }
        onDrawBehind {
            val value = strength()
            if (value <= 0f) return@onDrawBehind
            strokes.forEach { (i, stroke) -> drawOutline(outline, color.copy(alpha = value / i), style = stroke) }
        }
    }
```

### قيم `alpha` المستخدمة
| الاستخدام | القيمة |
|---|---|
| كارت عادي | `0.55f` (الافتراضي) |
| كارت مهم أو خانة بحث | `0.45f` مع `accent = true` |
| زرار Ghost أو Chip مش مختار | `0.35f` |

---

## 5. الخلفية `GlassBackdrop`

دي بتتحط ورا أي شاشة فيها زجاج. من غيرها الزجاج هيبان رمادي باهت.

```kotlin
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF030304))) {
        // طبقة لوحدها بتترسم مرة واحدة لكل مقاس، فالـ scroll مش بيعيد رسمها
        Spacer(
            Modifier.matchParentSize().graphicsLayer().drawWithCache {
                val w = size.width
                val h = size.height
                val top = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(w * 0.95f, h * 0.04f), radius = w * 1.05f,
                )
                val bottom = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.16f), Color(0xFF3A0610).copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(w * 0.05f, h * 0.92f), radius = w * 1.2f,
                )
                val dots = Bokeh.map { (x, y, r, a) ->
                    val c = Offset(w * x, h * y)
                    Triple(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = a), primary.copy(alpha = a * 0.35f), Color.Transparent),
                            center = c, radius = w * r,
                        ),
                        c, w * r,
                    )
                }
                val vignette = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    center = Offset(w / 2f, h / 2f), radius = maxOf(w, h) * 0.75f,
                )
                onDrawBehind {
                    drawRect(top)
                    drawRect(bottom)
                    dots.forEach { (brush, c, r) -> drawCircle(brush, radius = r, center = c) }
                    drawRect(vignette)
                }
            },
        )
        content()
    }
}

private data class Dot(val x: Float, val y: Float, val r: Float, val a: Float)

// أماكن بقع الضوء: نسبة من العرض والطول، ونص القطر، والشفافية
private val Bokeh = listOf(
    Dot(0.82f, 0.18f, 0.10f, 0.16f),
    Dot(0.12f, 0.30f, 0.06f, 0.12f),
    Dot(0.65f, 0.46f, 0.04f, 0.14f),
    Dot(0.25f, 0.62f, 0.12f, 0.08f),
    Dot(0.90f, 0.72f, 0.07f, 0.12f),
    Dot(0.45f, 0.86f, 0.05f, 0.14f),
    Dot(0.05f, 0.08f, 0.08f, 0.10f),
)
```

---

## 6. المكونات (ManhwaKit)

### التدرج الأساسي
```kotlin
@Composable
fun accentBrush(): Brush {
    val primary = MaterialTheme.colorScheme.primary
    return Brush.horizontalGradient(listOf(primary, lerp(primary, Color.Black, 0.45f)))
}
```

### NeonButton: الزرار الأساسي (الأكشن المهم)
مليان بالتدرج، وحواليه نيون، والنص أبيض وتقيل. **استخدم واحد بس في الشاشة**، للأكشن الأهم.
```kotlin
@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .neonGlow(RoundedCornerShape(16.dp), colors.primary, 0.25f, 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accentBrush())
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
    }
}
```

### GhostButton: الزرار الثانوي
زجاج شفاف من غير تدرج. ده للأكشنات التانية، زي "إلغاء" أو "هات مفتاح" أو "مشاركة".
```kotlin
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .glass(RoundedCornerShape(16.dp), alpha = 0.35f)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}
```

### IconOrb: زرار أيقونة (رجوع، إعدادات، أكشن في الـ TopBar)
```kotlin
@Composable
fun IconOrb(icon: ImageVector, active: Boolean = false, size: Int = 42, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size.dp)
            .then(
                if (active) {
                    Modifier.neonGlow(CircleShape, colors.primary, 0.35f, 10.dp).clip(CircleShape).background(accentBrush())
                } else {
                    Modifier.glass(RoundedCornerShape((size / 3).dp), alpha = 0.45f)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (active) Color.White else lerp(colors.primary, Color.White, 0.55f),
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}
```

### TopBar: شريط العنوان
شفاف، فيه زرار رجوع زجاج، وعنوان أبيض تقيل عليه ظل بلون الـ primary.
```kotlin
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack, onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(
                    shadow = Shadow(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), blurRadius = 22f),
                ),
                fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
        actions()
    }
}
```
> **في العربي:** استخدم أيقونة `AutoMirroredRounded.ArrowBack`، عشان تتقلب لوحدها في الـ RTL.

### Chip: فلتر أو اختيار
```kotlin
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = Color.White.copy(alpha = if (selected) 1f else 0.75f),
        maxLines = 1,
        modifier = Modifier
            .then(
                if (selected) Modifier.neonGlow(shape, MaterialTheme.colorScheme.primary, 0.2f, 8.dp).clip(shape).background(accentBrush())
                else Modifier.glass(shape, alpha = 0.35f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
```

### SearchField: خانة البحث
```kotlin
@Composable
fun SearchField(value: String, hint: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().height(50.dp)
            .glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.45f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MaterialSymbols.Rounded.Search, contentDescription = null, tint = colors.primary,
            modifier = Modifier.size(22.dp).neonGlow(CircleShape, colors.primary, 0.25f, 8.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f), maxLines = 1)
            }
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

### SectionTitle: عنوان القسم
شريط نيون رأسي صغير على جنب، وجنبه العنوان.
```kotlin
@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(4.dp).height(if (subtitle != null) 38.dp else 24.dp)
                .neonGlow(RoundedCornerShape(2.dp), colors.primary, 0.3f, 6.dp)
                .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.2f))), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
```

### GlowBar: شريط التقدم
```kotlin
@Composable
fun GlowBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f))) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp).background(
                Brush.horizontalGradient(listOf(lerp(colors.primary, Color.Black, 0.3f), colors.primary, Color.White)),
                RoundedCornerShape(50),
            ),
        )
    }
}
```

### GlowIcon: أيقونة جوه دايرة منوّرة (رأس الكارت)
لونها بيتحدد على حسب الحالة: Mint لو تمام، Amber لو انتباه، Ember لو شغال. ولو `pulse = true`، النور بيتنفس.
```kotlin
@Composable
fun GlowIcon(icon: ImageVector, color: Color, size: Int = 54, pulse: Boolean = false) {
    val glow = if (pulse) {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0.18f, targetValue = 0.42f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "glowValue",
        )
    } else null
    Box(
        modifier = Modifier.size(size.dp)
            .neonGlow(CircleShape, color, { glow?.value ?: 0.22f }, 12.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.12f))))
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}
```

---

## 7. شكل الشاشة الكاملة (القالب)

```kotlin
@Composable
fun MyScreen(onBack: () -> Unit) {
    GlassBackdrop {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "العنوان", subtitle = "وصف صغير", onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { SectionTitle("القسم الأول", subtitle = "تفاصيل") }
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .glass(RoundedCornerShape(26.dp), accent = true, alpha = 0.45f)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlowIcon(MaterialSymbols.Rounded.Speed, Color(0xFF3DDC97))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("عنوان الكارت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
                                Text("شرح صغير", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        GlowBar(0.6f)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeonButton("ابدأ", Modifier.weight(1f), MaterialSymbols.Rounded.PlayArrow) { }
                            GhostButton("إلغاء", Modifier.weight(1f)) { }
                        }
                    }
                }
            }
        }
    }
}
```

---

## 8. قواعد لازم تمشي عليها

1. **الأيقونات Material Symbols Rounded بس.** ممنوع إيموجي في النص.
2. **NeonButton واحد بس في كل كارت**، وده للأكشن الأهم. الباقي كله GhostButton.
3. **حط الـ `GlassBackdrop` دايماً ورا الزجاج.** الزجاج على خلفية فاتحة بيبوظ الشكل.
4. **الألوان من `MaterialTheme.colorScheme`**، ومش مكتوبة ثابتة، ما عدا ألوان الحالة (Ember وMint وAmber).
5. **ألوان الحالة ليها معنى ثابت:** Mint = تمام، Amber = انتباه، Ember = شغال أو سخن، Crimson = خطر. ما تستخدمهمش للزينة.
6. **الحركة خفيفة:** النبض (`pulse`) يبقى للحاجة الشغالة دلوقتي بس، ومدته حوالي 1400ms. اقرا قيمة الحركة جوه الرسم (`{ glow.value }`)، مش في الـ composition، عشان الأداء.
7. **ادعم RTL:** استخدم `AutoMirrored` للأسهم، و`start/end` بدل `left/right`.
8. **ما تستخدمش blur حقيقي.** `neonGlow` معمول بخطوط بتبهت، فهو سريع على الموبايلات الضعيفة.

---

## 9. نسخة CSS (لو برنامجك ويب)

```css
:root {
  --primary: #FF3B4E;
  --primary-dark: #8c2029;          /* primary ممزوج بـ 45% أسود */
  --bg: #030304;
  --surface-hi: #271C22;
  --surface: #161116;
  --text: #FFFFFF;
  --text-dim: rgba(255,255,255,.7);
  --ember: #FF6A2B; --mint: #3DDC97; --amber: #FFB020; --crimson: #E5173F;
}

body {
  margin: 0; color: var(--text); font-family: system-ui, "Noto Sans Arabic", sans-serif;
  background:
    radial-gradient(circle at 95% 4%, rgba(255,59,78,.30), transparent 60%),
    radial-gradient(circle at 5% 92%, rgba(255,59,78,.16), rgba(58,6,16,.12) 40%, transparent 70%),
    var(--bg);
  min-height: 100vh;
}

/* الزجاج */
.glass {
  background:
    linear-gradient(135deg, rgba(255,255,255,.09), transparent 35%),
    linear-gradient(180deg, rgba(39,28,34,.65), rgba(22,17,22,.55));
  border: 1px solid rgba(255,59,78,.5);
  border-radius: 26px;
  box-shadow: 0 0 10px rgba(255,59,78,.10), 0 0 4px rgba(255,59,78,.10);
  padding: 18px;
}
.glass.accent { border-color: rgba(255,59,78,.95); box-shadow: 0 0 14px rgba(255,59,78,.22); }

/* الأزرار */
.btn-neon, .btn-ghost {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  border-radius: 16px; padding: 12px 18px; color: #fff; cursor: pointer;
  font-weight: 900; font-size: 14px;
}
.btn-neon {
  background: linear-gradient(90deg, var(--primary), var(--primary-dark));
  border: 1px solid rgba(255,255,255,.25);
  box-shadow: 0 0 12px rgba(255,59,78,.25);
}
.btn-ghost {
  font-weight: 700;
  background: linear-gradient(180deg, rgba(39,28,34,.45), rgba(22,17,22,.35));
  border: 1px solid rgba(255,59,78,.4);
}

/* العنوان */
.title { font-weight: 900; text-shadow: 0 0 22px rgba(255,59,78,.6); }

/* شريط التقدم */
.glow-bar { height: 6px; border-radius: 99px; background: rgba(255,255,255,.1); overflow: hidden; }
.glow-bar > i { display: block; height: 100%; background: linear-gradient(90deg, #b32937, var(--primary), #fff); }

/* أيقونة منورة */
.glow-icon {
  --c: var(--mint);
  width: 54px; height: 54px; border-radius: 50%; display: grid; place-items: center;
  background: radial-gradient(color-mix(in srgb, var(--c) 45%, transparent), color-mix(in srgb, var(--c) 12%, transparent));
  border: 1px solid color-mix(in srgb, var(--c) 60%, transparent);
  box-shadow: 0 0 12px color-mix(in srgb, var(--c) 30%, transparent);
}
.glow-icon.pulse { animation: breathe 1.4s ease-in-out infinite alternate; }
@keyframes breathe { to { box-shadow: 0 0 22px color-mix(in srgb, var(--c) 55%, transparent); } }
```

للأيقونات على الويب، استخدم خط Google **Material Symbols Rounded**:
```html
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded" />
<span class="material-symbols-rounded">speed</span>
```

---

## 10. مكان الكود الأصلي في المشروع

| الملف | فيه إيه |
|---|---|
| `app/src/main/java/eu/kanade/presentation/components/ManhwaKit.kt` | TopBar وIconOrb وNeonButton وGhostButton وChip وSearchField وSectionTitle وGlowBar |
| `app/src/main/java/eu/kanade/presentation/components/Glass.kt` | `glass()` و`neonGlow()` و`GlassBackdrop` |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/stats/ReadingGameCards.kt` | `GlowIcon` و`GlowProgress` |
| `app/src/main/java/eu/kanade/presentation/theme/colorscheme/DanghwaColorScheme.kt` | ألوان الثيم كلها (غامق وفاتح) |
