#!/usr/bin/env python3
"""Create and validate a native-style English/Japanese review pass.

This is an offline authoring tool, not an Android build dependency. It uses an Apache-2.0
multilingual instruction model through MLX, checkpoints every reviewed batch, and refuses to
apply output when placeholders, source coverage, or script checks fail.
"""

from __future__ import annotations

import argparse
import base64
import importlib.util
import json
import os
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


KOREAN = re.compile(r"[가-힣]")
JAPANESE = re.compile(r"[぀-ヿ㐀-鿿]")
CATALOG_PLACEHOLDER = re.compile(r"\{\{([1-9][0-9]*)}}")
REVIEW_PLACEHOLDER = re.compile(
    r"\[\s*RUNTIME[_ ]*VALUE[_ ]*([1-9][0-9]*)\s*]",
    re.IGNORECASE,
)

GLOSSARY = """
Names: 로웬=Rowen/ロウェン; 니아=Nia/ニア; 오르반=Orban/オルバン;
베른=Bern/ベルン; 마엘=Mael/マエル; 라움=Raum/ラウム; 아셀=Asel/アセル;
리브=Liv/リヴ; 세라=Sera/セラ.
World terms: 새벽종=Dawn Bell/暁の鐘; 귀환종=Return Bell/帰還の鐘;
재의 문=Ashen Gate/灰の門; 재의 왕=Ash King/灰の王; 미궁=Labyrinth/迷宮;
국경=border/国境; 왕도=capital/王都; 성채=citadel/城塞; 수문=sluice gate/水門;
주조장=foundry/鋳造所; 문지기=gatekeeper/門番; 백야=White Night/白夜;
빈 겨울=Hollow Winter/虚ろな冬; 유리 심장=Glass Heart/ガラスの心臓.
UI terms: 모험가=Adventurer/冒険者; 전투력=Power/戦闘力; 장비력=Gear Power/装備力;
호칭=Title/称号; 스킬 경험치=Skill XP/スキル経験値; 전리품=Loot/戦利品;
막=Act/幕; 구역=Sector/区域; 관문=Gate/関門; 원정=Expedition/遠征.
""".strip()

SYSTEM_PROMPT = f"""You are a paired team of senior native English and Japanese game
localization editors. Localize Korean strings for a polished dark-fantasy idle RPG.

Editorial rules:
1. Preserve meaning, causality, speaker, tense, numbers, punctuation intent, and every placeholder.
2. Write idiomatic commercial-game English, never Korean word-order English.
3. Write idiomatic modern Japanese used in a commercial fantasy RPG. Check particles,
   conjugation, kanji choice, and spelling. Do not produce literal translationese.
4. Standalone names/titles should be concise title-style copy. Descriptions and story text should
   read naturally as prose. UI labels must stay compact enough for a phone.
5. Never romanize ordinary Korean words. Use the glossary consistently. A Korean word for a bell
   must never become paper, seed, species, or a person counter. 미궁 always means Labyrinth/迷宮.
6. Tokens such as [RUNTIME_VALUE_1] are immutable runtime values. Copy every one exactly once into both
   translations; reordering is allowed only when target grammar requires it.
7. Keep Lv., %, +, multiplication signs, bullets, and line breaks when they are meaningful.
8. Silently proofread both translations once before returning them.

Fixed glossary:
{GLOSSARY}

Return JSONL only, exactly one object per input and no Markdown:
{{"id":123,"en":"English","ja":"Japanese"}}
"""


def load_builder(project: Path) -> Any:
    path = project / "tools/localization/build_catalog.py"
    spec = importlib.util.spec_from_file_location("alarmquest_catalog_builder", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot import {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def decode(value: str) -> str:
    return base64.b64decode(value).decode("utf-8")


def encode(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def load_catalog(path: Path) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        row_type, source, target = line.split("\t")
        rows.append((row_type, decode(source), decode(target)))
    return rows


def load_review_overrides(project: Path) -> dict[str, dict[str, str]]:
    path = project / "tools/localization/review_overrides.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def review_source(value: str) -> str:
    return CATALOG_PLACEHOLDER.sub(
        lambda match: f"[RUNTIME_VALUE_{match.group(1)}]",
        value,
    )


def catalog_target(value: str) -> str:
    value = REVIEW_PLACEHOLDER.sub(lambda match: "{{" + match.group(1) + "}}", value)
    return value.strip()


def repair_placeholders(source: str, target: str) -> str:
    expected = CATALOG_PLACEHOLDER.findall(source)
    leading_match = re.match(r"^\s*((?:\{\{[1-9][0-9]*}}\s*)+)", source)
    trailing_match = re.search(r"((?:\s*\{\{[1-9][0-9]*}})+)\s*$", source)
    leading_tokens = CATALOG_PLACEHOLDER.findall(leading_match.group(1)) if leading_match else []
    trailing_tokens = CATALOG_PLACEHOLDER.findall(trailing_match.group(1)) if trailing_match else []

    # Generated prose occasionally keeps the first value but drops a second adjacent value.
    # Structured runtime values at either edge are safe to restore deterministically in the
    # same order as the authored source before the general single-token repair below.
    if leading_tokens:
        for placeholder_id in leading_tokens:
            target = target.replace("{{" + placeholder_id + "}}", "", 1)
        prefix = " ".join("{{" + placeholder_id + "}}" for placeholder_id in leading_tokens)
        target = f"{prefix} {target.lstrip()}"
    if trailing_tokens:
        for placeholder_id in trailing_tokens:
            target = target.replace("{{" + placeholder_id + "}}", "", 1)
        suffix = " ".join("{{" + placeholder_id + "}}" for placeholder_id in trailing_tokens)
        target = f"{target.rstrip()} {suffix}"

    for placeholder_id in expected:
        token = "{{" + placeholder_id + "}}"
        while target.count(token) > 1:
            target = target[::-1].replace(token[::-1], "", 1)[::-1]
        if token not in target and source.lstrip().startswith(token):
            target = f"{token} {target}"
        elif token not in target and source.rstrip().endswith(token):
            target = f"{target} {token}"
    return re.sub(r" {2,}", " ", target).strip()


def validate_target(source: str, target: str, language: str) -> str | None:
    expected = sorted(CATALOG_PLACEHOLDER.findall(source))
    actual = sorted(CATALOG_PLACEHOLDER.findall(target))
    if expected != actual:
        return f"placeholder mismatch {expected} != {actual}"
    if not target:
        return "empty target"
    if KOREAN.search(target):
        return "Korean remains"
    if language == "en" and JAPANESE.search(target):
        return "Japanese remains in English"
    if "<unk>" in target.lower():
        return "unknown token"
    if len(target) > max(240, len(source) * 8 + 120):
        return "runaway target"
    return None


def parse_jsonl(raw: str) -> dict[int, dict[str, str]]:
    parsed: dict[int, dict[str, str]] = {}
    decoder = json.JSONDecoder()
    cursor = 0
    while cursor < len(raw):
        start = raw.find("{", cursor)
        if start < 0:
            break
        try:
            item, consumed = decoder.raw_decode(raw[start:])
        except json.JSONDecodeError:
            cursor = start + 1
            continue
        cursor = start + consumed
        if isinstance(item, dict) and isinstance(item.get("id"), int):
            item_id = item["id"]
            partial = parsed.setdefault(item_id, {})
            for language in ("en", "ja"):
                if isinstance(item.get(language), str):
                    partial[language] = item[language]
            if not partial:
                parsed.pop(item_id, None)
    return parsed


def checkpoint(path: Path, reviewed: dict[str, dict[str, str]]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(reviewed, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def build_prompt(items: list[tuple[int, str]], context: str, strict: bool) -> str:
    correction = """
This is a correction retry. The previous response lost an item, placeholder, or target language.
Return all requested IDs. Copy every [RUNTIME_VALUE_n] token exactly into both targets.
""" if strict else ""
    inputs = "\n".join(
        json.dumps({"id": item_id, "ko": review_source(source)}, ensure_ascii=False)
        for item_id, source in items
    )
    return f"{SYSTEM_PROMPT}\n\nSource context: {context}.{correction}\nINPUT\n{inputs}"


def generate_review(
    model: Any,
    tokenizer: Any,
    items: list[tuple[int, str]],
    context: str,
    strict: bool = False,
) -> dict[int, dict[str, str]]:
    from mlx_lm import generate

    prompt = build_prompt(items, context, strict)
    template_arguments = {
        "add_generation_prompt": True,
        "tokenize": False,
    }
    if "Qwen3-" in getattr(tokenizer, "name_or_path", ""):
        template_arguments["enable_thinking"] = False
    rendered = tokenizer.apply_chat_template(
        [{"role": "user", "content": prompt}],
        **template_arguments,
    )
    source_characters = sum(len(source) for _, source in items)
    # A single rejected row only needs a compact correction response. Keeping the same
    # 1,200-token floor used for a batch made one-row retries needlessly expensive.
    max_tokens = min(8_000, max(256, source_characters + len(items) * 55))
    raw = generate(model, tokenizer, prompt=rendered, max_tokens=max_tokens, verbose=False)
    return parse_jsonl(raw)


def audit_review(
    sources: list[str],
    reviewed: dict[str, dict[str, str]],
) -> list[str]:
    problems: list[str] = []
    for source in sources:
        targets = reviewed.get(source)
        if targets is None:
            problems.append(f"missing source: {source!r}")
            continue
        for language in ("en", "ja"):
            problem = validate_target(source, targets.get(language, ""), language)
            if problem:
                problems.append(f"{language} {problem}: {source!r}")
    return problems


def apply_review(
    project: Path,
    reviewed: dict[str, dict[str, str]],
    builder: Any,
) -> None:
    for language, manual in (("en", builder.MANUAL_EN), ("ja", builder.MANUAL_JA)):
        path = project / f"app/src/simple/res/raw/localization_{language}.tsv"
        rows = load_catalog(path)
        output = ["# Native-style QA pass; Base64 TSV."]
        for row_type, source, _ in rows:
            target = manual.get(source, reviewed[source][language])
            output.append(f"{row_type}\t{encode(source)}\t{encode(target)}")
        path.write_text("\n".join(output) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--model", default="mlx-community/Qwen3-8B-4bit")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--min-source-length", type=int, default=0)
    parser.add_argument("--max-source-length", type=int)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    project = args.project.resolve()
    builder = load_builder(project)
    english_rows = load_catalog(project / "app/src/simple/res/raw/localization_en.tsv")
    sources = [source for _, source, _ in english_rows]
    sources = [
        source
        for source in sources
        if len(source) >= args.min_source_length
        and (args.max_source_length is None or len(source) <= args.max_source_length)
    ]
    if args.limit is not None:
        sources = sources[: args.limit]
    reviewed: dict[str, dict[str, str]] = {}
    if args.checkpoint.exists():
        reviewed = json.loads(args.checkpoint.read_text(encoding="utf-8"))
    overrides = load_review_overrides(project)
    for source in sources:
        if source in overrides:
            reviewed[source] = overrides[source]
    for source in sources:
        if source in builder.MANUAL_EN and source in builder.MANUAL_JA:
            reviewed[source] = {
                "en": builder.MANUAL_EN[source],
                "ja": builder.MANUAL_JA[source],
            }
    checkpoint(args.checkpoint, reviewed)

    pending = [source for source in sources if source not in reviewed]
    if pending:
        from mlx_lm import load

        model, tokenizer = load(args.model)
        completed = len(sources) - len(pending)
        for offset in range(0, len(pending), args.batch_size):
            batch_sources = pending[offset : offset + args.batch_size]
            indexed = list(enumerate(batch_sources, start=1))
            context = (
                "standalone compact skill, equipment, enemy, stat, or fantasy name fragments; "
                "translate the literal Korean meaning concisely, without inventing lore"
                if max(map(len, batch_sources)) <= 5
                else "mixed game strings"
            )
            results = generate_review(model, tokenizer, indexed, context=context)
            unresolved: list[tuple[int, str]] = []
            accepted: dict[str, dict[str, str]] = {}
            for item_id, source in indexed:
                result = results.get(item_id)
                if result is None or not all(language in result for language in ("en", "ja")):
                    unresolved.append((item_id, source))
                    continue
                targets = {
                    key: repair_placeholders(source, catalog_target(value))
                    for key, value in result.items()
                }
                if any(validate_target(source, targets[key], key) for key in ("en", "ja")):
                    unresolved.append((item_id, source))
                else:
                    accepted[source] = targets
            # Preserve every valid row before slower one-row correction retries. If a retry is
            # interrupted, a resumed run only repeats the unresolved rows.
            reviewed.update(accepted)
            checkpoint(args.checkpoint, reviewed)
            for item_id, source in unresolved:
                retry = generate_review(model, tokenizer, [(item_id, source)], "single game string", True)
                result = retry.get(item_id)
                if result is None or not all(language in result for language in ("en", "ja")):
                    raise RuntimeError(f"Model omitted retry item {item_id}: {source!r}")
                targets = {
                    key: repair_placeholders(source, catalog_target(value))
                    for key, value in result.items()
                }
                failures = [
                    f"{key}: {validate_target(source, targets[key], key)}"
                    for key in ("en", "ja")
                    if validate_target(source, targets[key], key)
                ]
                if failures:
                    raise RuntimeError(f"Review failed for {source!r}: {', '.join(failures)}")
                reviewed[source] = targets
                checkpoint(args.checkpoint, reviewed)
            completed += len(batch_sources)
            print(f"reviewed {completed}/{len(sources)}", flush=True)

    problems = audit_review(sources, reviewed)
    if problems:
        raise RuntimeError(f"Review audit failed ({len(problems)}):\n" + "\n".join(problems[:30]))
    print(f"audit passed for {len(sources)} sources", flush=True)
    if args.apply:
        if args.limit is not None or args.min_source_length or args.max_source_length is not None:
            raise RuntimeError("--apply requires an unfiltered full-catalog review")
        apply_review(project, reviewed, builder)
        print("reviewed catalogs applied", flush=True)


if __name__ == "__main__":
    main()
