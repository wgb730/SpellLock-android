#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验词表与考研白名单是否满足硬性要求。

用法（项目根目录执行）：
    python3 tools/validate_words.py

检查项：
1. words.txt 每行格式必须是 word|中文释义|巧记(可空)（# 注释、空行允许）
2. 单词不重复；只含英文字母/连字符/撇号；释义必须有中文
3. 白名单 kaoyan_core_set.txt：
   - 首行有「考研词汇」来源标记
   - 每行都是合法小写单词
   - 词数精确等于 WordRepository.kt 里的 EXPECTED_KAOYAN_WORDS（防止"两边一起改"绕过）
4. words.txt 的每个词都必须落在白名单内
5. 可用词数 >= WordRepository.REQUIRED_WORDS（当前 10）
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
WORDS = ASSETS / "words.txt"
INDEX = ASSETS / "kaoyan_core_set.txt"
PHONETICS = ASSETS / "phonetics.txt"
REPO_KT = ROOT / "app" / "src" / "main" / "java" / "com" / "detox" / "spelllock" / "WordRepository.kt"

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'-]*$")
INDEX_LINE_RE = re.compile(r"^[a-z][a-z'-]*$")
CN_RE = re.compile(r"[\u4e00-\u9fff]")
EXPECTED_RE = re.compile(r"EXPECTED_KAOYAN_WORDS\s*=\s*(\d+)")
REQUIRED_RE = re.compile(r"REQUIRED_WORDS\s*=\s*(\d+)")


def read_int(pattern, name: str) -> int:
    if not REPO_KT.exists():
        sys.exit("缺少 WordRepository.kt: " + str(REPO_KT))
    m = pattern.search(REPO_KT.read_text(encoding="utf-8"))
    if not m:
        sys.exit("WordRepository.kt 里找不到 " + name)
    return int(m.group(1))


def load_index(errors) -> set:
    if not INDEX.exists():
        sys.exit("缺少考研词表白名单: " + str(INDEX))
    text = INDEX.read_text(encoding="utf-8")
    first_line = next((l.strip() for l in text.splitlines() if l.strip()), "")
    if not first_line.startswith("#") or "考研词汇" not in first_line:
        errors.append("白名单首行缺少「考研词汇」来源标记")
    words = set()
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if not INDEX_LINE_RE.match(line):
            errors.append("白名单第 %d 行格式不合法: %s" % (lineno, line))
            continue
        words.add(line)
    return words


def main() -> int:
    if not WORDS.exists():
        print("FAIL 缺少词表文件: " + str(WORDS))
        return 1

    expected = read_int(EXPECTED_RE, "EXPECTED_KAOYAN_WORDS")
    required = read_int(REQUIRED_RE, "REQUIRED_WORDS")
    errors = []
    index = load_index(errors)
    if len(index) != expected:
        errors.append(
            "白名单词数不符：应为 %d，实际 %d（白名单是考研大纲快照，改动会破坏范围保证）"
            % (expected, len(index))
        )

    seen = {}
    entries = 0
    for lineno, raw in enumerate(WORDS.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries += 1
        parts = line.split("|", 2)
        if len(parts) < 2:
            errors.append("第 %d 行格式错误（不是 word|中文释义）: %s" % (lineno, line))
            continue
        word = parts[0].strip()
        meaning = parts[1].strip()
        if not WORD_RE.match(word):
            errors.append("第 %d 行单词不合法: %s" % (lineno, word))
        if not meaning or not CN_RE.search(meaning):
            errors.append("第 %d 行缺中文释义: %s" % (lineno, line))
        key = word.lower()
        if key in seen:
            errors.append("第 %d 行重复单词: %s（首次出现于第 %d 行）" % (lineno, word, seen[key]))
        else:
            seen[key] = lineno
        if key not in index:
            errors.append("第 %d 行不在考研大纲范围: %s" % (lineno, word))

    if entries < required:
        errors.append("可用单词不足 %d 个（当前 %d 个）" % (required, entries))

    if PHONETICS.exists():
        ph = 0
        for raw in PHONETICS.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            ph += 1
            if "|" not in line or not line.split("|", 1)[1].strip().startswith("/"):
                errors.append("音标行格式错误: " + line)
        print("  音标表    : %d 条" % ph)
    else:
        errors.append("缺少音标表: " + str(PHONETICS))

    if errors:
        print("FAIL 共 %d 个问题：" % len(errors))
        for e in errors[:50]:
            print("  - " + e)
        return 1

    print("PASS 词表校验通过")
    print("  考研白名单: %d 词（快照，受 EXPECTED_KAOYAN_WORDS 约束）" % len(index))
    print("  出题词表  : %d 词（全部属于考研大纲范围，要求 >= %d）" % (entries, required))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
