#!/usr/bin/env python3
"""静态检查：在没有 Android SDK/JDK 的机器上尽可能提前发现编译期问题。

检查项：
1. 所有 XML 是否格式正确
2. XML / Kotlin 中的 @drawable/@color/@string/@style/@mipmap/@layout 引用是否存在
3. Kotlin 中 R.xxx.name 引用的资源是否存在（忽略 android.R.*）
4. Kotlin 中 binding.xxx 引用的控件 id 是否存在于对应布局
5. Kotlin 括号/花括号是否平衡
6. Kotlin import 的工程内类是否存在于源码树（忽略生成的 R/BuildConfig/databinding）
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(SRC, "res")
JAVA = os.path.join(SRC, "java", "com", "openlauncher", "tv")

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
GENERATED = {"R", "BuildConfig"}

errors = []


def camel(snake: str) -> str:
    parts = snake.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def collect_resources():
    res = {"drawable": set(), "color": set(), "string": set(), "style": set(),
           "layout": set(), "mipmap": set(), "array": set(), "anim": set(),
           "integer": set(), "dimen": set(), "bool": set(), "xml": set()}
    ids = {}

    for base, _, files in os.walk(RES):
        folder = os.path.basename(base)
        kind = folder.split("-")[0]
        for f in files:
            if not f.endswith(".xml"):
                continue
            path = os.path.join(base, f)
            name = f.rsplit(".", 1)[0]
            if kind in res and kind != "values":
                res[kind].add(name)
            try:
                tree = ET.parse(path)
            except ET.ParseError as e:
                errors.append(f"XML 解析失败 {path}: {e}")
                continue

            if kind == "values":
                for el in tree.getroot():
                    tag = el.tag
                    res_name = el.get("name")
                    if not res_name:
                        continue
                    if tag == "item" and el.get("type"):
                        tag = el.get("type")
                    if tag in ("color", "string", "style", "integer", "dimen", "bool", "array"):
                        res.setdefault(tag, set()).add(res_name)
                    elif tag == "string-array":
                        res["array"].add(res_name)
                    elif tag == "style":
                        res["style"].add(res_name)

            if kind == "layout":
                ids[name] = {
                    el.get(ANDROID_NS + "id", "")[5:]
                    for el in tree.iter()
                    if el.get(ANDROID_NS + "id", "").startswith("@+id/")
                }
    return res, ids


def check_xml(res):
    ref = re.compile(
        r"@(drawable|color|string|style|mipmap|layout|array|anim|integer|dimen)/"
        r"([A-Za-z0-9_.]+)")
    for base, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith(".xml"):
                continue
            path = os.path.join(base, f)
            text = open(path, encoding="utf-8").read()
            for kind, name in ref.findall(text):
                if name.startswith("android:") or name.startswith("@"):
                    continue
                if kind in res and name not in res[kind]:
                    errors.append(f"{os.path.relpath(path, ROOT)}: 缺少资源 @{kind}/{name}")


def check_kotlin(res, ids):
    layout_by_activity = {
        "MainActivity.kt": "activity_main",
        "SettingsActivity.kt": "activity_settings",
        "CategoryManagerActivity.kt": "activity_category_manager",
        "ParentalSettingsActivity.kt": "activity_parental_settings",
        "LockScreenActivity.kt": "activity_lock",
        "AllAppsActivity.kt": "activity_all_apps",
    }
    r_ref = re.compile(r"(?<!android\.)\bR\.(drawable|color|string|style|mipmap|layout|array|anim|id)\.([A-Za-z0-9_]+)")

    classes = set()
    decl = re.compile(r"^\s*(?:@\w+\s+)*(?:data\s+|sealed\s+|abstract\s+|open\s+|internal\s+)?"
                      r"(?:class|object|interface)\s+(\w+)", re.M)
    for base, _, files in os.walk(JAVA):
        for f in files:
            if f.endswith(".kt"):
                classes.add(f[:-3])
                text = open(os.path.join(base, f), encoding="utf-8").read()
                classes.update(decl.findall(text))

    all_ids = {i for s in ids.values() for i in s}

    for base, _, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(base, f)
            rel = os.path.relpath(path, ROOT)
            text = open(path, encoding="utf-8").read()

            for o, c in (("{", "}"), ("(", ")")):
                if text.count(o) != text.count(c):
                    errors.append(f"{rel}: {o}{c} 不匹配 ({text.count(o)} vs {text.count(c)})")

            for kind, name in r_ref.findall(text):
                if kind == "id":
                    if name not in all_ids:
                        errors.append(f"{rel}: 缺少 R.id.{name}")
                elif kind == "style":
                    # 带点的 style 名在 R 里会转成下划线
                    if name not in {s.replace(".", "_") for s in res["style"]}:
                        errors.append(f"{rel}: 缺少 R.style.{name}")
                elif kind in res and name not in res[kind]:
                    errors.append(f"{rel}: 缺少 R.{kind}.{name}")

            if f in layout_by_activity:
                layout_ids = ids.get(layout_by_activity[f], set())
                for field in set(re.findall(r"\bbinding\.([a-z][A-Za-z0-9]*)\b", text)):
                    if field == "root":
                        continue
                    if not any(camel(i) == field for i in layout_ids):
                        errors.append(f"{rel}: binding.{field} 在 {layout_by_activity[f]}.xml 中无对应 id")

            for imp in re.findall(r"^import\s+(com\.openlauncher\.tv\.[A-Za-z0-9_.]+)", text, re.M):
                parts = imp.split(".")
                cls = parts[-1]
                if cls in GENERATED or "databinding" in parts:
                    continue
                if cls[0].isupper() and cls not in classes:
                    errors.append(f"{rel}: import 的类不存在 {imp}")


def main():
    res, ids = collect_resources()
    check_xml(res)
    check_kotlin(res, ids)

    print(f"资源: drawable={len(res['drawable'])} color={len(res['color'])} "
          f"string={len(res['string'])} style={len(res['style'])} "
          f"layout={len(res['layout'])} mipmap={len(res['mipmap'])}")
    print("布局 id: " + ", ".join(f"{k}={len(v)}" for k, v in sorted(ids.items())))
    if errors:
        print(f"\n发现 {len(errors)} 个问题:")
        for e in errors:
            print("  -", e)
        return 1
    print("\n静态检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
