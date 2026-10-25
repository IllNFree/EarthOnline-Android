# vc66: 全局一级页面背景透明化（透出壁纸+毛玻璃）
import io, re, os

ROOT = r"D:/AI/app/EarthOnline-Android/app/src/main/java/com/example/earthonline/ui"
FILES = [
    "tasks/TasksScreen.kt",
    "backpack/BackpackScreen.kt",
    "notes/NotesScreen.kt",
    "notes/NoteEditorScreen.kt",
    "settings/SettingsScreen.kt",
    "settings/SettingsPages.kt",
    "album/AlbumScreen.kt",
    "recyclebin/RecycleBinScreen.kt",
    "data/DataScreen.kt",
    "home/AllActivitiesScreen.kt",
    "profile/ProfileScreen.kt",
    "achievements/AchievementsScreen.kt",
    "ai/AiScreen.kt",
    "report/ReportScreen.kt",
    "steam/SteamGamesSection.kt",
    "map/MapScreen.kt",
]

for rel in FILES:
    p = os.path.join(ROOT, rel)
    with io.open(p, "r", encoding="utf-8") as f:
        src = f.read()
    orig = src
    src = src.replace(
        "containerColor = MaterialTheme.colorScheme.background",
        "containerColor = Color.Transparent",
    )
    if src != orig and "import androidx.compose.ui.graphics.Color" not in src:
        # 插到第一个 import 行之后
        m = re.search(r"^import .*$", src, re.M)
        if m:
            src = src[:m.end()] + "\nimport androidx.compose.ui.graphics.Color" + src[m.end():]
        else:
            src = src.replace("\n\n", "\nimport androidx.compose.ui.graphics.Color\n\n", 1)
    if src != orig:
        with io.open(p, "w", encoding="utf-8", newline="") as f:
            f.write(src)
        print("OK", rel)
    else:
        print("SKIP", rel)
