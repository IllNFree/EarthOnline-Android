package com.example.earthonline.ui.notes

import com.example.earthonline.ui.components.AnimatedAlertDialog
import com.example.earthonline.ui.components.GlassDialog

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.earthonline.ui.components.GlassTextField
import com.example.earthonline.ui.components.MoreMenuActions
import com.example.earthonline.ui.components.UiDimens
import com.example.earthonline.ui.theme.AmberPrimary
import com.example.earthonline.ui.theme.GlassCard
import com.example.earthonline.ui.theme.GlassSurface
import com.example.earthonline.util.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 笔记编辑页（vc64：块级任务列表编辑器）。
 *
 * 历史方案全部废弃，原因如实记录（勿回退）：
 *  ① 「Markdown 源码 + 字符替换」：勾选靠把 `- [ ] ` 替换成 `- [x] `，一旦用户正在输入、
 *     选区偏移就会错位 —— 表现就是吞字 / 光标乱跳（用户反复反馈的痛点）；
 *  ② 「独立待办区 + RichTextState 富文本」：待办被抽到正文之外，视觉上不是一块，
 *     且在「正文 ←→ 待办」两处状态之间搬 Markdown 行，仍会丢字。
 *
 * 现在的模型是**真正的块（Block）**：
 *  - 一篇笔记 = [NoteBlock] 列表，每个块自带 [TextFieldValue]（含选区）与类型；
 *  - [BlockKind.TASK] 块的**行首就是 Compose 原生 Checkbox**，文字紧跟其后 —— 真·所见即所得；
 *  - 勾选只改 `done` 布尔值，**一个字符都不动**（删除线由 textStyle 的 LineThrough 表达），
 *    因此不存在任何吞字 / 跳光标的可能；
 *  - 工具栏「待办」= 把当前块**转成任务块**（再点一次转回正文），而不是插入字符标记；
 *  - 回车在任务块里继续开新任务块（空任务回车退出清单），退格空块回并到上一块；
 *  - 持久化仍是标准 Markdown（`# 标题` / `- ` / `1. ` / `- [x] `），跨端契约零改动。
 *
 * 关于「库选型」的说明（写在源码里而不是藏在对话里）：
 * 用户点名的 ComposeTextEditor（com.darkrockstudios）确实**内置块级 Task List**，
 * 但其最低发布版本 1.0.0 要求 Kotlin 2.3.0 + Compose 1.9.3，最新版 3.1.1 更要求
 * Kotlin 2.4.10 + Compose 1.12 —— 本项目是 Kotlin 2.1.21 + Compose BOM 2024.10.01（1.7.x），
 * 强行引入会连带 AGP / KSP / Room / Hilt / 高德 / 本地推理 so 全链路连锁升级，
 * 属于「为了一个清单控件炸掉整条构建链」。按红线「编译冲突即换稳定方案」，
 * 这里改为自研块编辑器：零外部依赖、零迁移成本，且行为完全可控。
 */

/* ============================ 块模型 ============================ */

private enum class BlockKind { TEXT, H1, H2, BULLET, ORDERED, TASK }

/**
 * 笔记块（不可变数据，更新即整元素替换 → 精确触发该块重组，其它块不受影响）。
 * @param done 仅 [BlockKind.TASK] 生效：勾选态（真布尔，绝不是字符串标记）
 */
private data class NoteBlock(
    val id: Long,
    val kind: BlockKind = BlockKind.TEXT,
    val value: TextFieldValue = TextFieldValue(""),
    val done: Boolean = false
)

/* ---------------------- Markdown ↔ 块（序列化） ---------------------- */

private const val MD_BULLET = "- "
private val MD_TASK_RE = Regex("^\\s*[-*+] \\[([xX ])]\\s+(.*)$")
private val MD_BULLET_RE = Regex("^\\s*[-*+]\\s+(.*)$")
private val MD_ORDERED_RE = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val MD_INLINE_RE = Regex("""\*\*|~~|<u>|</u>|\*""")

/** 行内样式常量（同一逻辑样式永远是同一个对象，增删才能精确配对） */
private val MD_BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val MD_ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val MD_STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val MD_UNDER = SpanStyle(textDecoration = TextDecoration.Underline)

/**
 * Markdown 行内标记 → AnnotatedString（加粗 / 斜体 / 删除线 / 下划线）。
 * vc66（加粗持久化修复）：加粗必须带回「伪粗体投影」—— 国产中文字体族普遍没有 Bold 字重，
 * 只还原 fontWeight 时肉眼看不出加粗，用户就认为「保存失效」。存进库里的 `**…**` 一直都在，
 * 丢的是重描投影；这里把投影按当前正文颜色补回去，读出来的加粗与写进去的视觉一致。
 */
private fun mdToAnnotated(src: String, boldShadow: Shadow? = null): AnnotatedString = buildAnnotatedString {
    val boldStyle = if (boldShadow != null) MD_BOLD.copy(shadow = boldShadow) else MD_BOLD
    val stack = ArrayList<SpanStyle>()
    var i = 0
    while (i < src.length) {
        val m = MD_INLINE_RE.find(src, i)
        if (m == null) {
            append(src.substring(i))
            break
        }
        if (m.range.first > i) append(src.substring(i, m.range.first))
        when (m.value) {
            "**", "*", "~~" -> {
                val style = if (m.value == "**") boldStyle else if (m.value == "*") MD_ITALIC else MD_STRIKE
                if (stack.lastOrNull() == style) {
                    pop()
                    stack.removeAt(stack.lastIndex)
                } else {
                    pushStyle(style)
                    stack.add(style)
                }
            }
            "<u>" -> {
                pushStyle(MD_UNDER)
                stack.add(MD_UNDER)
            }
            "</u>" -> {
                if (stack.removeLastOrNull() != null) pop()
            }
        }
        i = m.range.last + 1
    }
}

/** AnnotatedString → Markdown 行内标记（按字符位标记，边界处成对插入符号） */
private fun annotatedToMd(s: AnnotatedString): String {
    val n = s.text.length
    if (n == 0) return ""
    val bold = BooleanArray(n)
    val ital = BooleanArray(n)
    val strike = BooleanArray(n)
    val under = BooleanArray(n)
    s.spanStyles.forEach { r ->
        val a = r.start.coerceIn(0, n)
        val b = r.end.coerceIn(0, n)
        val st = r.item
        if (st.fontWeight == FontWeight.Bold || st.shadow != null) for (k in a until b) bold[k] = true
        if (st.fontStyle == FontStyle.Italic) for (k in a until b) ital[k] = true
        if (st.textDecoration == TextDecoration.LineThrough) for (k in a until b) strike[k] = true
        if (st.textDecoration == TextDecoration.Underline) for (k in a until b) under[k] = true
    }
    val sb = StringBuilder()
    fun openClose(flags: BooleanArray, mark: String, idx: Int, emit: (String) -> Unit) {
        val prev = if (idx > 0) flags[idx - 1] else false
        val cur = flags[idx]
        if (cur && !prev) emit(mark)
        if (!cur && prev) emit(mark)
    }
    for (k in 0 until n) {
        openClose(bold, "**", k) { sb.append(it) }
        openClose(ital, "*", k) { sb.append(it) }
        openClose(strike, "~~", k) { sb.append(it) }
        openClose(under, if (flags(under, k)) "<u>" else "</u>", k) { sb.append(it) }
        sb.append(s.text[k])
    }
    // 收尾：仍处于开启状态的标记必须闭合，否则下次读回来会整段错乱
    if (bold[n - 1]) sb.append("**")
    if (ital[n - 1]) sb.append("*")
    if (strike[n - 1]) sb.append("~~")
    if (under[n - 1]) sb.append("</u>")
    return sb.toString()
}

private fun flags(a: BooleanArray, i: Int): Boolean = i < a.size && a[i]

/** 块列表 → Markdown 正文（有序列表按连续段自动编号） */
private fun blocksToMarkdown(blocks: List<NoteBlock>): String {
    val sb = StringBuilder()
    var ord = 0
    blocks.forEachIndexed { idx, b ->
        val text = annotatedToMd(b.value.annotatedString)
        val line = when (b.kind) {
            BlockKind.H1 -> "# $text"
            BlockKind.H2 -> "## $text"
            BlockKind.BULLET -> "$MD_BULLET$text"
            BlockKind.ORDERED -> "${++ord}. $text"
            BlockKind.TASK -> if (b.done) "- [x] $text" else "- [ ] $text"
            BlockKind.TEXT -> text
        }
        sb.append(line)
        if (idx != blocks.lastIndex) sb.append('\n')
        if (b.kind != BlockKind.ORDERED) ord = 0
    }
    return sb.toString().trim()
}

/** Markdown 正文 → 块列表（任务行行首的 `[x]` 只用来还原勾选态，之后不再依赖字符） */
private fun markdownToBlocks(md: String, boldShadow: Shadow? = null): List<NoteBlock> {
    if (md.isBlank()) return listOf(NoteBlock(id = 1L))
    val out = ArrayList<NoteBlock>()
    var seed = 0L
    md.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        seed++
        val id = seed
        when {
            line.startsWith("# ") -> out.add(
                NoteBlock(id, BlockKind.H1, TextFieldValue(mdToAnnotated(line.removePrefix("# ").trim(), boldShadow)))
            )
            line.startsWith("## ") -> out.add(
                NoteBlock(id, BlockKind.H2, TextFieldValue(mdToAnnotated(line.removePrefix("## ").trim(), boldShadow)))
            )
            MD_TASK_RE.containsMatchIn(line) -> {
                val m = MD_TASK_RE.find(line)!!
                out.add(
                    NoteBlock(
                        id = id,
                        kind = BlockKind.TASK,
                        value = TextFieldValue(mdToAnnotated(m.groupValues[2].trim(), boldShadow)),
                        done = m.groupValues[1] != " "
                    )
                )
            }
            MD_BULLET_RE.containsMatchIn(line) -> out.add(
                NoteBlock(id, BlockKind.BULLET, TextFieldValue(mdToAnnotated(MD_BULLET_RE.find(line)!!.groupValues[1], boldShadow)))
            )
            MD_ORDERED_RE.containsMatchIn(line) -> out.add(
                NoteBlock(id, BlockKind.ORDERED, TextFieldValue(mdToAnnotated(MD_ORDERED_RE.find(line)!!.groupValues[1], boldShadow)))
            )
            else -> out.add(NoteBlock(id, BlockKind.TEXT, TextFieldValue(mdToAnnotated(line, boldShadow))))
        }
    }
    if (out.isEmpty()) out.add(NoteBlock(id = 1L))
    return out
}

/**
 * vc66（输入时样式保留）：BasicTextField 走纯文本编辑内核，用户每次敲键返回的
 * TextFieldValue 都会把 AnnotatedString 的样式全部丢掉 —— 表现是「打好粗体再打个字，
 * 粗体就没了，保存自然也没有」。这里用「公共前缀 / 公共后缀」把旧样式区间映射到新文本：
 * 改动点之前的区间原样保留，之后的整体平移，正好落在改动里的区间丢弃。
 */
private fun preserveSpans(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val oldStr = old.annotatedString
    if (oldStr.spanStyles.isEmpty() && oldStr.paragraphStyles.isEmpty()) return new
    val oldText = oldStr.text
    val newText = new.annotatedString.text
    if (newText == oldText) return new
    var prefix = 0
    val minLen = minOf(oldText.length, newText.length)
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (
        suffix < minLen - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    val delta = newText.length - oldText.length
    fun remap(pos: Int): Int = when {
        pos <= prefix -> pos
        pos >= oldText.length - suffix -> pos + delta
        else -> prefix
    }
    val built = buildAnnotatedString {
        append(newText)
        oldStr.spanStyles.forEach { r ->
            val a = remap(r.start)
            val b = remap(r.end)
            if (b > a) addStyle(r.item, a, b)
        }
        oldStr.paragraphStyles.forEach { r ->
            val a = remap(r.start)
            val b = remap(r.end)
            if (b > a) addStyle(r.item, a, b)
        }
    }
    return new.copy(annotatedString = built)
}

/** 块 → 纯文本（搜索 / 摘要用；任务块以「[完成] / [待办]」区分，与跨端一致） */
private fun blockPlain(b: NoteBlock): String {
    val t = b.value.text
    return when (b.kind) {
        BlockKind.TASK -> (if (b.done) "[完成] " else "[待办] ") + t
        else -> t
    }
}

/* ============================ 编辑页 ============================ */

@Composable
fun NoteEditorRoute(
    id: String,
    moreActions: MoreMenuActions,
    onBack: () -> Unit,
    /** v1.0.6.9：从某个分类下新建笔记时带上分类 id，保存后自动归入该分类 */
    folderId: String? = null
) {
    val vm = hiltViewModel<NotesViewModel>()
    NoteEditorScreen(vm = vm, id = id, moreActions = moreActions, onBack = onBack, folderId = folderId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    vm: NotesViewModel,
    id: String,
    moreActions: MoreMenuActions,
    onBack: () -> Unit,
    /** v1.0.6.9：目标分类 id（空 = 未分类）；保存成功后自动归属 */
    folderId: String? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    /* 正文 = 块列表（唯一真相源）
       v2.0.0（新建笔记闪退根治）：**初始就带一个空块**，绝不从空列表起步。
       根因：首帧组合里 blocks 还是空的（LaunchedEffect 要下一帧才填），此时凡出现
       `focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))` 都会变成 coerceIn(0, -1)
       —— Kotlin 对空区间直接抛 IllegalArgumentException（Cannot coerce value to an
       empty range），于是「新建笔记」一点就崩；编辑已有笔记同理，只是时序不同罢了。 */
    val blocks = remember { mutableStateListOf(NoteBlock(id = 1L)) }
    var title by remember { mutableStateOf("") }
    // vc66（分类与标签合并）：取消单独的标签输入 —— 改为直接选择已有分类（单选），
    // 或在弹出的选择对话框里就地新建分类。旧标签照常透传保存，老数据不丢。
    var tags by remember { mutableStateOf(listOf<String>()) }
    var selFolderId by remember { mutableStateOf(folderId) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var bodyScale by remember { mutableFloatStateOf(1f) }
    /** 当前聚焦块下标（工具栏所有动作都作用于它） */
    var focusedIndex by remember { mutableIntStateOf(0) }
    /** 待聚焦（块 id + 光标位）：结构变化后由副作用统一请求焦点，避免组合期操作焦点 */
    var pendingFocus by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    /** 块 id → FocusRequester（非 State，只在副作用里读，避免组合期写状态） */
    val requesters = remember { HashMap<Long, FocusRequester>() }
    var historyJob by remember { mutableStateOf<Job?>(null) }

    /* ---------- 撤销 / 重做：整份块列表快照（块是不可变数据，浅拷贝即安全） ---------- */
    val history = remember { SnapshotStateList<List<NoteBlock>>() }
    var histIndex by remember { mutableIntStateOf(0) }

    fun pushHistory() {
        val snap = blocks.toList()
        if (history.isNotEmpty() && history[histIndex] == snap) return
        while (history.size > histIndex + 1) history.removeAt(history.lastIndex)
        history.add(snap)
        if (history.size > 120) history.removeAt(0)
        histIndex = history.lastIndex
    }

    fun undo() {
        if (histIndex > 0) {
            histIndex--
            blocks.clear()
            blocks.addAll(history[histIndex])
        }
    }

    fun redo() {
        if (histIndex < history.lastIndex) {
            histIndex++
            blocks.clear()
            blocks.addAll(history[histIndex])
        }
    }

    fun focusBlock(index: Int, cursor: Int = 0) {
        val b = blocks.getOrNull(index) ?: return
        requesters[b.id]?.requestFocus()
        blocks[index] = b.copy(value = b.value.copy(selection = TextRange(cursor.coerceIn(0, b.value.text.length))))
    }

    /* ---------- 结构操作（全部不动文字内容，只动块） ---------- */

    /** 回车：在光标处把当前块切成两块；任务块继续开任务，空任务回车退出清单 */
    fun splitBlock(index: Int, caret: Int) {
        val b = blocks.getOrNull(index) ?: return
        val text = b.value.text
        val left = text.substring(0, caret.coerceIn(0, text.length))
        val right = text.substring(caret.coerceIn(0, text.length))
        if (b.kind == BlockKind.TASK && left.isBlank() && right.isBlank()) {
            // 空任务 + 回车 = 退出清单，回到正文
            blocks[index] = b.copy(kind = BlockKind.TEXT, value = TextFieldValue(""))
            return
        }
        val nextKind = when (b.kind) {
            BlockKind.TASK -> BlockKind.TASK
            BlockKind.BULLET -> BlockKind.BULLET
            BlockKind.ORDERED -> BlockKind.ORDERED
            else -> BlockKind.TEXT
        }
        blocks[index] = b.copy(value = b.value.copy(text = left, selection = TextRange(left.length)))
        val nid = System.nanoTime()
        blocks.add(index + 1, NoteBlock(id = nid, kind = nextKind, value = TextFieldValue(right)))
        pendingFocus = nid to 0
        pushHistory()
    }

    /** 退格（块已空）：并入上一块；首块则退化为正文块 */
    fun mergeBack(index: Int) {
        val b = blocks.getOrNull(index) ?: return
        if (index == 0) {
            if (b.kind != BlockKind.TEXT) blocks[index] = b.copy(kind = BlockKind.TEXT)
            return
        }
        val prev = blocks[index - 1]
        val at = prev.value.text.length
        blocks[index - 1] = prev.copy(
            value = prev.value.copy(
                text = prev.value.text + b.value.text,
                selection = TextRange(at)
            )
        )
        blocks.removeAt(index)
        pendingFocus = prev.id to at
        pushHistory()
    }

    /** 工具栏「待办」：当前块 ↔ 任务块互转（绝不是插入字符标记） */
    fun toggleTaskBlock() {
        val i = focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
        val b = blocks[i]
        blocks[i] = if (b.kind == BlockKind.TASK) b.copy(kind = BlockKind.TEXT) else b.copy(kind = BlockKind.TASK)
        pendingFocus = b.id to b.value.selection.min
        pushHistory()
    }

    fun setKind(kind: BlockKind) {
        val i = focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
        val b = blocks[i]
        blocks[i] = b.copy(kind = if (b.kind == kind) BlockKind.TEXT else kind)
        pendingFocus = b.id to b.value.selection.min
        pushHistory()
    }

    /* ---------- 行内样式：作用于当前块选区（选区为空则整块生效） ---------- */
    val boldBase = SpanStyle(
        fontWeight = FontWeight.Bold,
        // 伪粗体：国产中文字体族普遍没有 Bold 字重，Compose 也不做合成加粗，
        // 用同色投影重描一遍让「加粗」肉眼可见（不能用 Stroke —— 那会只画轮廓变成空心字）。
        shadow = Shadow(
            color = LocalContentColor.current,
            offset = Offset(x = 0.9f, y = 0.9f),
            blurRadius = 0.6f
        )
    )

    fun toggleSpan(style: SpanStyle, detect: (SpanStyle) -> Boolean) {
        val i = focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
        val b = blocks[i]
        val sel = b.value.selection
        val from: Int
        val to: Int
        if (sel.collapsed) {
            from = 0
            to = b.value.text.length
        } else {
            from = sel.min
            to = sel.max
        }
        if (to <= from) return
        val src = b.value.annotatedString
        val has = src.spanStyles.any { r ->
            detect(r.item) && r.start <= from && r.end >= to
        }
        /* 增删样式一律**重建整串**，不用 Builder.removeStyle：
           ① 该 API 在各 Compose 版本间存在与否不一致（编译风险）；
           ② 重建能精确处理「样式区间与选区部分重叠」的情况 —— 只切掉重叠段，
              两侧剩余部分原样保留，绝不整段连坐。 */
        val rebuilt = if (has) {
            buildAnnotatedString {
                append(src.text)
                src.spanStyles.forEach { r ->
                    if (!detect(r.item)) {
                        addStyle(r.item, r.start, r.end)
                    } else {
                        if (r.start < from) addStyle(r.item, r.start, from)
                        if (r.end > to) addStyle(r.item, to, r.end)
                    }
                }
                src.paragraphStyles.forEach { r -> addStyle(r.item, r.start, r.end) }
            }
        } else {
            buildAnnotatedString {
                append(src)
                addStyle(style, from, to)
            }
        }
        val nv = b.value.copy(annotatedString = rebuilt)
        blocks[i] = b.copy(value = nv)
        pendingFocus = b.id to sel.max
        pushHistory()
    }

    fun currentBlock(): NoteBlock? = blocks.getOrNull(focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0)))

    fun activeStyle(detect: (SpanStyle) -> Boolean): Boolean {
        val b = currentBlock() ?: return false
        val sel = b.value.selection
        val from = if (sel.collapsed) 0 else sel.min
        val to = if (sel.collapsed) b.value.text.length else sel.max
        if (to <= from) return false
        return b.value.annotatedString.spanStyles.any { r -> detect(r.item) && r.start <= from && r.end >= to }
    }

    val boldActive = activeStyle { it.fontWeight == FontWeight.Bold || it.shadow != null }
    val italicActive = activeStyle { it.fontStyle == FontStyle.Italic }
    val underlineActive = activeStyle { it.textDecoration == TextDecoration.Underline }
    val curKind = currentBlock()?.kind
    val headingActive = curKind == BlockKind.H1 || curKind == BlockKind.H2

    /** 在当前块光标处插入纯文本（图片 / 附件） */
    fun insertText(txt: String) {
        val i = focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
        val b = blocks[i]
        val at = b.value.selection.min.coerceIn(0, b.value.text.length)
        val newLen = b.value.text.length + txt.length
        // 分三段重建：插入点之前的样式原样保留，之后的样式随文字整体后移，绝不串位
        val built = buildAnnotatedString {
            append(b.value.annotatedString.subSequence(0, at))
            append(txt)
            append(b.value.annotatedString.subSequence(at, b.value.text.length))
        }
        blocks[i] = b.copy(
            value = b.value.copy(
                annotatedString = built,
                selection = TextRange((at + txt.length).coerceIn(0, newLen))
            )
        )
        pushHistory()
    }

    /* ---------- 图片 / 附件：复制到私有目录，正文里以 Markdown 链接文本呈现 ---------- */
    fun importIntoNoteMedia(uri: Uri, fallbackName: String, onDone: (String) -> Unit) {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    var fileName = fallbackName
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { fileName = it } }
                    val safe = fileName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { fallbackName }
                    val dir = File(context.filesDir, "note_media").apply { mkdirs() }
                    val out = File(dir, "${System.currentTimeMillis()}_$safe")
                    resolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching null
                    out.absolutePath
                }.getOrNull()
            }
            if (path == null) {
                Toast.makeText(context, "导入失败，请重试", Toast.LENGTH_SHORT).show()
            } else {
                onDone(path)
            }
        }
    }

    val pickImage = rememberImagePicker(onPicked = { uri ->
        importIntoNoteMedia(uri, "image.jpg") { path -> insertText("![图片](file://$path)") }
    })
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importIntoNoteMedia(uri, "file") { path ->
                insertText("[${path.substringAfterLast('_')}]($path)")
            }
        }
    }

    // vc66：加载时把伪粗体投影按当前正文颜色补齐（见 mdToAnnotated 注释）
    val boldShadowColor = LocalContentColor.current
    LaunchedEffect(id) {
        if (id.isNotBlank()) {
            vm.getNote(id)?.let { m ->
                val md = m.markdown?.takeIf { it.isNotBlank() } ?: m.text
                val (t, rest) = splitTitle(md)
                title = t
                blocks.clear()
                blocks.addAll(
                    markdownToBlocks(
                        rest,
                        Shadow(color = boldShadowColor, offset = Offset(x = 0.9f, y = 0.9f), blurRadius = 0.6f)
                    )
                )
                tags = NotesViewModel.tagsOf(m)
            }
        } else {
            blocks.clear()
            blocks.add(NoteBlock(id = 1L))
        }
        // 兜底：任何路径下块列表都至少有一块（空表会让所有 coerceIn 落进空区间）
        if (blocks.isEmpty()) blocks.add(NoteBlock(id = 1L))
        history.clear()
        history.add(blocks.toList())
        histIndex = 0
        loaded = true
    }

    // 结构变化后的焦点请求（组合期绝不允许直接操作焦点，统一放这里）
    LaunchedEffect(pendingFocus) {
        val pf = pendingFocus ?: return@LaunchedEffect
        val idx = blocks.indexOfFirst { it.id == pf.first }
        if (idx >= 0) {
            focusBlock(idx, pf.second)
        }
        pendingFocus = null
    }

    fun save() {
        val t = title.trim()
        val hasContent = t.isNotBlank() || blocks.any { it.value.text.isNotBlank() }
        if (!hasContent) {
            onBack()
            return
        }
        val bodyMd = blocksToMarkdown(blocks)
        val markdown = if (t.isBlank()) bodyMd else "# $t\n\n$bodyMd"
        val lines = ArrayList<String>()
        if (t.isNotBlank()) lines.add(t)
        blocks.forEach { if (it.value.text.isNotBlank()) lines.add(blockPlain(it)) }
        val plain = lines.joinToString("\n")
        scope.launch {
            // v2.0.0：保存整链路兜底 —— 任何意外都只提示，绝不把用户困在崩掉的编辑页里
            runCatching {
                val savedId = vm.saveNote(id.ifBlank { null }, plain.ifBlank { t }, tags, markdown)
                // vc66（分类与标签合并）：归属编辑页内选定的分类（含未分类 = null）
                if (savedId.isNotBlank()) vm.assignFolder(savedId, selFolderId?.takeIf { it.isNotBlank() })
            }.onFailure { e ->
                android.util.Log.e("NoteEditor", "save failed", e)
                android.widget.Toast.makeText(context, "保存失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_SHORT).show()
            }
            onBack()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(if (id.isBlank()) "新建笔记" else "编辑笔记") },
                    navigationIcon = {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回并保存")
                        }
                    },
                    actions = {
                        TextButton(onClick = { save() }, enabled = loaded) { Text("保存") }
                        if (id.isNotBlank()) {
                            IconButton(onClick = { showDelete = true }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
                EditorFormatBar(
                    modifier = Modifier,
                    canUndo = histIndex > 0,
                    canRedo = histIndex >= 0 && histIndex < history.lastIndex,
                    boldActive = boldActive,
                    italicActive = italicActive,
                    underlineActive = underlineActive,
                    headingActive = headingActive,
                    bulletActive = curKind == BlockKind.BULLET,
                    orderedActive = curKind == BlockKind.ORDERED,
                    todoActive = curKind == BlockKind.TASK,
                    onUndo = { undo() },
                    onRedo = { redo() },
                    onBold = { toggleSpan(boldBase) { it.fontWeight == FontWeight.Bold || it.shadow != null } },
                    onItalic = { toggleSpan(MD_ITALIC) { it.fontStyle == FontStyle.Italic } },
                    onUnderline = { toggleSpan(MD_UNDER) { it.textDecoration == TextDecoration.Underline } },
                    onHeading = {
                        val i = focusedIndex.coerceIn(0, blocks.lastIndex.coerceAtLeast(0))
                        val b = blocks[i]
                        blocks[i] = b.copy(kind = if (b.kind == BlockKind.H1) BlockKind.TEXT else BlockKind.H1)
                        pendingFocus = b.id to b.value.selection.min
                        pushHistory()
                    },
                    // vc64：待办 = 当前块转成「任务块」（行首真实 Checkbox），再点转回正文
                    onTodo = { toggleTaskBlock() },
                    onBullet = { setKind(BlockKind.BULLET) },
                    onOrdered = { setKind(BlockKind.ORDERED) },
                    onImage = { pickImage() },
                    onFile = { pickFile.launch("*/*") },
                    onFontSize = {
                        bodyScale = when (bodyScale) {
                            1f -> 1.15f
                            1.15f -> 1.3f
                            else -> 1f
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassTextField(
                value = title,
                onValueChange = { title = it.replace("\n", "") },
                placeholder = "标题",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth()
            )

            // vc66（分类与标签合并）：取消单独的标签输入 —— 这里只做「选择 / 新建分类」。
            // 已有标签仍随笔记透传保存，但不再提供编辑入口。
            val foldersList by vm.folders.collectAsStateWithLifecycle(initialValue = emptyList())
            val folderNames = remember(foldersList) { foldersList.associate { it.id to it.name } }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AssistChip(
                    onClick = { showFolderPicker = true },
                    label = {
                        Text(
                            folderNames[selFolderId] ?: "未分类",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(UiDimens.CardRadius)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 有序列表编号：只在连续的 ORDERED 段内累加
                    var ord = 0
                    blocks.forEachIndexed { index, block ->
                        if (block.kind != BlockKind.ORDERED) ord = 0 else ord++
                        val fr = remember(block.id) { FocusRequester() }
                        DisposableEffect(block.id) {
                            requesters[block.id] = fr
                            onDispose { requesters.remove(block.id) }
                        }
                        BlockRow(
                            block = block,
                            orderNumber = ord,
                            focusRequester = fr,
                            bodyScale = bodyScale,
                            isOnlyEmptyBlock = blocks.size == 1 && block.value.text.isEmpty(),
                            onFocus = { focusedIndex = index },
                            onValueChange = { nv ->
                                val cur = blocks.getOrNull(index) ?: return@BlockRow
                                // vc66：先保住旧样式再落库状态 —— BasicTextField 每次按键都会
                                // 丢掉 AnnotatedString 的样式，这里用区间映射原样接回来
                                val safe = preserveSpans(cur.value, nv)
                                if (safe.text.contains('\n')) {
                                    // 回车：块内不存换行，按光标位置切块
                                    val caret = safe.text.indexOf('\n')
                                    val clean = safe.text.replace("\n", "")
                                    if (clean != cur.value.text) {
                                        blocks[index] = cur.copy(value = safe.copy(text = clean))
                                    }
                                    splitBlock(index, caret)
                                    return@BlockRow
                                }
                                if (safe.text.isEmpty() && cur.value.text.isEmpty() && safe.selection.collapsed && safe.selection.min == 0) {
                                    // 空块退格：并入上一块（首块则退回正文块）
                                    mergeBack(index)
                                    return@BlockRow
                                }
                                blocks[index] = cur.copy(value = safe)
                                historyJob?.cancel()
                                historyJob = scope.launch {
                                    delay(400)
                                    pushHistory()
                                }
                            },
                            onToggleDone = {
                                val cur = blocks.getOrNull(index) ?: return@BlockRow
                                // 只翻布尔，一个字符都不动 —— 吞字与光标跳动在此彻底不可能发生
                                blocks[index] = cur.copy(done = !cur.done)
                                pushHistory()
                            }
                        )
                    }
                    // 末尾留一段可点空白：点它 = 聚焦最后一块继续写（Compose 原生点击，无自定义手势）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                focusBlock(blocks.lastIndex, blocks.lastOrNull()?.value?.text?.length ?: 0)
                            }
                    )
                }
            }
        }
    }

    if (showDelete) {
        AnimatedAlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条笔记？") },
            text = { Text("删除后可在回收站保留 30 天。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        vm.deleteNote(id)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    // —— vc66（分类与标签合并）：分类选择弹窗 —— 单选已有分类、可就地新建分类 ——
    if (showFolderPicker) {
        val foldersList by vm.folders.collectAsStateWithLifecycle(initialValue = emptyList())
        var newName by remember { mutableStateOf("") }
        GlassDialog(onDismissRequest = { showFolderPicker = false }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("选择分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selFolderId = null; showFolderPicker = false }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RadioButton(
                        selected = selFolderId == null,
                        onClick = { selFolderId = null; showFolderPicker = false }
                    )
                    Text("未分类", style = MaterialTheme.typography.bodyLarge)
                }
                foldersList.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selFolderId = f.id; showFolderPicker = false }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selFolderId == f.id,
                            onClick = { selFolderId = f.id; showFolderPicker = false }
                        )
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassTextField(
                        value = newName,
                        onValueChange = { newName = it.take(20) },
                        placeholder = "新建分类名称",
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val newId = vm.createFolder(newName)
                            if (newId.isNotBlank()) {
                                selFolderId = newId
                                showFolderPicker = false
                            }
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("创建", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** 把首行 `# 标题` 拆出来；没有标题行时 title 为空、正文原样。 */
private fun splitTitle(md: String): Pair<String, String> {
    val firstLine = md.lineSequence().firstOrNull().orEmpty()
    if (!firstLine.trimStart().startsWith("#")) return "" to md
    val t = firstLine.trim().trimStart('#').trim()
    val rest = md.substring(firstLine.length).trimStart('\n')
    return t to rest
}

/* ============================ 单块渲染 ============================ */

@Composable
private fun BlockRow(
    block: NoteBlock,
    orderNumber: Int,
    focusRequester: FocusRequester,
    bodyScale: Float,
    isOnlyEmptyBlock: Boolean,
    onFocus: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleDone: () -> Unit
) {
    val baseSize = when (block.kind) {
        BlockKind.H1 -> 22
        BlockKind.H2 -> 19
        else -> 16
    }
    val weight = if (block.kind == BlockKind.H1 || block.kind == BlockKind.H2) FontWeight.Bold else FontWeight.Normal
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = (baseSize * bodyScale).sp,
        lineHeight = (baseSize * 1.55f * bodyScale).sp,
        fontWeight = weight,
        color = if (block.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        // 勾选即加删除线（由样式表达，不是改文字）
        textDecoration = if (block.done) TextDecoration.LineThrough else null
    )
    val field: @Composable (Modifier) -> Unit = { mod ->
        BasicTextField(
            value = block.value,
            onValueChange = onValueChange,
            modifier = mod
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() },
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (block.value.text.isEmpty() && isOnlyEmptyBlock) {
                        Text(
                            "写点什么…工具栏「待办」可开任务清单",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            }
        )
    }

    when (block.kind) {
        BlockKind.TASK -> {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 真实复选框：点击即打勾，文字一个字符都不动
                Checkbox(
                    checked = block.done,
                    onCheckedChange = { onToggleDone() },
                    modifier = Modifier.size(26.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = AmberPrimary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkmarkColor = Color.White
                    )
                )
                Spacer(Modifier.width(4.dp))
                field(Modifier.weight(1f))
            }
        }
        BlockKind.BULLET -> {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "•",
                    style = textStyle,
                    modifier = Modifier.padding(end = 6.dp)
                )
                field(Modifier.weight(1f))
            }
        }
        BlockKind.ORDERED -> {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "$orderNumber.",
                    style = textStyle,
                    modifier = Modifier.padding(end = 6.dp).widthIn(min = 22.dp)
                )
                field(Modifier.weight(1f))
            }
        }
        else -> field(Modifier.fillMaxWidth())
    }
}

/**
 * 编辑工具栏（对标主流 Markdown 编辑器）：撤销 / 重做 | B I U H | ☑ 待办 • 列表 1. 有序 | 图片 附件 | A±。
 * 单行横向滚动，恒定可见；按钮直接作用于**当前块**。
 */
@Composable
private fun EditorFormatBar(
    modifier: Modifier = Modifier,
    canUndo: Boolean,
    canRedo: Boolean,
    boldActive: Boolean,
    italicActive: Boolean,
    underlineActive: Boolean,
    headingActive: Boolean,
    bulletActive: Boolean,
    orderedActive: Boolean,
    todoActive: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onHeading: () -> Unit,
    onTodo: () -> Unit,
    onBullet: () -> Unit,
    onOrdered: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onFontSize: () -> Unit
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        showBorder = true
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolIcon(Icons.Rounded.Undo, "撤销", canUndo, onClick = onUndo)
            ToolIcon(Icons.Rounded.Redo, "重做", canRedo, onClick = onRedo)
            ToolDivider()
            ToolIcon(Icons.Rounded.FormatBold, "加粗", true, boldActive, onBold)
            ToolIcon(Icons.Rounded.FormatItalic, "斜体", true, italicActive, onItalic)
            ToolIcon(Icons.Rounded.FormatUnderlined, "下划线", true, underlineActive, onUnderline)
            ToolIcon(Icons.Rounded.Title, "标题", true, headingActive, onHeading)
            ToolDivider()
            ToolIcon(Icons.Rounded.CheckBox, "任务清单（当前行变待办）", true, todoActive, onTodo)
            ToolIcon(Icons.Rounded.FormatListBulleted, "无序列表", true, bulletActive, onBullet)
            ToolIcon(Icons.Rounded.FormatListNumbered, "有序列表", true, orderedActive, onOrdered)
            ToolDivider()
            ToolIcon(Icons.Rounded.Image, "插入图片", true, onClick = onImage)
            ToolIcon(Icons.Rounded.AttachFile, "插入附件", true, onClick = onFile)
            ToolDivider()
            ToolText("A±", onFontSize)
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    desc: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(
            icon,
            contentDescription = desc,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                active -> AmberPrimary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun ToolText(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(38.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}
