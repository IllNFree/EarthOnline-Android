# vc66: 堆叠区嵌套玻璃卡去除 —— 内层 GlassCard 改为普通 Column（双层玻璃取景区不同产生横向断层露底）
import io

p = r"D:/AI/app/EarthOnline-Android/app/src/main/java/com/example/earthonline/ui/home/HomeScreen.kt"
with io.open(p, "r", encoding="utf-8") as f:
    lines = f.readlines()

# 1-based 行号 → 0-based；这些是堆叠区内层卡（仅堆叠区使用）
targets = {1205, 1298, 1899, 1956, 2101}
for ln in targets:
    idx = ln - 1
    assert lines[idx].strip() == "GlassCard(modifier = Modifier.fillMaxWidth()) {", (ln, lines[idx])
    lines[idx] = lines[idx].replace(
        "GlassCard(modifier = Modifier.fillMaxWidth()) {",
        "Column(modifier = Modifier.fillMaxWidth()) {",
    )

with io.open(p, "w", encoding="utf-8", newline="") as f:
    f.writelines(lines)
print("done, replaced", len(targets))
