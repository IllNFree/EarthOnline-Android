package com.example.earthonline.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/** 列表滚动方向。 */
enum class ScrollDirection { Idle, Up, Down }

/**
 * vc67：**列表滚动方向派生状态**（`derivedStateOf` 降重组）。
 *
 * ## 为什么要它
 * 滚动方向这类高频信号，如果直接读 `listState.firstVisibleItemIndex`，
 * 每滚一格都会让**读它的整块 UI 重组**一次；页面越重掉帧越明显。
 * 这里把它收敛成**一个派生值**：
 *  - 底层用 [snapshotFlow] 采集索引 / 偏移的变化，只在**方向真的翻转**时才写一次状态；
 *  - 对外暴露的 [State] 内部再用 [derivedStateOf] 二次收敛。
 *
 * **最关键的用法**：把它读进 `Modifier.graphicsLayer { }` 的 lambda 里
 * （而不是读进组合逻辑里）—— 这样滚动时**只更新图层属性，零重组**，
 * 满血液态玻璃下也不会因为一个 FAB 而拖垮整页。
 */
@Composable
fun rememberScrollDirection(listState: LazyListState): State<ScrollDirection> {
    // 原始方向信号：只在方向翻转时更新
    val raw = remember { mutableStateOf(ScrollDirection.Idle) }
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val dir = when {
                index > lastIndex -> ScrollDirection.Down
                index < lastIndex -> ScrollDirection.Up
                offset > lastOffset -> ScrollDirection.Down
                offset < lastOffset -> ScrollDirection.Up
                else -> ScrollDirection.Idle
            }
            lastIndex = index
            lastOffset = offset
            if (dir != ScrollDirection.Idle) raw.value = dir
        }
    }
    // 派生：停手后一律回到 Idle（派生值只在被读时重算，不触发额外重组）
    return remember {
        derivedStateOf {
            if (listState.isScrollInProgress) raw.value else ScrollDirection.Idle
        }
    }
}
