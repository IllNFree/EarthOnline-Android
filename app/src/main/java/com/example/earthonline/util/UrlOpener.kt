package com.example.earthonline.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 用系统浏览器打开一条链接 —— 全 App 唯一入口（记账下载、检查更新等都用它）。
 *
 * 为什么必须抽成一处、并且不能裸调 `startActivity`：
 *
 * 1) **没装浏览器 / 系统里浏览器的默认处理被禁用**时，`startActivity` 会抛
 *    `ActivityNotFoundException`，裸调用就是一次闪退。这里先用 `resolveActivity` 预检：
 *    有浏览器才发起跳转，没有就直接给提示、连系统那层「无匹配」的异常通道都不进。
 *    预检本身若出问题则**默认放行**（交给下面的 catch 兜底），不会把「有浏览器」误判成没有。
 * 2) 注意 **预检在 Android 11+ 受包可见性限制**：manifest 里没有 `<queries>` 声明
 *    http/https 浏览意图时，`resolveActivity` 对浏览器永远返回 null —— 实测踩过这个坑
 *    （装了 Chrome 却被判成「没有浏览器」，点下载只弹提示）。`startActivity` 不受该限制，
 *    所以真正的兜底是下面的 catch，预检只是让提示更准确。
 * 3) 少数精简 ROM 上查询与跳转都可能抛 `SecurityException` / `AndroidRuntimeException`，
 *    所以整段再包一层 `catch (Throwable)` 兜底 ——
 *    这个函数**保证不向调用方抛异常**。
 * 4) 传入的 Context 不一定是 Activity（可能来自 Application、Worker、Composable 的
 *    LocalContext 中间层）。非 Activity Context 不带 `FLAG_ACTIVITY_NEW_TASK` 时，
 *    `startActivity` 会抛 `AndroidRuntimeException`；统一补上这个 flag，
 *    Activity Context 带了也没有副作用。
 *
 * @return true 表示已成功发起跳转；false 表示没跳成（此时已弹提示）
 */
fun openUrlInBrowser(
    context: Context,
    url: String,
    /** 找不到浏览器时的提示文案 */
    noBrowserMessage: String = "未找到可用的浏览器，请先安装浏览器应用",
    /** 其它异常时的提示前缀 */
    failurePrefix: String = "无法打开链接"
): Boolean {
    if (url.isBlank()) return false
    val appContext = context.applicationContext ?: context
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /* 三层保险，顺序很重要：
       ① 预检（仅用于给出更准确的提示）：有浏览器就直接跳；没有就提示，且**不发起跳转**。
          预检失败时默认「能跳」（getOrDefault(true)），绝不因为查询本身出问题就把
          「有浏览器」误判成「没有」—— 那样用户点了没反应，比闪退更让人困惑。
          注意 预检在 Android 11+ 受**包可见性**限制：manifest 里的 <queries> 必须声明
             http/https 浏览意图，否则 resolveActivity 永远返回 null（踩过：明明装了 Chrome
             却被判成没浏览器）。而 startActivity 不受该限制，所以 ② 才是真正的兜底。
       ② 真正发起跳转（唯一权威判据），③ 捕获一切异常兜底。 */
    val canResolve = runCatching {
        context.packageManager?.resolveActivity(intent, 0) != null
    }.getOrDefault(true)

    if (!canResolve) {
        toast(appContext, noBrowserMessage)
        return false
    }

    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        // 系统里没有任何应用能处理这条链接（没装浏览器 / 浏览器被禁用）
        toast(appContext, noBrowserMessage)
        false
    } catch (t: Throwable) {
        // SecurityException、非 Activity Context 缺 NEW_TASK 抛的 AndroidRuntimeException、
        // 以及定制 ROM 的花式异常 —— 一律兜住，绝不让它冒到 UI 线程变成闪退
        toast(appContext, "$failurePrefix：${t.message ?: "未知错误"}")
        false
    }
}

/**
 * 带容错的 Toast。用 applicationContext，避免 Activity 已经销毁时
 * `Toast.makeText(activityContext, ...)` 抛 `BadTokenException`。
 */
private fun toast(context: Context, message: String) {
    runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}
