package com.example.earthonline.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.sp

/**
 * vc67：**自适应字号文本**（Compose 1.8 `TextAutoSize` 的 1.7.x 等价实现）。
 *
 * ## 为什么自己写，而不是直接用 `TextAutoSize`
 * `TextAutoSize` 是 **Compose 1.8 才有的 API**，而本项目锁在 Compose 1.7.x
 * （Haze 1.3.1 要求 1.7.x，见 gradle/libs.versions.toml）。
 * 升级 Compose 会连带打破 Haze 兼容矩阵 —— 属于「为��排版 API 冒全链路风险」，
 * 因此这里用**同版本已稳定存在**的 [rememberTextMeasurer]（`androidx.compose.ui`，
 * Compose 1.4+ 即稳定，无需 opt-in）自实现等价行为，**零新增依赖**。
 *
 * ## 行为
 * 1. 先按 [style] 的原始字号量一次；
 * 2. 放不下就**二分查找**出能放下的最大字号（≥ [minFontSize]）；
 * 3. 到了下限仍放不下，才交给 [overflow]（默认省略号）优雅截断。
 *
 * 效果：主页标题 / 昵称这类「长度不可控」的文本，**先缩到刚好放得下**，
 * 而不是一上来就砍字变成「地球…」。**文字永远完整可读**才是正解。
 *
 * @param minFontSize 允许缩到的下限；再小就改用省略号。
 * @param maxFontSize 上限；默认取 [style] 里的字号。
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 10.sp,
    maxFontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val base = style.copy(
            color = color,
            fontWeight = fontWeight ?: style.fontWeight,
            textAlign = textAlign ?: style.textAlign
        )
        // TextUnit 没有 coerceAtMost，也没有可比较性 —— 全部走 .value（sp 数值）比较。
        val hiSp = maxFontSize.takeOrElse { base.fontSize.takeOrElse { 16.sp } }.value
        val loSp = minFontSize.value.coerceAtMost(hiSp)

        val availablePx = with(density) { maxWidth.toPx() }
        val resolved = remember(text, base, hiSp, loSp, availablePx, measurer) {
            // 宽度无界（如横向滚动的 Chip 行）时无从判断是否放得下 —— 保持原字号。
            if (!availablePx.isFinite() || availablePx <= 0f) {
                hiSp
            } else {
                fun fits(sizeSp: Float): Boolean {
                    val r = measurer.measure(
                        text = text,
                        style = base.copy(fontSize = sizeSp.sp),
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        maxLines = 1,
                        density = density
                    )
                    return r.size.width <= availablePx
                }
                when {
                    fits(hiSp) -> hiSp
                    // 连下限都放不下 —— 交给 Text 自己的省略号裁剪，别再缩了（会糊成一团）。
                    !fits(loSp) -> loSp
                    else -> {
                        var l = loSp
                        var r = hiSp
                        while (r - l > 0.5f) {
                            val mid = (l + r) / 2f
                            if (fits(mid)) l = mid else r = mid
                        }
                        l
                    }
                }
            }
        }

        Text(
            text = text,
            style = base.copy(fontSize = resolved.sp),
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
