package com.example.earthonline.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.earthonline.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProfileEntity)

    /** v1.0.5 清空数据：清空资料行（清空后由 BackupRepository 重建空种子行） */
    @Query("DELETE FROM profile")
    suspend fun deleteAll()
}

@Dao
interface TaskDao {
    /* v1.0.5 回收站：所有常规读取统一过滤软删行（deletedAt IS NULL）；
       回收站专用查询见文件尾注释同段。 */
    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE parentId IS NULL AND deletedAt IS NULL ORDER BY sort_order ASC")
    fun observeRoots(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE parentId = :pid AND deletedAt IS NULL ORDER BY sort_order ASC")
    fun observeChildren(pid: String): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE deletedAt IS NULL")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE doneAt IS NOT NULL AND deletedAt IS NULL")
    fun doneCount(): Flow<Int>

    /** 人生时间轴：最近完成的任务（限量，避免首页加载全表） */
    @Query("SELECT * FROM tasks WHERE doneAt IS NOT NULL AND deletedAt IS NULL ORDER BY doneAt DESC LIMIT :limit")
    fun observeDoneRecent(limit: Int): Flow<List<TaskEntity>>

    /* ---------------- v1.0.0：周期性报告（日报 / 周报 / 年报） ----------------
       区间一律「左闭右开」[from, to)，与 DateUtils.dayStartIso 生成的边界配套。
       doneAt 是定长 UTC ISO 串（yyyy-MM-dd'T'HH:mm:ss'Z'），字典序即时间序，
       所以可以直接用字符串比较，不需要在 SQL 里做日期转换（SQLite 也没有本地时区概念）。 */

    @Query(
        "SELECT COUNT(*) FROM tasks WHERE doneAt IS NOT NULL AND deletedAt IS NULL " +
            "AND doneAt >= :from AND doneAt < :to"
    )
    fun doneCountBetween(from: String, to: String): Flow<Int>

    @Query(
        "SELECT * FROM tasks WHERE doneAt IS NOT NULL AND deletedAt IS NULL " +
            "AND doneAt >= :from AND doneAt < :to ORDER BY doneAt DESC LIMIT :limit"
    )
    fun doneBetween(from: String, to: String, limit: Int): Flow<List<TaskEntity>>

    /**
     * v1.2.1 全局搜索：标题 / 备注模糊匹配，限量 20。
     * 走 SQL 而不是「拉全表再 filter」—— 数据量大了以后，全表进内存再过滤的成本
     * 比一条带 LIMIT 的 LIKE 高一个数量级。IFNULL 是必须的：note 可空，
     * NULL LIKE '%x%' 在 SQLite 里结果是 NULL（不是 false），整条会被静默漏掉。
     */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND (title LIKE '%' || :q || '%' " +
            "OR IFNULL(note, '') LIKE '%' || :q || '%') ORDER BY sort_order ASC LIMIT 20"
    )
    fun search(q: String): Flow<List<TaskEntity>>

    /* ---------------- v1.0.5 回收站 ---------------- */

    /** 软删单条（幂等：已软删的不刷新时间戳） */
    @Query("UPDATE tasks SET deletedAt = :ts WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, ts: String)

    /** 级联软删子任务（与父任务同批时间戳，恢复时按父恢复） */
    @Query("UPDATE tasks SET deletedAt = :ts WHERE parentId = :pid AND deletedAt IS NULL")
    suspend fun softDeleteChildren(pid: String, ts: String)

    @Query("SELECT * FROM tasks WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycled(): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** 恢复时连带子任务（只恢复仍处于软删态的） */
    @Query("UPDATE tasks SET deletedAt = NULL WHERE parentId = :pid AND deletedAt IS NOT NULL")
    suspend fun restoreChildren(pid: String)

    /** 30 天到期物理清理（ISO 串字典序即时间序） */
    @Query("DELETE FROM tasks WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: String)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: TaskEntity)

    @Update
    suspend fun update(t: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks WHERE parentId = :pid")
    suspend fun deleteChildren(pid: String)

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoEntity>>

    /** 首页只取最近 N 条，不再把全表拉进内存 */
    @Query("SELECT * FROM memos WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MemoEntity>>

    @Query("SELECT COUNT(*) FROM memos WHERE deletedAt IS NULL")
    fun count(): Flow<Int>

    /** v1.0.0：区间内新增灵感数（报告用，左闭右开） */
    @Query("SELECT COUNT(*) FROM memos WHERE deletedAt IS NULL AND createdAt >= :from AND createdAt < :to")
    fun countBetween(from: String, to: String): Flow<Int>

    /** v1.0.0：区间内灵感明细（报告图表按小时/天/月聚合用） */
    @Query(
        "SELECT * FROM memos WHERE deletedAt IS NULL AND createdAt >= :from AND createdAt < :to " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun between(from: String, to: String, limit: Int): Flow<List<MemoEntity>>

    /** v1.0.5 全局搜索：日志正文模糊匹配（含 type） */
    @Query(
        "SELECT * FROM memos WHERE deletedAt IS NULL AND text LIKE '%' || :q || '%' " +
            "ORDER BY createdAt DESC LIMIT 20"
    )
    fun search(q: String): Flow<List<MemoEntity>>

    /* ---------------- v1.0.5 回收站 ---------------- */
    @Query("UPDATE memos SET deletedAt = :ts WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, ts: String)
    @Query("SELECT * FROM memos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycled(): Flow<List<MemoEntity>>

    @Query("UPDATE memos SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM memos WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MemoEntity)

    @Query("DELETE FROM memos WHERE id = :id")
    suspend fun delete(id: String)

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM memos")
    suspend fun deleteAll()
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items WHERE deletedAt IS NULL")
    fun count(): Flow<Int>

    /** v1.2.1 全局搜索：物品名 / 描述模糊匹配 */
    @Query(
        "SELECT * FROM items WHERE deletedAt IS NULL AND (name LIKE '%' || :q || '%' " +
            "OR IFNULL(description, '') LIKE '%' || :q || '%') ORDER BY createdAt DESC LIMIT 20"
    )
    fun search(q: String): Flow<List<ItemEntity>>

    /** 已使用的自定义分类数（首页概览卡「分类 N」） */
    @Query("SELECT COUNT(DISTINCT category) FROM items WHERE deletedAt IS NULL AND category IS NOT NULL AND category != ''")
    fun categoryCount(): Flow<Int>

    /** v1.0.3 报告：区间内拾取的物品数（createdAt 是 YYYY-MM-DD 日键，闭区间） */
    @Query("SELECT COUNT(*) FROM items WHERE deletedAt IS NULL AND createdAt >= :fromDay AND createdAt <= :toDay")
    fun countBetweenDays(fromDay: String, toDay: String): Flow<Int>

    /* ---------------- v1.0.5 回收站 ---------------- */
    @Query("UPDATE items SET deletedAt = :ts WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, ts: String)

    @Query("SELECT * FROM items WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycled(): Flow<List<ItemEntity>>

    @Query("UPDATE items SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(i: ItemEntity)

    @Update
    suspend fun update(i: ItemEntity)

    /** 删除分类时把引用它的物品回落为「未分类」（物品本身保留） */
    @Query("UPDATE items SET category = NULL WHERE category = :name")
    suspend fun clearCategory(name: String)

    /** 分类改名后同步条目上的分类名（条目存的是分类名字符串，不同步会失联） */
    @Query("UPDATE items SET category = :newName WHERE category = :oldName")
    suspend fun renameCategory(oldName: String, newName: String)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: String)

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM items")
    suspend fun deleteAll()
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY type ASC, title ASC")
    fun observeAll(): Flow<List<AchievementEntity>>

    /** v1.0.5 全局搜索：成就标题 / 描述模糊匹配（含未解锁） */
    @Query(
        "SELECT * FROM achievements WHERE title LIKE '%' || :q || '%' " +
            "OR desc LIKE '%' || :q || '%' ORDER BY unlocked DESC, title ASC LIMIT 20"
    )
    fun search(q: String): Flow<List<AchievementEntity>>

    @Query("SELECT COUNT(*) FROM achievements WHERE unlocked = 1")
    fun unlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM achievements")
    fun totalCount(): Flow<Int>

    /** v1.0.0：区间内解锁成就数（报告用；unlockedAt 可空，NULL 行不计入） */
    @Query(
        "SELECT COUNT(*) FROM achievements WHERE unlocked = 1 AND unlockedAt IS NOT NULL " +
            "AND unlockedAt >= :from AND unlockedAt < :to"
    )
    fun unlockedCountBetween(from: String, to: String): Flow<Int>

    /** v1.0.0：区间内解锁成就明细（报告图表聚合用） */
    @Query(
        "SELECT * FROM achievements WHERE unlocked = 1 AND unlockedAt IS NOT NULL " +
            "AND unlockedAt >= :from AND unlockedAt < :to ORDER BY unlockedAt DESC LIMIT :limit"
    )
    fun unlockedBetween(from: String, to: String, limit: Int): Flow<List<AchievementEntity>>

    /** 人生时间轴：最近解锁的成就（限量） */
    @Query(
        "SELECT * FROM achievements WHERE unlocked = 1 AND unlockedAt IS NOT NULL " +
            "ORDER BY unlockedAt DESC LIMIT :limit"
    )
    fun observeUnlockedRecent(limit: Int): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: AchievementEntity)

    @Update
    suspend fun update(a: AchievementEntity)

    @Query("DELETE FROM achievements WHERE id = :id")
    suspend fun delete(id: String)

    /** v1.0.5 清空数据：整表清空（成就定义由引擎下次启动时重建，解锁记录全部丢失） */
    @Query("DELETE FROM achievements")
    suspend fun deleteAll()
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT COUNT(*) FROM collections WHERE deletedAt IS NULL")
    fun count(): Flow<Int>

    /** v1.2.1 全局搜索：收藏标题 / 备注模糊匹配 */
    @Query(
        "SELECT * FROM collections WHERE deletedAt IS NULL AND (title LIKE '%' || :q || '%' " +
            "OR IFNULL(note, '') LIKE '%' || :q || '%') ORDER BY createdAt DESC LIMIT 20"
    )
    fun search(q: String): Flow<List<CollectionEntity>>

    /* ---------------- v1.0.5 回收站 ---------------- */
    @Query("UPDATE collections SET deletedAt = :ts WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, ts: String)

    @Query("SELECT * FROM collections WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycled(): Flow<List<CollectionEntity>>

    @Query("UPDATE collections SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM collections WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: CollectionEntity)

    @Update
    suspend fun update(c: CollectionEntity)

    /** 删除分类时把引用它的收藏回落为「未分类」（收藏本身保留） */
    @Query("UPDATE collections SET category = NULL WHERE category = :name")
    suspend fun clearCategory(name: String)

    /** 分类改名后同步条目上的分类名 */
    @Query("UPDATE collections SET category = :newName WHERE category = :oldName")
    suspend fun renameCategory(oldName: String, newName: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM collections")
    suspend fun deleteAll()
}

@Dao
interface LocationDao {
    /* v2.0.0 P0：足迹纳入 deletedAt 软删体系，常规读取统一过滤已软删行 */
    @Query("SELECT * FROM locations WHERE deletedAt IS NULL ORDER BY date DESC")
    fun observeAll(): Flow<List<LocationEntity>>

    /** v1.0.5 全局搜索：足迹名称 / 备注 / 标签模糊匹配 */
    @Query(
        "SELECT * FROM locations WHERE deletedAt IS NULL AND (name LIKE '%' || :q || '%' " +
            "OR IFNULL(note, '') LIKE '%' || :q || '%' " +
            "OR IFNULL(tagsJson, '') LIKE '%' || :q || '%') ORDER BY date DESC LIMIT 20"
    )
    fun search(q: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE deletedAt IS NULL ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LocationEntity>>

    @Query("SELECT COUNT(*) FROM locations WHERE deletedAt IS NULL")
    fun count(): Flow<Int>

    /** v1.0.0：区间内新增足迹数。locations.date 存的是本地日键（YYYY-MM-DD），
     *  不是 ISO 时间戳，所以这里是闭区间 [fromDay, toDay] —— 与上面几张表的语义不同，勿混用。 */
    @Query("SELECT COUNT(*) FROM locations WHERE deletedAt IS NULL AND date >= :fromDay AND date <= :toDay")
    fun countBetweenDays(fromDay: String, toDay: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(l: LocationEntity)

    @Update
    suspend fun update(l: LocationEntity)

    /** v2.0.0 P0：软删除（打时间戳，行仍在库内，可从回收站恢复） */
    @Query("UPDATE locations SET deletedAt = :ts WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: String, ts: String)

    /** 回收站：全部已软删足迹 */
    @Query("SELECT * FROM locations WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycled(): Flow<List<LocationEntity>>

    /** 恢复：清掉时间戳，行回到常规列表 */
    @Query("UPDATE locations SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** 彻底删除（回收站里再删一次 = 真删） */
    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun delete(id: String)

    /** 30 天到期物理清理（cutoff = ISO 时间串） */
    @Query("DELETE FROM locations WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: String)

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM locations")
    suspend fun deleteAll()
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY time DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: ActivityEntity)

    /** 删除单条动态（用户自定义动态的删除入口） */
    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String)

    /** 裁剪：仅保留最近 50 条，超出部分删最旧 */
    @Query("DELETE FROM activities WHERE id NOT IN (SELECT id FROM activities ORDER BY time DESC LIMIT 50)")
    suspend fun trim()

    /** v1.0.5 清空数据：整表清空 */
    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}

@Dao
interface XpEventDao {
    @Query("SELECT * FROM xp_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<XpEventEntity>>

    @Query("SELECT COUNT(*) FROM xp_events")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_events WHERE kind = :kind")
    suspend fun sumByKind(kind: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: XpEventEntity)

    /** v1.0.5 清空数据：清空 XP 流水（profile.xp 一并由种子行归零） */
    @Query("DELETE FROM xp_events")
    suspend fun deleteAll()
}
