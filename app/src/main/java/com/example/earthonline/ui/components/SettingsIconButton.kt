package com.example.earthonline.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.earthonline.ui.navigation.Screen

/**
 * 「更多」按钮所需的行为。由 MainScaffold 统一注入，避免每个页面各自维护一份。
 *
 * v1.2.1：下拉菜单已取消（见 SettingsIconButton 注释），这里只保留「跳转」能力；
 * onAccounting 仍保留 —— 主页快速入口的「记账」格还在用它唤起下载引导。
 */
data class MoreMenuActions(
    /** 跳转到某个路由 */
    val onNavigate: (String) -> Unit,
    /** 记账：打开下载渠道对话框（跳转浏览器） */
    val onAccounting: () -> Unit
)

/**
 * 右上角「设置」图标按钮 —— 一键直达设置页。
 *
 * v1.2.1 导航栏精简：原来的「菜单 更多」下拉菜单（成就 / 收藏 / 记账 / 地图 / 设置 五项）
 * 整个移除。原因是这些入口在主页快速入口和概览卡里都有更短的直达路径，
 * 多一层下拉只是多一次点击，还挡住内容。现在右上角只留一个齿轮，点一下进设置。
 *
 * 保留函数名 [MoreMenuButton] 之外的所有调用点统一用本组件，行为两端一致
 * （Web 端 pages.js 的 home-more-wrap 也换成了 设置 图标 + data-action="nav"）。
 */
@Composable
fun SettingsIconButton(
    actions: MoreMenuActions,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Settings,
    contentDescription: String = "设置",
    /** 已在设置页时可传 false 隐藏自身，避免「点设置进设置」 */
    visible: Boolean = true
) {
    if (!visible) return
    IconButton(
        onClick = { actions.onNavigate(Screen.Settings.route) },
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LocalContentColor.current
        )
    }
}
