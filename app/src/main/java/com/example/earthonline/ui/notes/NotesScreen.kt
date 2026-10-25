package com.example.earthonline.ui.notes

import com.example.earthonline.ui.components.AnimatedAlertDialog
import com.example.earthonline.ui.components.bouncyPress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.earthonline.data.local.entity.MemoEntity
import com.example.earthonline.ui.components.EmptyHint
import com.example.earthonline.ui.components.GlassDialog
import com.example.earthonline.ui.components.GlassTopBar
import com.example.earthonline.ui.components.GlassTextField
import com.example.earthonline.ui.components.MoreMenuActions
import com.example.earthonline.ui.components.ScrollDirection
import com.example.earthonline.ui.components.rememberScrollDirection
import com.example.earthonline.ui.components.UiDimens
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.earthonline.ui.theme.AppCard
import com.example.earthonline.ui.theme.AmberPrimary
import com.example.earthonline.ui.theme.OnAmber
import kotlinx.coroutines.launch

/**
 * 笔记主页（v1.0.6.9 重构：分类（文件夹）层级）。
 *
 * 形态：**先创建分类（文件夹），再在分类下创建笔记** —— 彻底取消平铺一锅粥。
 *  - 顶部常驻「新建笔记」入口（落入「未分类」）与「新建分类」入口；
 *  - 每个分类是一张可展开/收起的卡片：头部 = 图标 + 名称 + 篇数 + 管理（重命名/删除），
 *    展开 = 该分类下的笔记流 + 「＋ 新建笔记」（新笔记直接归入此分类）；
 *  - 没有归属的笔记收在「未分类」段，同样可展开、可新建；
 *  - 笔记卡片的「归类」按钮可把笔记移动到任意分类（或移出到未分类）；
 *  - 搜索 / 标签筛选激活时临时展示平铺结果（筛选态不分组，避免空分类干扰）。
 *
 * 存储：分类与归属走 DataStore JSON（见 [NotesViewModel]），笔记本体仍落 Room memos 表 ——
 * 底层数据与跨端同步契约零改动，纯粹是 UI / 交互层重构。
 */
@Composable
fun NotesRoute(
    moreActions: MoreMenuActions,
    onEdit: (id: String?, folderId: String?) -> Unit
) {
    val vm = hiltViewModel<NotesViewModel>()
    NotesScreen(vm = vm, moreActions = moreActions, onEdit = onEdit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    vm: NotesViewModel,
    moreActions: MoreMenuActions,
    onEdit: (id: String?, folderId: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val notes by vm.notes.collectAsStateWithLifecycle(initialValue = emptyList())
    val query by vm.query.collectAsStateWithLifecycle(initialValue = "")
    val allTags by vm.allTags.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeTag by vm.tag.collectAsStateWithLifecycle(initialValue = NotesViewModel.ALL_TAGS)
    val folders by vm.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val folderMap by vm.folderMap.collectAsStateWithLifecycle(initialValue = emptyMap())

    var toDelete by remember { mutableStateOf<String?>(null) }
    // v1.0.6.11：顶栏「分类管理」入口保留（重命名 / 删除）
    var showFolderManager by remember { mutableStateOf(false) }
    // vc68：FAB 的新建入口改为**居中固定大小 Dialog**（与背包页 ItemDialog 同风格），
    // 且不再询问"新建分类还是新建笔记"—— 一律直接走新建笔记流程。
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var manageFolder by remember { mutableStateOf<NoteFolder?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<NoteFolder?>(null) }
    var moveTarget by remember { mutableStateOf<MemoEntity?>(null) }
    // 展开态：新分类自动展开，用户可随时收起
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(folders) {
        expanded = expanded + folders.map { it.id }
    }
    val folderIds = remember(folders) { folders.map { it.id }.toSet() }
    val filtering = query.isNotBlank() || activeTag != NotesViewModel.ALL_TAGS
    val unfiled = remember(notes, folderMap, folderIds) {
        notes.filter { val fid = folderMap[it.id]; fid.isNullOrBlank() || fid !in folderIds }
    }

    // vc67：listState 提到 Scaffold 之外 —— 右下角 FAB 的滚动收缩要读它（见下）。
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // vc68：全局统一液态玻璃顶栏（标题 + 玻璃底板），与主页材质同源
            GlassTopBar(
                title = { Text("笔记") },
                actions = {
                    // v1.0.6.11：分类管理入口（重命名 / 删除）
                    // vc68：新建分类与分类管理统一收进这个右上角图标
                    IconButton(onClick = { showFolderManager = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "分类管理")
                    }
                }
            )
        },
        // vc68：补回右下角「+」悬浮按钮。
        // **逻辑理顺**：点击后**直接进入新建笔记流程**（居中固定大小 Dialog，与背包页
        // ItemDialog 同风格），不再像 vc67 那样先弹一张「新建笔记 / 新建分类」二选一
        // 抽屉 —— 多一次点击、且把用户的真实意图（写笔记）藏了起来。
        // 滚动方向用 derivedStateOf 派生，且**只读进 graphicsLayer**：
        // 滑动时只改图层属性，零重组 —— 满血液态玻璃下也不掉帧。
        floatingActionButton = {
            val scrollDir = rememberScrollDirection(listState)
            FloatingActionButton(
                onClick = { showNewNoteDialog = true },
                containerColor = AmberPrimary,
                contentColor = OnAmber,
                shape = CircleShape,
                modifier = Modifier
                    .bouncyPress()
                    .graphicsLayer {
                        val s = if (scrollDir.value == ScrollDirection.Down) 0.86f else 1f
                        scaleX = s
                        scaleY = s
                    }
            ) { Icon(Icons.Rounded.Add, contentDescription = "新建笔记") }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // vc62（UI 统一批）：顶部常驻搜索框 —— 与背包页同一套玻璃输入条风格，
            // 不再「点放大镜才出现」；取消搜索时点输入框末端的清空键即可。
            GlassTextField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = "搜正文或标签",
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "搜索") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 标签筛选：全部 + 已用过的标签（按使用次数排序）
            if (allTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = activeTag == NotesViewModel.ALL_TAGS,
                        onClick = { vm.setTag(NotesViewModel.ALL_TAGS) },
                        label = { Text("全部") }
                    )
                    allTags.forEach { t ->
                        FilterChip(
                            selected = activeTag == t,
                            onClick = { vm.setTag(if (activeTag == t) NotesViewModel.ALL_TAGS else t) },
                            label = { Text("#$t") }
                        )
                    }
                }
            }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // vc62（UI 统一批）：取消顶部「新建笔记」「新建分类」两大块常驻卡片 ——
            // 新建入口统一收进右下角悬浮「+」选项面板，列表直接以分类/笔记开头。

            if (filtering) {
                // 筛选态：平铺结果，不分组（避免空分类干扰筛选）
                item(key = "filter_hint") {
                    Text(
                        "「${query.ifBlank { "#$activeTag" }}」的筛选结果（${notes.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                    )
                }
                if (notes.isEmpty()) {
                    item(key = "filter_empty") {
                        EmptyHint("没有匹配的笔记", icon = Icons.Rounded.Search)
                    }
                } else {
                    items(notes, key = { it.id }) { m ->
                        NoteCard(
                            text = m.text,
                            markdown = m.markdown ?: m.text,
                            tags = NotesViewModel.tagsOf(m),
                            time = formatNoteTime(m.createdAt),
                            onClick = { onEdit(m.id, null) },
                            onDelete = { toDelete = m.id },
                            onMove = { moveTarget = m }
                        )
                    }
                }
            } else {
                if (notes.isEmpty() && folders.isEmpty()) {
                    item(key = "empty_all") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.EditNote,
                                contentDescription = null,
                                tint = AmberPrimary,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                "先建一个分类吧",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "把笔记分门别类：工作、生活、灵感……\n建好分类后，在分类里写笔记。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // —— 分类卡片（可展开/收起） ——
                folders.forEach { f ->
                    val fNotes = notes.filter { folderMap[it.id] == f.id }
                    val open = f.id in expanded
                    item(key = "folder-${f.id}") {
                        FolderHeaderCard(
                            folder = f,
                            count = fNotes.size,
                            expanded = open,
                            onToggle = {
                                expanded = if (open) expanded - f.id else expanded + f.id
                            },
                            // vc66：分类右侧独立「+」—— 直接在该分类下新建笔记
                            onAddNote = { onEdit(null, f.id) }
                        )
                    }
                    if (open) {
                        if (fNotes.isEmpty()) {
                            item(key = "folder-empty-${f.id}") {
                                EmptyHint("这个分类还是空的，写第一篇吧", icon = Icons.Rounded.EditNote)
                            }
                        } else {
                            items(fNotes, key = { it.id }) { m ->
                                NoteCard(
                                    text = m.text,
                                    markdown = m.markdown ?: m.text,
                                    tags = NotesViewModel.tagsOf(m),
                                    time = formatNoteTime(m.createdAt),
                                    onClick = { onEdit(m.id, null) },
                                    onDelete = { toDelete = m.id },
                                    onMove = { moveTarget = m }
                                )
                            }
                        }
                        item(key = "folder-new-${f.id}") {
                            NewNoteInFolderRow(folderName = f.name) { onEdit(null, f.id) }
                        }
                    }
                }

                // —— 未分类段（有笔记才显示；一篇都没有时不占位） ——
                if (unfiled.isNotEmpty()) {
                    val open = " unfiled" in expanded
                    item(key = "unfiled") {
                        FolderHeaderCard(
                            folder = null,
                            name = "未分类",
                            count = unfiled.size,
                            expanded = open,
                            onToggle = {
                                expanded = if (open) expanded - " unfiled" else expanded + " unfiled"
                            },
                            onAddNote = { onEdit(null, null) }
                        )
                    }
                    if (open) {
                        items(unfiled, key = { it.id }) { m ->
                            NoteCard(
                                text = m.text,
                                markdown = m.markdown ?: m.text,
                                tags = NotesViewModel.tagsOf(m),
                                time = formatNoteTime(m.createdAt),
                                onClick = { onEdit(m.id, null) },
                                onDelete = { toDelete = m.id },
                                onMove = { moveTarget = m }
                            )
                        }
                        item(key = "unfiled-new") {
                            NewNoteInFolderRow(folderName = "未分类") { onEdit(null, null) }
                        }
                    }
                }
            }
        }
        }
    }

    } // vc66：外层 Box（原新建面板覆盖层已下线）闭合

    // —— vc68：新建笔记（**居中固定大小 Dialog**，与背包页 ItemDialog 同风格）——
    // 逻辑理顺：FAB 点开就是「写笔记」这件事本身（标题 + 分类），
    // 不再像 vc67 那样先弹一张「新建笔记 / 新建分类」二选一抽屉 ——
    // 那张抽屉把用户的真实意图藏了起来，还多一次点击。
    // 「新建分类 / 管理分类」统一收进右上角图标按钮（见 showFolderManager 弹窗）。
    if (showNewNoteDialog) {
        var draftTitle by remember { mutableStateOf("") }
        var draftFolderId by remember { mutableStateOf<String?>(null) }
        GlassDialog(onDismissRequest = { showNewNoteDialog = false }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("新建笔记", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                GlassTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it.replace("\n", "").take(60) },
                    label = "标题（可留空）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "归入分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 分类单选行：未分类 + 全部已有分类（横向滚动，单行不换行）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = draftFolderId == null,
                        onClick = { draftFolderId = null },
                        label = { Text("未分类", maxLines = 1) }
                    )
                    folders.forEach { f ->
                        FilterChip(
                            selected = draftFolderId == f.id,
                            onClick = { draftFolderId = f.id },
                            label = { Text(f.name, maxLines = 1) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showNewNoteDialog = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("取消", maxLines = 1) }
                    Button(
                        onClick = {
                            val t = draftTitle.trim()
                            scope.launch {
                                if (t.isBlank()) {
                                    // 没起标题 → 直接开一个空白新笔记去写
                                    showNewNoteDialog = false
                                    onEdit(null, draftFolderId)
                                } else {
                                    // 起了标题 → 先落一条「只有标题」的笔记，
                                    // 立刻带着它进编辑器继续写正文（标题已就位，不必再输一遍）
                                    val id = vm.saveNote(null, t, emptyList(), "# $t\n\n")
                                    if (id.isNotBlank()) vm.assignFolder(id, draftFolderId)
                                    showNewNoteDialog = false
                                    onEdit(id.ifBlank { null }, draftFolderId)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberPrimary,
                            contentColor = OnAmber
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("开始写", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }

    toDelete?.let { id ->
        AnimatedAlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除这条笔记？") },
            text = { Text("删除后可在回收站保留 30 天，之后彻底清除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.deleteNote(id) }
                    toDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } }
        )
    }

    // —— 分类管理（重命名 / 删除） ——
    manageFolder?.let { f ->
        var name by remember(f.id) { mutableStateOf(f.name) }
        GlassDialog(onDismissRequest = { manageFolder = null }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("管理分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                GlassTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = "分类名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "删除分类不会删除笔记，旗下笔记将移回「未分类」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { confirmDeleteFolder = f; manageFolder = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除分类", maxLines = 1) }
                    Button(
                        onClick = { vm.renameFolder(f.id, name); manageFolder = null },
                        enabled = name.isNotBlank() && name != f.name,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberPrimary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("保存改名", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }

    confirmDeleteFolder?.let { f ->
        AnimatedAlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("删除分类「${f.name}」？") },
            text = { Text("旗下笔记将移回「未分类」，笔记本身不会丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFolder(f.id)
                    confirmDeleteFolder = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFolder = null }) { Text("取消") } }
        )
    }

    // —— vc68：顶栏右上角图标 →「分类」管理弹窗 ——
    // 「新建分类」与「管理分类」两个功能**统一收进这里**（vc67 时它们还散落在
    // FAB 的二选一抽屉里）。弹窗内先给「新建分类」一行快捷输入，
    // 下面列出全部分类供重命名 / 删除 —— 一个入口管完分类，不再到处找。
    if (showFolderManager) {
        var newFolderName by remember { mutableStateOf("") }
        GlassDialog(onDismissRequest = { showFolderManager = false }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // vc63（真机闪退根治）：这里绝不能再套 verticalScroll —— 外层 GlassDialog
                // 内容列已自带滚动，内层在无限高约束下测量直接抛异常（点「分类管理」必闪退的真凶）。
                Text("分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // —— 新建分类（就地起名，回车即建）——
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it.replace("\n", "").take(20) },
                        label = "新分类名称",
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                vm.createFolder(newFolderName)
                                newFolderName = ""
                            }
                        },
                        enabled = newFolderName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberPrimary,
                            contentColor = OnAmber
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("新建", maxLines = 1) }
                }
                if (folders.isEmpty()) {
                    Text(
                        "还没有分类，在上面输入名称即可新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    folders.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                f.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { showFolderManager = false; manageFolder = f }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "重命名", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { showFolderManager = false; confirmDeleteFolder = f }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // —— 移动笔记到分类 ——
    moveTarget?.let { m ->
        GlassDialog(onDismissRequest = { moveTarget = null }) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("移动到分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    m.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(30).orEmpty()
                        .ifBlank { "无标题笔记" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                folders.forEach { f ->
                    val selected = folderMap[m.id] == f.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                vm.assignFolder(m.id, f.id)
                                moveTarget = null
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = if (selected) AmberPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text("当前", style = MaterialTheme.typography.labelSmall, color = AmberPrimary)
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            vm.assignFolder(m.id, null)
                            moveTarget = null
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("移到「未分类」", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * v1.0.6.9：分类头部卡 —— 图标 + 名称 + 篇数 + 展开箭头 + 快捷新建。
 * vc66：「三点更多菜单」移除；右侧改为独立「+」按钮 —— 点击直接在该分类下新建笔记。
 */
@Composable
private fun FolderHeaderCard(
    folder: NoteFolder?,
    name: String = folder?.name.orEmpty(),
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddNote: () -> Unit = {}
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AmberPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "$count 篇笔记",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onAddNote,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "在此分类下新建笔记",
                    tint = AmberPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f)
            )
        }
    }
}

/** v1.0.6.9：分类内的「＋ 新建笔记」行（新笔记直接归入该分类） */
@Composable
private fun NewNoteInFolderRow(folderName: String, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                "在「$folderName」里写一篇",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 卡片流里的一张笔记（v1.0.12：标题 + 纯文本摘要）。
 * 关键修复：列表绝不直接渲染 Markdown 源码 —— 之前卡片上会出现 `### *小鸡小鸡*`
 * 这种原始标记。现在标题取正文第一行、摘要做 markdown -> 纯文本收敛，
 * 链接留文字、图片/HTML 标签直接剔除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(
    text: String,
    markdown: String,
    tags: List<String>,
    time: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    /** v1.0.6.9：移动到其它分类 */
    onMove: () -> Unit = {}
) {
    val plainLines = markdownToPlain(markdown).lines().filter { it.isNotBlank() }
    val title = plainLines.firstOrNull()?.take(60).orEmpty().ifBlank {
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60).orEmpty()
    }.ifBlank { "无标题笔记" }
    val preview = plainLines.drop(1).joinToString(" ").take(180)

    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.take(3).forEach { t ->
                        AssistChip(
                            onClick = {},
                            label = { Text("#$t", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // v1.0.6.9：移动到分类（文件夹层级的新操作）
                IconButton(onClick = onMove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = "移动到分类",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * v1.0.12：Markdown -> 纯文本（列表摘要 / 标题提取共用）。
 * 只做「看得见」的收敛：删标记、留文字，不追求完整解析 —— 全文渲染交给编辑页。
 */
internal fun markdownToPlain(md: String): String {
    var s = md
    s = s.replace(Regex("<[^>]{1,40}>"), "")
    s = s.replace(Regex("!\\[[^]]{0,80}]\\([^)]{0,200}\\)"), "")
    s = s.replace(Regex("\\[([^]]{0,120})]\\([^)]{0,200}\\)")) { it.groupValues[1] }
    s = s.replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s{0,3}\\d+[.)]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE), "")
    s = s.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
    s = s.replace(Regex("(?<!\\*)\\*(?!\\*)"), "")
    s = s.replace(Regex("[ \\t]{2,}"), " ")
    return s.trim()
}

/** 2026-10-19T12:34 → 10-19 12:34 */
private fun formatNoteTime(iso: String): String {
    if (iso.isBlank()) return ""
    val d = iso.take(10)
    val t = iso.substringAfter('T', "").take(5)
    return if (t.isBlank()) d else "$d $t"
}
