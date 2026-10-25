package com.example.earthonline.data.backup

import android.content.Context
import com.example.earthonline.data.local.dao.*
import com.example.earthonline.data.local.datastore.SettingsDataStore
import com.example.earthonline.data.local.entity.*
import com.example.earthonline.data.photos.MemoryPhoto
import com.example.earthonline.data.repository.XpLedger
import com.example.earthonline.util.ImageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量备份：把 Room 各表导出为单个 JSON（earth_data 等价物），或把 JSON 导回。
 * 导入采用 REPLACE 语义（按主键合并），不清除既有数据。
 */
@Singleton
class BackupRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val taskDao: TaskDao,
    private val memoDao: MemoDao,
    private val itemDao: ItemDao,
    private val achievementDao: AchievementDao,
    private val collectionDao: CollectionDao,
    private val locationDao: LocationDao,
    private val activityDao: ActivityDao,
    private val json: Json,
    // v1.0.4：导出前对账 profile.xp，保证导出的 XP 与派生口径一致
    private val xpLedger: XpLedger,
    // v1.0.0：头像改成「私有目录里的原图文件」，备份要把图一起带走，需要读写私有目录
    @ApplicationContext private val appContext: Context,
    // v1.0.5 清空数据：背包分类与 XP 流水两表也要清
    private val bagCategoryDao: BagCategoryDao,
    private val xpEventDao: XpEventDao,
    // v2.0.0 P0：相册元数据与主页速览排序此前完全不在 Payload 里，属于跨端必丢数据
    private val settings: SettingsDataStore
) {
    @Serializable
    data class Payload(
        val version: Int = 1,
        val exportedAt: String = "",
        val profile: ProfileEntity? = null,
        val tasks: List<TaskEntity> = emptyList(),
        val memos: List<MemoEntity> = emptyList(),
        val items: List<ItemEntity> = emptyList(),
        val achievements: List<AchievementEntity> = emptyList(),
        val collections: List<CollectionEntity> = emptyList(),
        val locations: List<LocationEntity> = emptyList(),
        val activities: List<ActivityEntity> = emptyList(),
        /** v2.0.0 P0：相册元数据（跨端可搬运；文件路径在落地时由对端各自处理） */
        val photos: List<MemoryPhoto> = emptyList(),
        /** v2.0.0 P0：主页速览入口自定义排序（JSON 字符串，空=用默认顺序） */
        val homeEntryOrder: String = ""
    )

    suspend fun exportJson(): String {
        // v1.0.4：先对账 XP（失败不阻塞导出，xp 列保持原值）
        runCatching { xpLedger.reconcile() }
        val payload = Payload(
            exportedAt = LocalDateTime.now().toString(),
            profile = profileDao.get()?.let { withAvatarBytes(it) },
            tasks = taskDao.observeAll().first(),
            memos = memoDao.observeAll().first(),
            items = itemDao.observeAll().first(),
            achievements = achievementDao.observeAll().first(),
            collections = collectionDao.observeAll().first(),
            locations = locationDao.observeAll().first(),
            activities = activityDao.observeAll().first(),
            photos = loadPhotos(),
            homeEntryOrder = settings.homeEntryOrder.first()
        )
        return json.encodeToString(Payload.serializer(), payload)
    }

    suspend fun importJson(text: String) {
        val p = json.decodeFromString<Payload>(text)
        p.profile?.let { profileDao.upsert(materializeAvatar(it)) }
        p.tasks.forEach { taskDao.insert(it) }
        p.memos.forEach { memoDao.insert(it) }
        p.items.forEach { itemDao.insert(it) }
        p.achievements.forEach { achievementDao.insert(it) }
        p.collections.forEach { collectionDao.insert(it) }
        p.locations.forEach { locationDao.insert(it) }
        p.activities.forEach { activityDao.insert(it) }
        // 相册：按主键 upsert（入站条目覆盖本地同 id，本地独有保留），保持与其它实体同语义
        if (p.photos.isNotEmpty()) mergePhotos(p.photos)
        if (p.homeEntryOrder.isNotBlank()) settings.setHomeEntryOrder(p.homeEntryOrder)
    }

    /** 读取相册元数据（导出前过滤软删：回收站永不跨端） */
    private suspend fun loadPhotos(): List<MemoryPhoto> {
        val txt = settings.memoryPhotosJson.first()
        if (txt.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MemoryPhoto.serializer()), txt)
        }.getOrDefault(emptyList()).filter { it.deletedAt == null }
    }

    /** 相册合并：同 id 以备份为准，本地独有条目保留 */
    private suspend fun mergePhotos(incoming: List<MemoryPhoto>) {
        val local = loadPhotos()
        val byId = LinkedHashMap<String, MemoryPhoto>()
        local.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        val merged = byId.values.toList()
        settings.setMemoryPhotosJson(
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MemoryPhoto.serializer()), merged)
        )
    }

    /**
     * v1.0.5「清空数据」：清空全部用户数据并恢复空种子，语义与 Web 端 resetAllData 对齐。
     *
     * 清空范围：十张表全部（profile/tasks/memos/items/achievements/collections/
     * locations/activities/bag_categories/xp_events）+ 头像原图文件。
     * 保留范围：DataStore 里的应用设置（主题/壁纸/WebDAV/AI 配置等，由调用方处理）
     * 与本地自动备份快照文件（由调用方决定是否一并清理）。
     *
     * 注意顺序：先删头像文件再清 profile 行，否则 avatarPath 随行丢失、文件成孤儿。
     */
    suspend fun clearAllData() {
        // 1. 头像原图文件（先取路径，再清行）
        profileDao.get()?.avatarPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { ImageStore.deleteFile(path) }
        }
        // 2. 十表整表清空
        taskDao.deleteAll()
        memoDao.deleteAll()
        itemDao.deleteAll()
        achievementDao.deleteAll()
        collectionDao.deleteAll()
        locationDao.deleteAll()
        activityDao.deleteAll()
        bagCategoryDao.deleteAll()
        xpEventDao.deleteAll()
        profileDao.deleteAll()
        // 3. 重建空种子资料行（全部默认值：name 空 / birthDate 空 / xp 0），
        //    避免「行缺失 = 未初始化」语义触发引导页（onboarding_done 已为 true，不会重进引导）
        profileDao.upsert(ProfileEntity())
    }

    /**
     * 导出：把头像原图**内联**进 JSON。
     *
     * v1.0.0 起头像存的是私有目录里的文件（filesDir/avatar/xxx.jpg），
     * 而 JSON 备份是要能搬到另一台机器上用的 —— 只记路径的话，换设备后头像就丢了。
     * 所以导出时把文件字节读出来塞回 `avatarData`（Base64），
     * 导入端再落成文件（见 [materializeAvatar]）。
     */
    private fun withAvatarBytes(p: ProfileEntity): ProfileEntity {
        if (p.avatarData != null && p.avatarData!!.isNotEmpty()) return p
        val file = p.avatarPath?.takeIf { it.isNotBlank() }
            ?.let(::File)?.takeIf { it.exists() && it.length() > 0L } ?: return p
        return runCatching { p.copy(avatarData = file.readBytes()) }.getOrDefault(p)
    }

    /** 导入：把内联的头像字节落成私有目录下的原图文件，并把路径写回资料行 */
    private fun materializeAvatar(p: ProfileEntity): ProfileEntity {
        val bytes = p.avatarData
        if (bytes == null || bytes.isEmpty()) return p
        val file = ImageStore.saveBytes(ImageStore.avatarDir(appContext), "avatar", bytes, "jpg")
            ?: return p
        return p.copy(avatarPath = file.absolutePath, avatarData = null)
    }
}
