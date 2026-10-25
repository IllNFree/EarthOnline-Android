#!/usr/bin/env python3
"""
vc67：删除 HazeScrollAware 包装（滚动降级逻辑已整体删除，包装层成为纯空壳）。

做法（按花括号配对定位，机械且可校验）：
  1. 找到形如 `HazeScrollAware(...) {` 的开括号行；
  2. 从该行起做花括号计数，找到配对的闭合行；
  3. 删掉这两行，并把中间内容整体左移 4 个空格（还原缩进层级）；
  4. 删掉闭合行上的 `// vc64：滚动降级作用域闭合` 注释残留。

执行后脚本会再做一次全文件花括号平衡校验。
"""
import re
import sys

TARGETS = [
    ("app/src/main/java/com/example/earthonline/ui/home/HomeScreen.kt", "homeListState"),
    ("app/src/main/java/com/example/earthonline/ui/tasks/TasksScreen.kt", "listState"),
    ("app/src/main/java/com/example/earthonline/ui/notes/NotesScreen.kt", "listState"),
]

OPEN_RE = re.compile(r"^\s*HazeScrollAware\(\s*\w+\s*\)\s*\{\s*$")
CLOSE_RE = re.compile(r"^\s*\}\s*//\s*vc64：滚动降级作用域闭合\s*$")


def strip_block(lines, start):
    """返回 (新行列表, 配对闭合行下标)。start 为开括号行下标。"""
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth == 0:
            return i
    raise RuntimeError("未找到配对的闭合花括号")


def process(path, arg):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")

    changed = False
    i = 0
    while i < len(lines):
        if OPEN_RE.match(lines[i]) and arg in lines[i]:
            close = strip_block(lines, i)
            body = lines[i + 1:close]
            # 去掉可能存在的空行首尾
            body = [ln[4:] if ln.startswith("    ") else ln for ln in body]
            new_lines = (
                lines[:i]
                + body
                + [ln for ln in lines[close + 1:]]
            )
            lines = new_lines
            changed = True
            continue
        i += 1

    if not changed:
        print(f"  ⚠️  {path}: 未找到 HazeScrollAware 包装（可能已处理）")
        return False

    # 校验花括号平衡
    bal = sum(ln.count("{") - ln.count("}") for ln in lines)
    if bal != 0:
        print(f"  ❌ {path}: 花括号不平衡（{bal:+d}），回滚！")
        raise SystemExit(1)

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"  ✅ {path}: 已删除 HazeScrollAware 包装，花括号平衡 ✓")
    return True


if __name__ == "__main__":
    os_ok = True
    for path, arg in TARGETS:
        try:
            process(path, arg)
        except Exception as e:
            print(f"  ❌ {path}: {e}")
            os_ok = False
    sys.exit(0 if os_ok else 1)
