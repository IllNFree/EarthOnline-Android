package com.example.earthonline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.earthonline.ui.theme.AmberPrimary

/**
 * vc68：**全局统一的液态玻璃分段 Tab**。
 *
 * ## 定位与形态
 * 此前本组件自带一层 [com.example.earthonline.ui.theme.GlassSurface] 外壳；
 * vc68 起它改为**嵌入在 [GlassTopBar] 的玻璃底板内部**使用 ——
 * 外层已经是一整块 Haze 液态玻璃，内部再套一层玻璃会造成「玻璃套玻璃」
 * （两层独立模糊、边界出现灰缝、GPU 开销翻倍）。
 *
 * 因此现在的形态是：
 *  - 外壳 = [GlassTopBar] 的整块玻璃底板（标题 + Tab 同属一块玻璃）；
 *  - 本组件 = 玻璃底板上的一层**半透明内衬**（极淡 surface 染 + 1dp 描边），
 *    选中段为半透明琥珀染 + 描边 —— 选中态依旧一眼可辨，但不再有实心色块。
 *
 * ## 统一规格
 *  - 段高固定 38dp、圆角 16dp、段间 4dp —— 全站二级页 Tab 行高度完全一致；
 *  - 文字单行不换行（`softWrap = false`），窄屏下靠横向留白兜底，绝不挤成竖排。
 *
 * @param labels Tab 文案。
 * @param selectedIndex 当前选中下标。
 * @param onSelect 点击回调（参数为被点项下标）。
 */
@Composable
fun GlassTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                // 内衬：极淡的 surface 染 + 1dp 描边 —— 在玻璃底板上给出"容器"轮廓，
                // 但自身不再做一次模糊（避免玻璃套玻璃的双重开销与灰缝）
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            labels.forEachIndexed { index, label ->
                GlassTabSegment(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

/**
 * vc68：分段控件的**单段**。选中态 = 半透明琥珀染 + 1dp 描边（不遮挡壁纸），
 * 未选中态 = 完全透明。
 */
@Composable
private fun RowScope.GlassTabSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(shape)
            .bouncyClickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier
                        .background(AmberPrimary.copy(alpha = 0.16f))
                        .border(1.dp, AmberPrimary.copy(alpha = 0.55f), shape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) AmberPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}
