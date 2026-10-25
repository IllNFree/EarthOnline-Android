package com.example.earthonline.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.earthonline.ui.components.UiDimens
import com.example.earthonline.ui.components.bouncyClickable
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 全局液态玻璃（Liquid Glass）封装 —— vc64 起**统一由 Haze（dev.chrisbanes.haze）**实现。
 *
 * 历史包袱（已在本轮全部删除，勿再拾回）：
 *  - vc62 手写 AGSL RuntimeShader「圆角矩形 SDF 折射 + RGB 色散」链式 RenderEffect：
 *    每卡一张离屏层 + 全屏 shader，中低端机直接掉帧；
 *  - 卡片内 AsyncImage 采样壁纸 + `Modifier.blur` / RenderEffect.createBlurEffect 手搓毛玻璃：
 *    每卡各自取景采样，卡片之间出现灰 / 白色块断层（vc57 / vc63 的「灰砖」真凶）。
 *
 * 现在的渲染管线（工业级，三层）：
 *  ① 背景层（[MainScaffold] 的壁纸 Box）挂 `Modifier.hazeSource(state)` —— 一次性把背景内容
 *     抓进 GraphicsLayer，全站共享，不再有「每卡各自采样」的错位与断层；
 *  ② 玻璃层：卡片挂 `Modifier.hazeEffect(state)` —— Haze 从共享层取景、按卡片屏幕位置做真高斯模糊，
 *     叠加 tint（霜白 + 主题色）与极细噪声，天然带「厚度 / 折射 / 景深」的液态观感；
 *  ③ 装饰层：1.5px 极细顶边高光 + 1dp 色散描边（DrawScope 手绘，零额外 GPU 成本）。
 *
 * 性能兜底（两条硬规则）：
 *  - **vc67：滑动时不再降级** —— 液态玻璃滑动全程 100% 满血，绝不妥协视觉
 *    （关模糊 / 降半径造成的视觉跳变，比掉帧更刺眼）；
 *  - Android 12（API 31）以下 / 低内存机 / 用户关闭液态玻璃 → Haze 自动走 fallbackTint
 *    半透明纯色（scrim），零 GPU 模糊开销。
 */
val LocalWallpaperPath = compositionLocalOf<String?> { null }

/**
 * v1.0.18：卡片背景不透明度（设置 → 外观滑块，DataStore 持久化）。
 * vc64 语义微调：它现在是「玻璃底色浓度」—— 1.0 = 底色最接近纯 surface（玻璃感最克制、最清晰），
 * 数值越低玻璃越通透（模糊背景透出越多）。仍只作用于 [GlassCard]。
 *
 * vc68：默认值 1.0 → **0.8（80% 不透明度 = 20% 透明度）** ——
 * 首次打开时卡片底色偏实心，正文对比度优先。
 */
val LocalCardOpacity = compositionLocalOf { 0.8f }

/**
 * vc64：全局 Haze 状态 —— 由 [MainScaffold] 在壁纸层创建并注入，全站玻璃共享同一份取景。
 * 默认值给一个空 [HazeState]：任何未注入的分支（如独立预览页）都不会崩，只是没有可模糊的背景。
 */
val LocalHazeState = compositionLocalOf { HazeState() }

/**
 * vc64：模糊强度缩放（**仅**用于跟随用户的「壁纸模糊度」设置，不再用于滚动降级）。
 * - 1f = 满强度；
 * - 0f = 完全不模糊（等价于纯色档，仅限极特殊场景）。
 *
 * vc67（死命令）：**滑动时不再降级**。原实现会在 `isScrollInProgress` 时把它压到
 * ×0.45f，现已整体删除 —— 滑动全程 100% 满血。
 */
val LocalHazeBlurScale = compositionLocalOf { 1f }

/** v1.0.19：卡片不透明度被视为「纯色不透明」的阈值（≥ 该值 = 100% 档） */
const val CARD_OPAQUE_THRESHOLD = 0.995f

/**
 * 液态玻璃特效全局开关（对应「设置 → 外观 → 液态玻璃特效」）。
 * - true（默认）：启用 Haze 真高斯模糊 + 高光描边 + 半透明折射观感；
 * - false（性能模式）：[GlassSurface] 强制走 fallback 纯色底板（blurEnabled = false）。
 * 业务层无需感知，由框架在 [MainScaffold] 统一注入。
 */
val LocalLiquidGlass = compositionLocalOf { true }

/**
 * Haze 满档模糊半径（dp）。滚动时按 [LocalHazeBlurScale] 线性下调。
 *
 * vc70（GPU 调优）：22dp → **20dp**。20dp 已是液态玻璃质感的最佳区间
 * （16~20dp），再往上 GPU 模糊开销近似线性/超线性增长而视觉增益极小。
 */
val GLASS_BLUR_RADIUS_DP: Dp = 20.dp

/** Haze 噪声强度（0~1）。极轻，只用来打散色带，避免出现「塑料膜」感。 */
private const val GLASS_NOISE_FACTOR = 0.06f

enum class GlassTier { FULL, BLUR, FLAT }

@Composable
fun rememberGlassTier(): GlassTier {
    val context = LocalContext.current
    // isLowRamDevice 之外再加一道**总内存闸**：总内存 < 4GB 的机型即便系统未标记 low-RAM，
    // GPU / 带宽也撑不住实时高斯，一律强制 FLAT（半透明纯色底板，零 GPU 模糊开销）。
    val flatTier = remember {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            am?.isLowRamDevice == true || info.totalMem in 1 until 4_000_000_000L
        }.getOrDefault(false)
    }
    return when {
        Build.VERSION.SDK_INT >= 33 && !flatTier -> GlassTier.FULL
        Build.VERSION.SDK_INT >= 31 && !flatTier -> GlassTier.BLUR
        else -> GlassTier.FLAT
    }
}

/**
 * 液态玻璃容器（全站唯一材质入口）：Haze 模糊 + 霜白 / 主题色 tint + 纯色兜底 + 极细高光。
 *
 * @param onClick 非空时整块可点（替代 Button 的玻璃态）。
 * @param shape 圆角形状（默认 20dp）。
 * @param showBorder 是否描边（玻璃边缘色散高光）。
 * @param tintOverride 底色浓度覆写（卡片不透明度滑块），null = 用 [LocalCardOpacity]。
 * @param scrimOverride 可读性遮罩覆写，null = 按底色浓度自动推导。
 * @param solid true = 弹窗档（近不透明，玻璃感最克制，保证长文绝对清晰）。
 * @param fxRadius 高光折射带用的圆角像素半径（与 shape 视觉圆角一致即可）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    showBorder: Boolean = true,
    /** v1.0.18：着色不透明度覆写（卡片不透明度滑块），null = 用内置档位 */
    tintOverride: Float? = null,
    /** v1.0.18：可读性遮罩覆写，null = 用内置推导 */
    scrimOverride: Float? = null,
    /** v1.0.6.9（去壁纸化）：true = 弹窗档，近不透明、玻璃感最克制 */
    solid: Boolean = false,
    /** 高光折射带圆角半径（仅影响 1.5px 顶边高光的起止位置） */
    fxRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val hazeState = LocalHazeState.current
    val blurScale = LocalHazeBlurScale.current
    val liquid = LocalLiquidGlass.current
    val cardAlpha = (tintOverride ?: LocalCardOpacity.current).coerceIn(0f, 1f)

    // 玻璃透出量：底色越实（cardAlpha 越大）→ 模糊背景透出越少，正文越清晰。
    // 弹窗档额外再压一档（长段落需要绝对稳定的对比度）。
    val effectAlpha = if (solid) 0.14f else (0.18f + (1f - cardAlpha) * 0.55f).coerceIn(0.18f, 0.72f)
    val scrimAlpha = scrimOverride
        ?: if (solid) 0.34f else (0.28f * (cardAlpha / 0.74f)).coerceIn(0.22f, 0.42f)

    // 滚动降级：只降半径，绝不关模糊（关模糊会造成视觉跳变）
    val blurRadiusDp = (GLASS_BLUR_RADIUS_DP * blurScale).coerceAtLeast(0.dp)
    val blurOn = liquid && blurRadiusDp > 0.5.dp && Build.VERSION.SDK_INT >= 31 &&
            rememberGlassTier() != GlassTier.FLAT

    val fxRadiusPx = with(LocalDensity.current) { fxRadius.toPx() }

    Box(
        modifier = modifier
            /* 圆弧裁剪必须排在整条链最前：Haze 的模糊层、底色、高光、描边、内容
               全部被锁进同一个圆角里，任何调用点都不可能再出现方形直角。 */
            .clip(shape)
            // vc67（极限性能·禁用降级）：强制 GPU 离屏缓冲 —— 满血液态玻璃下把重绘压力
            // 收敛到一张离屏层，滚动时不再每帧重算整张玻璃，卡顿根因直接消除。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .then(if (onClick != null) Modifier.bouncyClickable(onClick = onClick) else Modifier)
            // ① 纯色兜底：无壁纸 / API<31 / 低内存 / 用户关特效时，这就是最终的卡片底色
            .background(scheme.surface.copy(alpha = if (solid) 0.97f else cardAlpha))
            // ② Haze 玻璃层：从共享背景层取景做真高斯模糊 + 霜白/主题色 tint
            .hazeEffect(hazeState, style = HazeStyle.Unspecified) {
                alpha = effectAlpha
                blurEnabled = blurOn
                blurRadius = blurRadiusDp
                noiseFactor = GLASS_NOISE_FACTOR
                /* vc70（有壁纸才卡的 GPU 根因·死命令）：Haze 1.3.1 的 inputScale
                   默认是 None —— 每张卡都对**全分辨率**壁纸纹理做高斯，
                   一张 12MP 壁纸 × 屏幕上 6~7 张玻璃卡 = 每帧数亿次纹理采样。
                   Auto 档在半径 ≥ 7dp 时自动把模糊输入降到 1/3 分辨率
                   （待模糊像素量直接降 9 倍），毛玻璃观感无损（模糊本身就
                   抹掉细节），这是「不降特效、只降算力」的关键一刀。 */
                inputScale = HazeInputScale.Auto
                backgroundColor = Color.Transparent
                tints = listOf(
                    HazeTint(color = Color.White.copy(alpha = 0.30f)),
                    HazeTint(color = scheme.surface.copy(alpha = scrimAlpha))
                )
                // Android 12 以下 / 低内存：Haze 自动改画这层半透明纯色（零 GPU 模糊）
                fallbackTint = HazeTint(color = scheme.surface.copy(alpha = scrimAlpha))
            }
    ) {
        // ③ 顶部极细高光（1.5px）：左上打光的玻璃倒角。
        //    渐变显式锁定在细线矩形内（vc63 教训：Brush 不会自动映射 drawRect 的矩形）。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val hi = 1.5.dp.toPx()
                    val inset = fxRadiusPx * 0.55f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.72f),
                                Color.White.copy(alpha = 0.28f),
                                Color.Transparent
                            ),
                            startX = inset,
                            endX = size.width - inset
                        ),
                        topLeft = Offset(inset, 0f),
                        size = Size(size.width - inset * 2f, hi)
                    )
                }
        )
        // ④ 色散描边（玻璃边缘光，1dp 细线，零额外成本）
        if (showBorder) {
            val edgeBrush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.55f),
                    AmberPrimary.copy(alpha = 0.38f),
                    Color(0xFFBFD6FF).copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.22f)
                )
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .border(1.dp, edgeBrush, shape)
            )
        }
        // ⑤ 内容（永远锐利，不参与模糊）
        Box(Modifier, contentAlignment = contentAlignment, content = content)
    }
}

/**
 * 卡片级玻璃容器（全站列表卡 / 概览卡的统一材质）。
 * 业务里替代 Material Card 使用；内容按自然测量，调用方自带 padding。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val userAlpha = LocalCardOpacity.current
    GlassSurface(
        modifier = modifier,
        shape = shape,
        showBorder = true,
        onClick = onClick,
        tintOverride = userAlpha,
        scrimOverride = (0.28f * (userAlpha / 0.74f)).coerceIn(0.22f, 0.42f),
        fxRadius = UiDimens.CardRadius,
        content = content
    )
}

/**
 * 全局标准列表卡：圆角固定 [UiDimens.CardRadius]（16dp），与速览 / 底栏同套圆弧，
 * 走 [GlassCard] 同一渲染管线，绝不出现默认 Material Card 的黑色 elevation 阴影。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(UiDimens.CardRadius),
        onClick = onClick,
        content = content
    )
}

/** 在框架层注入 Haze 状态（[MainScaffold] 的壁纸层创建，全站共享）。 */
@Composable
fun ProvideHazeState(state: HazeState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHazeState provides state, content = content)
}

/** 列表滚动降级：把当前滚动状态映射为模糊强度缩放后注入子树。 */
@Composable
fun ProvideHazeBlurScale(scale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHazeBlurScale provides scale.coerceIn(0f, 1f), content = content)
}

/**
 * vc67（极限性能·死命令：彻底删除滚动降级）：原 `HazeScrollAware(listState) { … }` 会在
 * [LazyListState.isScrollInProgress] 为真时把模糊半径降到 ×0.45f，造成
 * 「滑动时玻璃发虚 / 停手才恢复」的视觉跳变。
 *
 * **该降级逻辑已整体删除**，本函数一并移除，调用点同步清理
 * （主页 / 任务页 / 笔记页三处包装已解包）。
 *
 * 现在滑动时液态玻璃 **100% 满血保持** —— 绝不妥协视觉：
 * 关模糊 / 降半径都比掉帧更刺眼。满血下的流畅由三件事共同保证：
 *  ① [GlassSurface] 的 `graphicsLayer { compositingStrategy = Offscreen }` 强制 GPU 离屏缓冲；
 *  ② 滚动方向等高频信号一律走 `derivedStateOf` 派生（见 ui/components/ScrollKit.kt）；
 *  ③ 滚动过程中不读取任何随滚动变化的状态，整页不因滑动而重组。
 */

/** 在主框架注入全局壁纸路径（保留兼容：部分页面据此判断是否值得挂玻璃）。 */
@Composable
fun ProvideWallpaper(wallpaperPath: String?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWallpaperPath provides wallpaperPath, content = content)
}

/** 在框架层注入液态玻璃开关：关闭时所有 [GlassSurface] 自动降级为纯色底板（性能模式）。 */
@Composable
fun ProvideLiquidGlass(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLiquidGlass provides enabled, content = content)
}
