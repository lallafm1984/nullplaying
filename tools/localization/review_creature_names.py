#!/usr/bin/env python3
"""Review mistranslated Korean creature names with fixed species suffixes.

This optional offline authoring tool is not an Android build dependency. It keeps the authored
prefix concise, forces the Korean creature species to remain exact in English and Japanese, and
never applies a partial or structurally invalid model response.
"""

from __future__ import annotations

import argparse
import base64
import json
import re
from pathlib import Path
from typing import Any


KOREAN = re.compile(r"[가-힣]")
JAPANESE = re.compile(r"[぀-ヿ㐀-鿿]")

SPECIES: dict[str, tuple[str, str]] = {
    "이끼골렘": ("Moss Golem", "苔ゴーレム"),
    "기생벌레": ("Parasite", "寄生虫"),
    "독거미": ("Venom Spider", "毒グモ"),
    "뿔토끼": ("Horned Rabbit", "角ウサギ"),
    "뽔토끼": ("Horned Rabbit", "角ウサギ"),
    "전령새": ("Messenger Bird", "伝令鳥"),
    "포식새": ("Raptor", "猛禽"),
    "깃털새": ("Featherbird", "羽鳥"),
    "하이에나": ("Hyena", "ハイエナ"),
    "살쾡이": ("Wildcat", "ヤマネコ"),
    "살쾋이": ("Wildcat", "ヤマネコ"),
    "사냥개": ("Hound", "猟犬"),
    "스크리커": ("Shrieker", "シュリーカー"),
    "설치류": ("Rodent", "齧歯類"),
    "도롱뇽": ("Salamander", "サンショウウオ"),
    "두꺼비": ("Toad", "ヒキガエル"),
    "물고기": ("Fish", "魚"),
    "거미": ("Spider", "グモ"),
    "들쥐": ("Mouse", "ネズミ"),
    "왕쥐": ("Giant Rat", "オオネズミ"),
    "나방": ("Moth", "ガ"),
    "까마귀": ("Crow", "カラス"),
    "토끼": ("Rabbit", "ウサギ"),
    "두더지": ("Mole", "モグラ"),
    "도마뱀": ("Lizard", "トカゲ"),
    "박쥐": ("Bat", "コウモリ"),
    "여우": ("Fox", "キツネ"),
    "딱정벌레": ("Beetle", "甲虫"),
    "늑대": ("Wolf", "オオカミ"),
    "들개": ("Hound", "野犬"),
    "좀": ("Silverfish", "シミ"),
    "물뱀": ("Water Snake", "ミズヘビ"),
    "부엉이": ("Owl", "フクロウ"),
    "산양": ("Mountain Goat", "ヤギ"),
    "슬라임": ("Slime", "スライム"),
    "거북": ("Turtle", "カメ"),
    "사슴": ("Deer", "シカ"),
    "피라미": ("Minnow", "ハヤ"),
    "벌레": ("Bug", "虫"),
    "새": ("Bird", "鳥"),
    "게": ("Crab", "カニ"),
}

JAPANESE_SPECIES_ALIASES: dict[str, tuple[str, ...]] = {
    "거미": ("グモ", "クモ"),
}


SYSTEM_PROMPT = """You are a senior native English and Japanese game localization editor.
Translate standalone Korean dark-fantasy enemy names as compact commercial-RPG names.
The required species is immutable: English must end with the exact required_en words and
Japanese must end with the exact required_ja characters. Translate only the prefix; do not turn
the name into a sentence, explanation, or invented lore. Use English Title Case. Japanese must be
natural; use の where it makes a multiword modifier idiomatic, and contain no Korean.

Fixed world glossary: 군패=Military Tag/軍牌; 새벽종=Dawn Bell/暁の鐘;
귀환종=Return Bell/帰還の鐘; 금 간 종=Cracked Bell/ひび割れた鐘;
종=Bell/鐘 unless it is inside 종이; 종이=Paper/紙; 창=Spear/槍, never window;
부러진 창=Broken Spear/折れた槍; 감옥빛=Prison Light/牢獄の光;
열 번째 장=Tenth Chapter/第十章; 새벽종쐐기=Dawn-Bell Wedge/暁の鐘の楔;
재=Ash/灰; 재꽃=Ash Blossom/灰の花; 씨앗=Seed/種; 편지=Letter/手紙;
우편낭=Mailbag/郵便袋; 명부=Roster/名簿; 장부=Ledger/帳簿;
기록=Record/記録; 구조=Rescue/救助; 수레=Cart/荷車; 뿔토끼=Horned Rabbit/角ウサギ;
유리=Glass/ガラス; 백야=White Night/白夜; 미궁=Labyrinth/迷宮;
빛=Light/光; 불씨=Ember/残り火; 껑질 and 껍질=Shell/殻;
봄문=Spring Gate/春の門; 종가루=Bell Dust/鐘の粉; 표지석=Marker Stone/標石;
유리뿌리=Glass Root/ガラスの根; 재 갑옷=Ash Armor/灰の鎧; 재무리=Ash Swarm/灰の群れ.

Return JSONL only: {\"id\":1,\"en\":\"...\",\"ja\":\"...\"}."""


# Human-reviewed compounds whose coined Korean cannot be translated reliably by token inference.
EXACT_REVIEWS: dict[str, dict[str, str]] = {
    "감옥빛 딱정벌레": {"en": "Prison-Light Beetle", "ja": "牢獄の光の甲虫"},
    "검댕 뽔토끼": {"en": "Soot Horned Rabbit", "ja": "煤けた角ウサギ"},
    "검댕 뿔토끼": {"en": "Soot Horned Rabbit", "ja": "煤けた角ウサギ"},
    "국경 뿔토끼": {"en": "Border Horned Rabbit", "ja": "国境の角ウサギ"},
    "거울길 거미": {"en": "Mirror-Path Spider", "ja": "鏡の道のクモ"},
    "거울우물 도롱뇽": {"en": "Mirror-Well Salamander", "ja": "鏡の井戸のサンショウウオ"},
    "검은먹 도마뱀": {"en": "Black-Ink Lizard", "ja": "黒墨のトカゲ"},
    "고서 딱정벌레": {"en": "Ancient-Tome Beetle", "ja": "古書の甲虫"},
    "구조신호 나방": {"en": "Rescue-Signal Moth", "ja": "救難信号のガ"},
    "국경 너머 여우": {"en": "Beyond-the-Border Fox", "ja": "国境の彼方のキツネ"},
    "군패자국 딱정벌레": {"en": "Military-Tag Trace Beetle", "ja": "軍牌の跡の甲虫"},
    "균형추 딱정벌레": {"en": "Counterweight Beetle", "ja": "釣合い重りの甲虫"},
    "기록끈 딱정벌레": {"en": "Record-Twine Beetle", "ja": "記録紐の甲虫"},
    "기억표본 게": {"en": "Memory-Specimen Crab", "ja": "記憶標本のカニ"},
    "길지도 까마귀": {"en": "Roadmap Crow", "ja": "道案内図のカラス"},
    "깨진잎 도마뱀": {"en": "Torn-Leaf Lizard", "ja": "破れ葉のトカゲ"},
    "다리 밑 여우": {"en": "Under-the-Bridge Fox", "ja": "橋の下のキツネ"},
    "돌아온발 박쥐": {"en": "Returning-Footstep Bat", "ja": "戻り足のコウモリ"},
    "라움 골목여우": {"en": "Raum's Alley Fox", "ja": "ラウムの路地のキツネ"},
    "목소리 거미": {"en": "Voice Spider", "ja": "声のクモ"},
    "명부먹는 딱정벌레": {"en": "Roster-Eating Beetle", "ja": "名簿喰いの甲虫"},
    "묘지지도 여우": {"en": "Cemetery-Map Fox", "ja": "墓地図のキツネ"},
    "묘표 딱정벌레": {"en": "Gravemarker Beetle", "ja": "墓標の甲虫"},
    "무명기록 여우": {"en": "Nameless-Record Fox", "ja": "無名記録のキツネ"},
    "무별계단 들쥐": {"en": "Starless-Stair Mouse", "ja": "星なき階段のネズミ"},
    "명부밖 들쥐": {"en": "Outside-the-Roster Mouse", "ja": "名簿外のネズミ"},
    "물레 나방": {"en": "Waterwheel Moth", "ja": "水車のガ"},
    "마력 뿔토끼": {"en": "Arcane Horned Rabbit", "ja": "魔力の角ウサギ"},
    "물먹은책 거미": {"en": "Waterlogged-Book Spider", "ja": "水浸しの本のクモ"},
    "바퀴자국 거미": {"en": "Wheel-Track Spider", "ja": "車輪跡のクモ"},
    "발자국모사 들쥐": {"en": "Footprint-Mimic Mouse", "ja": "足跡まねのネズミ"},
    "보호막 뿔토끼": {"en": "Barrier Horned Rabbit", "ja": "結界の角ウサギ"},
    "봉인문 딱정벌레": {"en": "Sealed-Gate Beetle", "ja": "封印門の甲虫"},
    "봉인열쇠 도마뱀": {"en": "Seal-Key Lizard", "ja": "封印の鍵のトカゲ"},
    "봉인 좀": {"en": "Sealed Silverfish", "ja": "封印のシミ"},
    "봉투 뿔토끼": {"en": "Envelope Horned Rabbit", "ja": "封筒の角ウサギ"},
    "불꽃종 까마귀": {"en": "Flame-Bell Crow", "ja": "炎の鐘のカラス"},
    "불씨뿌리 거미": {"en": "Ember-Root Spider", "ja": "残り火の根のクモ"},
    "비친얼굴 나방": {"en": "Reflected-Face Moth", "ja": "映り顔のガ"},
    "빛금 거미": {"en": "Light-Fissure Spider", "ja": "光の亀裂のクモ"},
    "빛먹은 나방": {"en": "Light-Eating Moth", "ja": "光喰いのガ"},
    "빈 병영 거미": {"en": "Empty-Barracks Spider", "ja": "無人兵舎のクモ"},
    "빈 진지 여우": {"en": "Empty-Encampment Fox", "ja": "無人陣地のキツネ"},
    "뿌리가시 여우": {"en": "Root-Thorn Fox", "ja": "根棘のキツネ"},
    "서가수면 나방": {"en": "Bookshelf-Surface Moth", "ja": "書架の表面のガ"},
    "서리기어 딱정벌레": {"en": "Frost-Gear Beetle", "ja": "霜歯車の甲虫"},
    "수레바퀴 거미": {"en": "Wagon-Wheel Spider", "ja": "荷車の車輪のクモ"},
    "수로벽 들쥐": {"en": "Waterway-Wall Mouse", "ja": "水路壁のネズミ"},
    "수문 거미": {"en": "Sluice Spider", "ja": "水門のクモ"},
    "수문거울 여우": {"en": "Sluice-Mirror Fox", "ja": "水門鏡のキツネ"},
    "침묵 뿔토끼": {"en": "Silent Horned Rabbit", "ja": "沈黙の角ウサギ"},
    "신호불 딱정벌레": {"en": "Signal-Flame Beetle", "ja": "信号火の甲虫"},
    "순찰 명부 좀": {"en": "Patrol-Roster Silverfish", "ja": "巡回名簿のシミ"},
    "열 번째 장 까마귀": {"en": "Tenth-Chapter Crow", "ja": "第十章のカラス"},
    "열여덟울림 박쥐": {"en": "Eighteenth-Echo Bat", "ja": "第十八の残響のコウモリ"},
    "얼음갑옷 게": {"en": "Ice-Armor Crab", "ja": "氷の鎧のカニ"},
    "역인 독거미": {"en": "Reverse-Seal Venom Spider", "ja": "逆印の毒グモ"},
    "유리계곡 도마뱀": {"en": "Glass-Valley Lizard", "ja": "ガラスの谷のトカゲ"},
    "유리 잎 딱정벌레": {"en": "Glass-Leaf Beetle", "ja": "ガラス葉の甲虫"},
    "유리나선 거미": {"en": "Glass-Spiral Spider", "ja": "ガラスの螺旋のクモ"},
    "유리답장 거미": {"en": "Glass-Reply Spider", "ja": "ガラスの返書のクモ"},
    "유리전사 들쥐": {"en": "Glass-Warrior Mouse", "ja": "ガラスの戦士のネズミ"},
    "유리탑 까마귀": {"en": "Glass-Tower Crow", "ja": "ガラス塔のカラス"},
    "유리풀 거미": {"en": "Glass-Grass Spider", "ja": "ガラス草のクモ"},
    "유리뿌리 도마뱀": {"en": "Glass-Root Lizard", "ja": "ガラスの根のトカゲ"},
    "유리뿌리 딱정벌레": {"en": "Glass-Root Beetle", "ja": "ガラスの根の甲虫"},
    "이름갑옷 여우": {"en": "Name-Armor Fox", "ja": "名前の鎧のキツネ"},
    "이름거울 박쥐": {"en": "Name-Mirror Bat", "ja": "名前の鏡のコウモリ"},
    "이름장작 왕쥐": {"en": "Name-Kindling Giant Rat", "ja": "名前の薪のオオネズミ"},
    "잉걸잎 도마뱀": {"en": "Ember-Leaf Lizard", "ja": "熾火の葉のトカゲ"},
    "잠긴인장 딱정벌레": {"en": "Locked-Seal Beetle", "ja": "閉ざされた印章の甲虫"},
    "잠긴표식 딱정벌레": {"en": "Locked-Mark Beetle", "ja": "閉ざされた標の甲虫"},
    "잠긴장부 좀": {"en": "Locked-Ledger Silverfish", "ja": "閉ざされた帳簿のシミ"},
    "재 갑옷 딱정벌레": {"en": "Ash-Armor Beetle", "ja": "灰の鎧の甲虫"},
    "재 갑옷 왕쥐": {"en": "Ash-Armor Giant Rat", "ja": "灰の鎧のオオネズミ"},
    "재무리 도롱뇽": {"en": "Ash-Swarm Salamander", "ja": "灰の群れのサンショウウオ"},
    "재밭 여우": {"en": "Ash-Field Fox", "ja": "灰畑のキツネ"},
    "재 속의 종이 새": {"en": "Paper Bird in the Ash", "ja": "灰の中の紙の鳥"},
    "조작 문서 나방": {"en": "Forged-Document Moth", "ja": "偽造文書のガ"},
    "지도칼 까마귀": {"en": "Map-Blade Crow", "ja": "地図刃のカラス"},
    "지워진층 도마뱀": {"en": "Erased-Layer Lizard", "ja": "消された階層のトカゲ"},
    "지하물길 박쥐": {"en": "Underground-Waterway Bat", "ja": "地下水路のコウモリ"},
    "지하음 까마귀": {"en": "Underground-Sound Crow", "ja": "地下音のカラス"},
    "종틀 박쥐": {"en": "Bell-Frame Bat", "ja": "鐘枠のコウモリ"},
    "종탑 도마뱀": {"en": "Bell-Tower Lizard", "ja": "鐘楼のトカゲ"},
    "종혀 피라미": {"en": "Bell-Clapper Minnow", "ja": "鐘舌のハヤ"},
    "주문 박쥐": {"en": "Spell Bat", "ja": "呪文のコウモリ"},
    "천장문 까마귀": {"en": "Ceiling-Gate Crow", "ja": "天井門のカラス"},
    "탄나무 까마귀": {"en": "Charred-Tree Crow", "ja": "炭化木のカラス"},
    "역류둥지 게": {"en": "Backflow-Nest Crab", "ja": "逆流の巣のカニ"},
    "젖은묘표 좀": {"en": "Wet-Gravemarker Silverfish", "ja": "濡れた墓標のシミ"},
    "해동수 딱정벌레": {"en": "Thawwater Beetle", "ja": "雪解け水の甲虫"},
    "화살촉 딱정벌레": {"en": "Arrowhead Beetle", "ja": "鏃の甲虫"},
    "편지 테두리벌레": {"en": "Letter-Edge Bug", "ja": "手紙の縁虫"},
}


def decode(value: str) -> str:
    return base64.b64decode(value).decode("utf-8")


def load_catalog(path: Path) -> list[tuple[str, str, str]]:
    rows: list[tuple[str, str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        row_type, source, target = line.split("\t")
        rows.append((row_type, decode(source), decode(target)))
    return rows


def species_for(source: str) -> tuple[str, str, str] | None:
    # Prefer the longest authored species so “뿔토끼” is not reduced to “토끼”.
    for korean in sorted(SPECIES, key=len, reverse=True):
        english, japanese = SPECIES[korean]
        if korean in {"좀", "새", "게"} and not source.endswith(" " + korean):
            continue
        if source.endswith(korean):
            return korean, english, japanese
    return None


def needs_review(source: str, english: str, japanese: str) -> bool:
    species = species_for(source)
    if species is None:
        return False
    korean_species, required_en, required_ja = species
    allowed_japanese = JAPANESE_SPECIES_ALIASES.get(korean_species, (required_ja,))
    return not english.casefold().endswith(required_en.casefold()) or not japanese.endswith(
        allowed_japanese,
    )


def parse_jsonl(raw: str) -> dict[int, dict[str, str]]:
    results: dict[int, dict[str, str]] = {}
    for line in raw.splitlines():
        try:
            value = json.loads(line.strip())
        except json.JSONDecodeError:
            continue
        if not isinstance(value, dict) or not isinstance(value.get("id"), int):
            continue
        if isinstance(value.get("en"), str) and isinstance(value.get("ja"), str):
            results[value["id"]] = {
                "en": value["en"].strip(),
                "ja": value["ja"].strip(),
            }
    return results


def validate(source: str, targets: dict[str, str]) -> str | None:
    species = species_for(source)
    if species is None:
        return "missing species rule"
    korean_species, required_en, required_ja = species
    english = targets.get("en", "")
    japanese = targets.get("ja", "")
    if not english.casefold().endswith(required_en.casefold()):
        return f"English must end with {required_en!r}"
    allowed_japanese = JAPANESE_SPECIES_ALIASES.get(korean_species, (required_ja,))
    if not japanese.endswith(allowed_japanese):
        return f"Japanese must end with {required_ja!r}"
    if KOREAN.search(english) or KOREAN.search(japanese):
        return "Korean remains"
    if JAPANESE.search(english):
        return "Japanese remains in English"
    if len(english) > 80 or len(japanese) > 50:
        return "runaway name"
    return None


def checkpoint(path: Path, values: dict[str, dict[str, str]]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(values, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def build_prompt(batch: list[str]) -> str:
    items = []
    for index, source in enumerate(batch, start=1):
        species = species_for(source)
        assert species is not None
        _, required_en, required_ja = species
        items.append(
            json.dumps(
                {
                    "id": index,
                    "ko": source,
                    "required_en": required_en,
                    "required_ja": required_ja,
                },
                ensure_ascii=False,
            ),
        )
    return "Translate every row and verify the required endings twice.\n" + "\n".join(items)


def generate_batch(model: Any, processor: Any, sources: list[str]) -> dict[int, dict[str, str]]:
    from mlx_vlm import generate
    from mlx_vlm.prompt_utils import apply_chat_template

    prompt = apply_chat_template(
        processor,
        model.config,
        [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(sources)},
        ],
        num_images=0,
        enable_thinking=False,
    )
    result = generate(
        model,
        processor,
        prompt,
        max_tokens=max(512, len(sources) * 50),
        temperature=0.0,
        enable_thinking=False,
        verbose=False,
    )
    return parse_jsonl(result.text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--model", default="mlx-community/Qwen3.5-9B-MLX-4bit")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    english_rows = load_catalog(args.project / "app/src/simple/res/raw/localization_en.tsv")
    japanese_rows = load_catalog(args.project / "app/src/simple/res/raw/localization_ja.tsv")
    japanese_by_source = {source: target for _, source, target in japanese_rows}
    sources = [
        source
        for _, source, english in english_rows
        if needs_review(source, english, japanese_by_source[source])
    ]
    if args.limit is not None:
        sources = sources[: args.limit]

    reviewed: dict[str, dict[str, str]] = {}
    if args.checkpoint.exists():
        reviewed = json.loads(args.checkpoint.read_text(encoding="utf-8"))
    reviewed.update(EXACT_REVIEWS)
    pending = [source for source in sources if source not in reviewed]
    if pending:
        from mlx_vlm import load

        model, processor = load(args.model, trust_remote_code=True)
        for offset in range(0, len(pending), args.batch_size):
            batch = pending[offset : offset + args.batch_size]
            generated = generate_batch(model, processor, batch)
            for index, source in enumerate(batch, start=1):
                targets = generated.get(index)
                problem = validate(source, targets) if targets is not None else "missing"
                if problem:
                    retry = generate_batch(model, processor, [source])
                    targets = retry.get(1)
                if targets is None:
                    raise RuntimeError(f"Model omitted {source!r} twice")
                problem = validate(source, targets)
                if problem:
                    raise RuntimeError(f"Invalid review for {source!r}: {problem}: {targets}")
                reviewed[source] = targets
            checkpoint(args.checkpoint, reviewed)
            print(f"reviewed {min(offset + len(batch), len(pending))}/{len(pending)}", flush=True)

    problems = [
        f"{source!r}: {problem}"
        for source in sources
        if (problem := validate(source, reviewed.get(source, {})))
    ]
    if problems:
        raise RuntimeError("Creature-name audit failed:\n" + "\n".join(problems[:30]))
    print(f"audit passed for {len(sources)} creature names", flush=True)

    if args.apply:
        overrides_path = args.project / "tools/localization/review_overrides.json"
        overrides = json.loads(overrides_path.read_text(encoding="utf-8"))
        overrides.update({source: reviewed[source] for source in sources})
        overrides.update(EXACT_REVIEWS)
        overrides_path.write_text(
            json.dumps(overrides, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print("reviewed creature names merged into review_overrides.json", flush=True)


if __name__ == "__main__":
    main()
