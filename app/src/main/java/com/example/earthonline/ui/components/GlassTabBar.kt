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
import com.example.earthonline.ui.theme.GlassSurface

/**
 * vc67：**全局统一的液态玻璃分段 Tab**（主页口径）。
 *
 * ## 为什么要抽成公共组件
 * 此前任务页「日常 / Todo」与背包页「物品 / 收藏」各写了一份胶囊分段控件，
 * 两者外框虽已是玻璃，但**选中段仍是实心 `primary` 色块** ——
 * 实心色块会把身后的壁纸「盖死」，于是二级页顶部与主页的通透感割裂。
 * 现在统一收敛到本组件：**外框走 [GlassSurface]（Haze 真高斯模糊，透出壁纸），
 * 选中段改为半透明琥珀染 + 细描边**，选中态依然清晰，但**不再有实心背景**。
 *
 * 全站二级页顶部 Tab 一律用这一个，保证「主页 ↔ 二级页」材质完全同源。
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
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
) {
    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
 * vc67：分段控件的**单段**。选中态 = 半透明琥珀染 + 1dp 描边（不遮挡壁纸），
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
