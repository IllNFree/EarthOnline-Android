package com.example.earthonline.data.backup

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import com.example.earthonline.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地自动备份：每次写操作之后在设备本地留一份 JSON 快照，只保留最近 3 份。
 *
 * 「每次写操作后」怎么知道？—— Room 的 InvalidationTracker。
 * 它是 Room 自带的、基于 SQLite 触发器 + 内存多版本快照的表变更通知机制，
 * 覆盖所有 DAO 写入（也包括导入备份、成就引擎回写），不需要在每个 Repository
 * 里手动插桩，也就不会漏掉将来新增的写入路径。
 *
 * 两个节流参数（缺一不可）：
 *  - DEBOUNCE_MS：连续写（比如导入一次备份会写 8 张表）合并成一次快照；
 *  - MIN_INTERVAL_MS：两次快照的最小间隔，防止「每改一个字就写一份」把磁盘刷爆。
 *
 * 快照只写 app 私有目录（filesDir/autobackup），不申请任何存储权限；
 * 卸载 App 会随之删除 —— 想长期留档请用设置页的「导出备份」。
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val repo: BackupRepository
) {

    data class Snapshot(val file: File, val timeMillis: Long, val bytes: Long)

    companion object {
        /** 保留份数（需求：最近 3 份） */
        const val KEEP = 3
        private const val TAG = "AutoBackup"
        private const val DEBOUNCE_MS = 4_000L
        private const val MIN_INTERVAL_MS = 60_000L

        /** 需要监听的表（顺序无关；漏掉的表变更后不会触发备份） */
        private val TABLES = arrayOf(
            "profile", "tasks", "memos", "items", "achievements",
            "collections", "locations", "activities", "bag_categories"
        )

        private val FILE_FMT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var pending: Job? = null
    @Volatile private var lastAt = 0L
    @Volatile private var started = false

    private fun dir(): File = File(context.filesDir, "autobackup").apply { if (!exists()) mkdirs() }

    /**
     * 注册表变更监听。应在 Application.onCreate 里调一次（幂等）。
     * 注意 只监听业务表：本管理器自己写的是文件，不落库，因此不会自激循环。
     */
    fun start() {
        if (started) return
        started = true
        runCatching {
            db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLES) {
                // 注意是 Set<String> 不是 MutableSet —— Room 2.6 起改成了只读集合
                override fun onInvalidated(tables: Set<String>) {
                    schedule()
                }
            })
        }.onFailure { Log.w(TAG, "自动备份监听注册失败", it) }
    }

    /** 变更后的防抖调度：最后一次写入后 DEBOUNCE_MS 落盘 */
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { snapshot(reasonCheck = true) }
                .onFailure { Log.w(TAG, "自动备份失败", it) }
        }
    }

    /**
     * 立刻写一份快照（设置页「立即备份」按钮也走这里）。
     * @param reasonCheck true 时遵守 MIN_INTERVAL_MS 节流；手动触发传 false 强制写
     * @return 新快照文件；被节流跳过时返回 null
     */
    suspend fun snapshot(reasonCheck: Boolean): File? = writeMutex.withLock {
        val now = System.currentTimeMillis()
        if (reasonCheck && now - lastAt < MIN_INTERVAL_MS) return null
        lastAt = now

        val text = repo.exportJson()
        if (text.length < 32) return null          // 空库没必要留快照
        val f = File(dir(), "auto-${FILE_FMT.format(now)}.json")
        f.writeText(text, Charsets.UTF_8)
        trim()
        return f
    }

    /** 只保留最近 [KEEP] 份（按文件名时间戳排序是最稳的，不依赖文件系统 mtime） */
    private fun trim() {
        val all = dir().listFiles { f -> f.isFile && f.name.startsWith("auto-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: return
        all.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** 快照列表（新 -> 旧），供设置页展示 */
    fun listSnapshots(): List<Snapshot> =
        dir().listFiles { f -> f.isFile && f.name.startsWith("auto-") && f.name.endsWith(".json") }
            ?.map { f ->
                val millis = runCatching { FILE_FMT.parse(f.name.removePrefix("auto-").removeSuffix(".json"))?.time }
                    .getOrNull() ?: f.lastModified()
                Snapshot(f, millis, f.length())
            }
            ?.sortedByDescending { it.timeMillis }
            ?: emptyList()

    /** 从某份快照恢复（走 BackupRepository 的按主键合并语义，与导入备份一致） */
    suspend fun restore(s: Snapshot): Boolean = runCatching {
        repo.importJson(s.file.readText(Charsets.UTF_8))
        true
    }.getOrElse {
        Log.w(TAG, "自动备份恢复失败", it)
        false
    }

    /**
     * v1.0.5 清空数据：删除全部本地自动快照。
     * 快照本身就是完整存档的副本 —— 用户数据都清了，快照不能留着（否则「清空」名不副实，
     * 且下次 InvalidationTracker 触发的新快照还会与旧快照混在一起）。
     * @return 删除的文件数
     */
    suspend fun clearSnapshots(): Int = writeMutex.withLock {
        val all = dir().listFiles { f -> f.isFile && f.name.startsWith("auto-") && f.name.endsWith(".json") }
            ?: return@withLock 0
        var n = 0
        all.forEach { f -> if (runCatching { f.delete() }.getOrDefault(false)) n++ }
        n
    }
}
