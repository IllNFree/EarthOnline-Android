package com.example.earthonline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.earthonline.ui.theme.GlassSurface

/**
 * vc68：**全局统一的液态玻璃顶栏**（二级页头部标准件）。
 *
 * ## 为什么要有它
 * 此前每个二级页各写一份 `TopAppBar(containerColor = Color.Transparent)`：
 * 标题栏区域**完全透明**，壁纸直接裸露在文字后面 —— 这正是用户反馈的
 * 「壁纸直接透出显得割裂」。而主页的卡片是 Haze 液态玻璃，两边材质不同源，
 * 于是「主页 ↔ 二级页」之间出现明显的割裂感。
 *
 * 现在统一收敛到本组件：**标题行 +（可选）Tab 行**全部包在同一块
 * Haze 液态玻璃底板里（[GlassSurface] → 真高斯模糊，壁纸被"裹"进玻璃），
 * 底板只保留底部两角圆角，顶部与屏幕边缘齐平，视觉上是一整块连续的头部区域。
 *
 * ## 关键设计
 * - **材质同源**：走 [GlassSurface]（Haze），与主页卡片、底部导航胶囊完全一致；
 * - **文字清晰**：顶栏底板固定偏实心（tint 0.92 / scrim 0.46），保证文字
 *   在任意壁纸上都读得清（用户明确要求"文字清晰"）；
 * - **视觉高度统一**：标题行固定 64dp（与 Material3 TopAppBar 完全一致），
 *   有 Tab 的页面在下方追加 Tab 行 —— 全站二级页头部高度严格对齐；
 * - **零重组**：本组件只接收标题 / 图标 / 操作三个稳定槽位，滚动时不产生任何状态写入。
 *
 * @param title 标题内容（自定义标题时传自定义 Row，内部已套好 titleLarge 文字样式）。
 * @param navigationIcon 左侧导航图标（如返回键），无则传 null。
 * @param actions 右侧操作区。
 * @param tab 标题行下方的附加行（Tab / 分段控件），无则传 null。
 */
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    tab: (@Composable () -> Unit)? = null
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        // 顶部与屏幕齐平、底部两角圆角 —— 视觉上是一整块连续的"玻璃头部"
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        contentAlignment = Alignment.TopStart,
        // 顶栏文字必须清晰：玻璃底板偏实心，与卡片的通透感区分开
        tintOverride = 0.92f,
        scrimOverride = 0.46f
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 状态栏 + 刘海横向安全区：标题不被通知栏 / 挖孔压住
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                    // 标题行高度与 Material3 TopAppBar 对齐，全站二级页头部高度统一。
                    // v2.0.0 P1：改为最小高度 —— 大字号下顶栏可增高，标题不再被硬裁。
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigationIcon?.invoke()
                Box(Modifier.weight(1f)) {
                    // 复刻 TopAppBar 的标题文字样式，各页面原有 `Text("…")` 无需改动
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                        title()
                    }
                }
                actions()
            }
            // Tab 行（任务页「日常/Todo」、背包页「物品/收藏」等）—— 与标题同属一块玻璃
            tab?.let {
                Box(Modifier.fillMaxWidth()) { it() }
            }
        }
    }
}
