const CLASS_ORDER = ["WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN"];
const ELEMENT_COLORS = {
  PHYSICAL: ["#f6f0e7", "rgba(112,119,132,.22)"], FIRE: ["#ffb45f", "rgba(106,23,21,.26)"],
  ICE: ["#b9edff", "rgba(18,54,82,.24)"], LIGHTNING: ["#ddf7ff", "rgba(24,40,88,.25)"],
  ARCANE: ["#d3b6ff", "rgba(36,58,120,.25)"], DARK: ["#e0a7ff", "rgba(25,12,36,.25)"],
  POISON: ["#b8e879", "rgba(16,43,25,.24)"], WIND: ["#a7e9df", "rgba(18,59,58,.22)"],
  EARTH: ["#e2be79", "rgba(43,28,18,.24)"], HOLY: ["#fff0ad", "rgba(59,44,16,.22)"],
  COSMIC: ["#d9d8ff", "rgba(23,24,61,.27)"],
};
const ROLE_TOGGLE = {
  SECONDARY: "secondary", PRIMARY: "primary", CONTACT: "impact", DEBRIS: "debris",
  RESIDUAL: "residual", FINISHER_RING: "residual", FINISHER_ECHO: "residual", WARRIOR_ACCENT: "impact",
};
const ROLE_FOCUS_OPTIONS = [
  ["ALL", "전체"], ["SECONDARY", "예고"], ["PRIMARY", "본체"],
  ["CONTACT", "접촉"], ["DEBRIS", "파편"], ["RESIDUAL", "잔광"],
  ["FINISHER_RING", "피니셔"], ["FINISHER_ECHO", "에코"],
];

const BLADE_SLASH_ID = "warrior_t01_c01";
const SHIELD_BREAK_ID = "warrior_t02_c03";
const SHATTER_STRIKE_ID = "warrior_t03_c02";
const EARTH_CLEAVE_ID = "warrior_t04_c03";
const CROSS_SLASH_ID = "warrior_t05_c02";
const STORM_SLASH_ID = "warrior_t06_c05";
const EARTH_SHATTER_ID = "warrior_t10_c01";
const DRAGONSLAYER_ID = "warrior_t14_c03";
const HEAVEN_ERASING_FLASH_ID = "warrior_t15_c01";
const BOUNDLESS_FLASH_ID = "warrior_t16_c05";
const DOOM_SWORD_ID = "warrior_t17_c01";
const HEAVEN_EARTH_DIVIDE_ID = "warrior_t18_c02";
const WORLD_OCEAN_SEVER_ID = "warrior_t19_c02";
const FINAL_BLOW_ID = "warrior_t20_c01";
const APEX_800MS_WARRIOR_IDS = Object.freeze([
  DOOM_SWORD_ID, HEAVEN_EARTH_DIVIDE_ID, WORLD_OCEAN_SEVER_ID, FINAL_BLOW_ID,
]);
const ROGUE_IDENTITY_REWORK_IDS = Object.freeze([
  "rogue_t01_c01", "rogue_t07_c01", "rogue_t09_c02", "rogue_t10_c04", "rogue_t12_c05",
]);
const ROGUE_FOUR_DARK_REWORK_IDS = Object.freeze([
  "rogue_t01_c01", "rogue_t09_c02", "rogue_t10_c04", "rogue_t20_c05",
]);
const AFTERIMAGE_VAULT_ID = "rogue_t08_c02";
const FULL_RUNTIME_VFX_OPACITY = 1;
const INTUITIVE_WARRIOR_SKILL_IDS = Object.freeze([
  "warrior_t07_c01", "warrior_t08_c02", "warrior_t09_c03", "warrior_t10_c01",
  "warrior_t11_c02", "warrior_t12_c04", "warrior_t13_c03", "warrior_t14_c03",
  "warrior_t15_c01", "warrior_t16_c05", "warrior_t17_c01", "warrior_t18_c02",
  "warrior_t19_c02", "warrior_t20_c01",
]);
const SIGNATURE_ROGUE_SKILLS = Object.freeze([
  { id: "rogue_t01_c01", name: "빠른 찌르기", action: "RAPID_THREE_POINT_STILETTO_THRUST", flow: "UPPER_LOWER_CENTER_SHORT_PUNCTURE", label: "PIERCE · THREE_POINT_STILETTO", hits: 3, prep: "첫 비수 검압 응축", motion: "상단→하단→중앙 단거리 찌르기", contact: "두 관통점 잔류", impact: "중앙 심부 관통", hold: "세 관통흔 감쇠" },
  { id: "rogue_t02_c02", name: "암습", action: "REAR_BLIND_AMBUSH", flow: "SHADOW_REAR_CUT", label: "AMBUSH · REAR_CUT", hits: 1, prep: "그림자 잠입", motion: "후방 이동", contact: "등 뒤 절개", impact: "암습 타격", hold: "그림자 소실" },
  { id: "rogue_t03_c03", name: "맹독 쌍침", action: "TWIN_VENOM_NEEDLES", flow: "STAGGERED_DUAL_PUNCTURE", label: "VENOM · TWIN_NEEDLES", hits: 2, prep: "쌍침 예고", motion: "두 경로 수렴", contact: "맹독 관통", impact: "쌍침 독점", hold: "독편 감쇠" },
  { id: "rogue_t04_c04", name: "칼날 덫", action: "ASYMMETRIC_BLADE_TRAP", flow: "FOUR_BLADE_INWARD_SNAP", label: "TRAP · FOUR_BLADES", hits: 3, prep: "칼날 은폐", motion: "네 칼날 기동", contact: "덫 폐쇄", impact: "칼날 포획", hold: "칼날 반동" },
  { id: "rogue_t05_c01", name: "초승달 단검", action: "CRESCENT_DAGGER_CUT", flow: "OPEN_CRESCENT_RISING_CUT", label: "DAGGER · CRESCENT_CUT", hits: 3, prep: "단검광 예고", motion: "초승달 궤적", contact: "곡선 절개", impact: "초승달 단검", hold: "곡광 감쇠" },
  { id: "rogue_t06_c05", name: "사각 일격", action: "BLIND_SPOT_STRIKE", flow: "REVERSE_DIAGONAL_SNAP", label: "AMBUSH · BLIND_SPOT", hits: 1, prep: "사각 섬광", motion: "역사선 접근", contact: "무방비 접촉", impact: "사각 일격", hold: "절개선 소실" },
  { id: "rogue_t07_c01", name: "네 갈래 비수", action: "FOUR_BRANCH_DAGGER_VOLLEY", flow: "LEFT_ORIGIN_TO_FOUR_OFFSET_POINTS", label: "DAGGER · FOUR_BRANCHES", hits: 4, prep: "분기점 점화", motion: "네 갈래 곡선 투척", contact: "네 편심 표적 관통", impact: "네 갈래 비수", hold: "네 비수 잔광" },
  { id: "rogue_t08_c02", name: "잔상 도약", action: "AFTERIMAGE_VAULT_CUT", flow: "VAULT_TO_DESCENDING_SLASH", label: "AMBUSH · AFTERIMAGE_VAULT", hits: 1, prep: "도약 잔상", motion: "곡선 도약", contact: "하강 절개", impact: "잔상 착지", hold: "잔상 감쇠" },
  { id: "rogue_t09_c02", name: "월하 암습", action: "MOON_VEIL_REAR_DOUBLE_AMBUSH", flow: "RIGHT_REAR_SHADOW_TO_STAGGERED_DOUBLE_CUT", label: "AMBUSH · MOON_VEIL_REAR", hits: 2, prep: "월광 장막 하강", motion: "우측 후방 첫 절개", contact: "월영 재은폐", impact: "두 번째 후방 절개", hold: "월광 장막 소실" },
  { id: "rogue_t10_c04", name: "칼날 감옥", action: "IRREGULAR_BLADE_BAR_PRISON", flow: "STAGGERED_BARS_TO_THREE_AXIS_SNAP_CLOSE", label: "PRISON · IRREGULAR_BLADE_BARS", hits: 3, prep: "첫 칼날봉 투입", motion: "비대칭 감옥 결속", contact: "세 축 폐쇄 직전", impact: "칼날 감옥 폐쇄", hold: "칼날봉 반동" },
  { id: "rogue_t11_c04", name: "은사 난무", action: "SILVER_THREAD_FLURRY", flow: "FOUR_ROAMING_WIRE_CUTS", label: "WIRE · SILVER_FLURRY", hits: 4, prep: "은사 세 가닥", motion: "사점 난무", contact: "은사 팽팽", impact: "은사 종결", hold: "섬유 감쇠" },
  { id: "rogue_t12_c05", name: "침묵의 처형", action: "MOON_SHADOW_REVERSE_BLADE_EXECUTION", flow: "MOON_VEIL_REVERSE_BLADE_TO_SINGLE_SILENT_FINISH", label: "EXECUTE · MOON_SHADOW_REVERSE_BLADE", hits: 1, prep: "월영 장막 결속", motion: "역날 처형 궤적 전개", contact: "월영 검흔 수렴", impact: "침묵의 역날 처형", hold: "분리된 월영 잔류" },
  { id: "rogue_t13_c03", name: "독왕의 송곳니", action: "VENOM_KING_FANG", flow: "ROYAL_FANG_PUNCTURE", label: "VENOM · KING_FANG", hits: 2, prep: "독아 형성", motion: "송곳니 돌진", contact: "독린 관통", impact: "독왕 파열", hold: "독편 감쇠" },
  { id: "rogue_t14_c04", name: "천라지망", action: "INESCAPABLE_DESCENDING_NET", flow: "SKEWED_NET_CONSTRICTION", label: "WIRE · HEAVEN_EARTH_NET", hits: 4, prep: "금사 강하", motion: "비대칭 그물", contact: "그물 포획", impact: "천라지망 수축", hold: "금사 분해" },
  { id: "rogue_t15_c04", name: "월광 철사", action: "MOONLIGHT_RAZOR_WIRE", flow: "HOOK_AND_SNAP_CUT", label: "WIRE · MOONLIGHT_SNAP", hits: 2, prep: "월광선 예고", motion: "철사 갈고리", contact: "철사 장력", impact: "월광 절단", hold: "은사 파편" },
  { id: "rogue_t16_c04", name: "검은 실 감옥", action: "BLACK_THREAD_PRISON", flow: "FIVE_SIDE_IRREGULAR_PRISON", label: "PRISON · BLACK_THREAD", hits: 3, prep: "검은 실 예고", motion: "오각 감옥 형성", contact: "감옥 수축", impact: "검은 실 절단", hold: "실 반동" },
  { id: "rogue_t17_c05", name: "왕의 숨통", action: "SOVEREIGN_THROAT_SEVER", flow: "THROAT_HEIGHT_HORIZONTAL_CUT", label: "EXECUTE · SOVEREIGN_SEVER", hits: 1, prep: "왕금선 압축", motion: "수평 처형", contact: "압력판 절개", impact: "숨통 분리", hold: "금선 소실" },
  { id: "rogue_t18_c01", name: "종말의 단검무", action: "APOCALYPSE_DAGGER_DANCE", flow: "SIX_DAGGER_ROAMING_STORM", label: "DAGGER · APOCALYPSE_DANCE", hits: 4, prep: "여섯 단검 예고", motion: "다층 단검무", contact: "종말 수렴", impact: "최종 단검 폭우", hold: "단검 낙하" },
  { id: "rogue_t19_c02", name: "그림자 세계 절단", action: "SHADOW_WORLD_SEVER", flow: "TWIN_PLANE_DIAGONAL_DIVIDE", label: "SHADOW · WORLD_SEVER", hits: 3, prep: "그림자면 전개", motion: "세계 사선", contact: "양면 절단", impact: "그림자 세계 분리", hold: "암면 붕괴" },
  { id: "rogue_t20_c05", name: "죽음의 한 점", action: "DEATH_ASYMMETRIC_TOTAL_PINPOINT", flow: "SEVEN_DARK_VECTORS_TO_SINGLE_OFFSET_VOID_POINT", label: "EXECUTE · DEATH_SINGLE_POINT", hits: 1, prep: "일곱 암살 검압 접근", motion: "편심 극점 압축", contact: "한 점 직전", impact: "죽음의 한 점", hold: "적흑 극점 소실" },
]);
const SIGNATURE_ROGUE_IDS = new Set(SIGNATURE_ROGUE_SKILLS.map(({ id }) => id));
const SIGNATURE_ROGUE_NAMES = new Map(SIGNATURE_ROGUE_SKILLS.map(({ id, name }) => [id, name]));
const SIGNATURE_ROGUE_META = new Map(SIGNATURE_ROGUE_SKILLS.map((skill) => [skill.id, skill]));
const SIGNATURE_REMAINING_NAMES = new Map();
const SIGNATURE_REMAINING_META = new Map();
const SIGNATURE_WARRIOR_SPRITES = new Map([
  ["warrior_t01_c01", "custom-assets/warrior-t01-blade-slash-snap-impact-revision/warrior_t01_c01.png"],
  [SHIELD_BREAK_ID, "custom-assets/warrior-t02-steel-slice-attached-image/warrior_t02_c03.png?v=warrior-t02-steel-slice-attached-image-v1"],
  [SHATTER_STRIKE_ID, "custom-assets/warrior-t03-shatter-strike-attached-image/warrior_t03_c02.png?v=warrior-t03-shatter-strike-attached-image-v1"],
  [EARTH_CLEAVE_ID, "custom-assets/warrior-t04-earth-cleave-slash-then-rift-imagegen-v1/warrior_t04_c03.png?v=earth-cleave-slash-then-rift-imagegen-v1"],
  [CROSS_SLASH_ID, "custom-assets/warrior-t05-cross-slash-horizontal-vertical-afterimage-imagegen-v1/warrior_t05_c02.png?v=cross-slash-horizontal-vertical-afterimage-v1"],
  [STORM_SLASH_ID, "custom-assets/warrior-t06-storm-slash-six-gale-cascade-imagegen-v1/warrior_t06_c05.png?v=storm-slash-six-gale-cascade-imagegen-v1"],
  ...INTUITIVE_WARRIOR_SKILL_IDS.map((catalogId) => [
    catalogId,
    `custom-assets/warrior-t07-t20-intuitive-skills-imagegen-v1/${catalogId}.png?v=warrior-intuitive-t07-t20-v1`,
  ]),
  [DRAGONSLAYER_ID, "custom-assets/warrior-t14-dragonslayer-attached-image/warrior_t14_c03.png?v=warrior-t14-dragonslayer-attached-image-v1"],
  ...[
    EARTH_SHATTER_ID,
    HEAVEN_ERASING_FLASH_ID,
    BOUNDLESS_FLASH_ID,
    DOOM_SWORD_ID,
    HEAVEN_EARTH_DIVIDE_ID,
    WORLD_OCEAN_SEVER_ID,
    FINAL_BLOW_ID,
  ].map((catalogId) => [
    catalogId,
    `custom-assets/warrior-finalized-vfx/${catalogId}.png?v=warrior-finalized-20260824-r1`,
  ]),
]);
const SIGNATURE_WARRIOR_IDS = new Set([BLADE_SLASH_ID, ...SIGNATURE_WARRIOR_SPRITES.keys()]);
const WEB_REVIEW_SKILL_IDS = new Set([...SIGNATURE_WARRIOR_IDS, ...SIGNATURE_ROGUE_IDS]);
const SIGNATURE_WARRIOR_NAMES = new Map([
  ["warrior_t01_c01", "칼날 베기"],
  ["warrior_t02_c03", "강철 베기"],
  ["warrior_t03_c02", "파쇄격"],
  ["warrior_t04_c03", "대지 가르기"],
  ["warrior_t05_c02", "십자 참격"],
  ["warrior_t06_c05", "폭풍 베기"],
  ["warrior_t07_c01", "철갑 돌진"],
  ["warrior_t08_c02", "전장의 돌격"],
  ["warrior_t09_c03", "회오리 참격"],
  ["warrior_t10_c01", "대지 분쇄"],
  ["warrior_t11_c02", "폭풍검"],
  ["warrior_t12_c04", "섬광 일섬"],
  ["warrior_t13_c03", "무영 연참"],
  ["warrior_t14_c03", "용살검"],
  ["warrior_t15_c01", "멸천 일섬"],
  ["warrior_t16_c05", "무극일섬"],
  ["warrior_t17_c01", "파멸의 검"],
  ["warrior_t18_c02", "천지 가르기"],
  ["warrior_t19_c02", "천하대양단"],
  ["warrior_t20_c01", "최후의 일격"],
]);

const SWORD_MASTER_LATE_IMPACT_META = new Map([
  [EARTH_CLEAVE_ID, { action: "EARTH_CLEAVE", flow: "SLASH_TO_TECTONIC_FAULT", label: "SWORD · SLASH_TO_FAULT", prep: "검광 예고", motion: "공중 사선 베기", contact: "지면 접촉", impact: "대지 파열", hold: "단층 감쇠", detail: "4×4 / 16프레임 · F01-F03 순수 공중 베기 · F04 지면 첫 접촉 · F05-F07 동일 궤적을 따라 균열 확장 · F08 주 단층 분리 · F09 최대 대지 파열/피해 표시 · F10-F14 잔해 감쇠 · F15 유지/875-1350ms 페이드아웃 · F16 빈 소실 셀" }],
  [CROSS_SLASH_ID, { action: "CROSS_SLASH", flow: "HORIZONTAL_THEN_VERTICAL_CROSS_AFTERIMAGE", label: "SWORD · CROSS_AFTERIMAGE", prep: "수평 검광", motion: "가로→세로 베기", contact: "십자 완성", impact: "십자 잔상", hold: "잔상 감쇠", detail: "4×4 / 16프레임 · F01-F04 가로 베기 · F05 수평 잔상/세로 예고 · F06-F07 세로 베기 · F08 주 십자 완성 · F09 최대 십자 잔상/피해 표시 · F10-F14 잔상 감쇠 · F15 유지/875-1350ms 페이드아웃 · F16 빈 소실 셀" }],
  [STORM_SLASH_ID, { action: "STORM_SLASH", flow: "SIX_GALE_STORM_FRONT", label: "WIND · SIX_GALE_STORM", prep: "검풍 예고", motion: "6연속 풍절", contact: "폭풍 전선", impact: "최대 폭풍 베기", hold: "검풍 감쇠", detail: "4×4 / 16프레임 · F01 검풍 예고 · F02-F07 여섯 검풍 순차 누적 · F08 개방형 폭풍 전선 완성 · F09 최대 폭풍 베기/최종 피해 표시 · F10 즉시 약화 · F11-F14 바람 리본 감쇠 · F15 유지/875-1350ms 페이드아웃 · F16 빈 소실 셀" }],
  ["warrior_t07_c01", { action: "IRONCLAD_RAM_CHARGE", flow: "STEEL_WEDGE_BREAKTHROUGH", label: "CHARGE · IRONCLAD_RAM", prep: "철갑 쐐기 형성", motion: "우측 돌진", contact: "장벽 충돌", impact: "철갑 돌파", hold: "철편 감쇠", detail: "4×4 / 16프레임 · F01 불씨 예고 · F02-F08 철갑 쐐기 우측 돌진 · F09 장벽 파쇄/피해 표시 · F10-F15 철편과 속도 잔상 감쇠 · F16 소실 · 1100-1350ms 페이드아웃" }],
  ["warrior_t08_c02", { action: "BATTLEFIELD_SPEARHEAD_RUSH", flow: "TRIPLE_LANE_WARFRONT", label: "CHARGE · BATTLEFRONT", prep: "삼중 선봉 예고", motion: "전장 돌격", contact: "선봉 집결", impact: "전선 돌파", hold: "전선 감쇠", detail: "4×4 / 16프레임 · F01 전장 예고 · F02-F08 적금색 삼중 선봉 돌격 · F09 전선 최대 돌파/피해 표시 · F10-F15 압력 리본 감쇠 · F16 소실 · 1100-1350ms 페이드아웃" }],
  ["warrior_t09_c03", { action: "WHIRLWIND_SLASH", flow: "OPEN_CRESCENT_WHIRL", label: "SLASH · OPEN_WHIRLWIND", prep: "곡선 검광 예고", motion: "개방 회전", contact: "회오리 수렴", impact: "회오리 참격", hold: "곡풍 감쇠", detail: "4×4 / 16프레임 · F01-F03 청백색 초승달 예고 · F04-F08 열린 회오리 검광 확장 · F09 최대 곡풍 참격/피해 표시 · F10-F15 곡선 잔광 감쇠 · F16 소실 · 1100-1350ms 페이드아웃" }],
  [EARTH_SHATTER_ID, { action: "EARTH_SHATTER", flow: "LEGACY_GROUND_BREAK", label: "CRUSH · EARTH_SHATTER", prep: "지층 압력 예고", motion: "기존 암반 융기", contact: "분쇄점 형성", impact: "대지 분쇄", hold: "암편 낙하", detail: "4×4 / 16프레임 · Lv45 기존 연출 확정본 · F01-F08 압력 축적과 암반 융기 · 500ms F09 최대 분쇄/피해 표시 · F10-F14 암편 낙하와 RGB 감쇠(소재 알파 유지)" }],
  ["warrior_t11_c02", { action: "TEMPEST_FORGED_SWORD", flow: "DIAGONAL_STORM_SWORD", label: "SWORD · TEMPEST_FORGED", prep: "폭풍검 응집", motion: "대각 폭풍검", contact: "폭풍 접촉", impact: "폭풍 절개", hold: "검풍 감쇠", detail: "4×4 / 16프레임 · F01-F03 폭풍검 응집 · F04-F08 대각 폭풍검 가속 · F09 최대 폭풍 절개/피해 표시 · F10-F15 검풍과 뇌광 감쇠 · F16 소실 · 1100-1350ms 페이드아웃" }],
  ["warrior_t12_c04", { action: "INSTANT_FLASH_SEVER", flow: "STYLE_MATCHED_FLASH_DRAW", label: "SLASH · GLOSSY_FLASH", prep: "섬광점 응집", motion: "곡광 일섬", contact: "섬광 절개", impact: "찰나 파열", hold: "파편 잔광", detail: "4×4 / 16프레임 · F01-F07 기존 화풍의 은청색 반투명 섬광이 미세하게 굽고 두께를 바꾸며 가속 · F08 불균일 절개 완성 · F09 프리즘 파편 파열/피해 표시 · F10-F15 급감쇠 · F16 소실" }],
  ["warrior_t13_c03", { action: "SHADOWLESS_MULTI_CUT", flow: "STYLE_MATCHED_ROAMING_COMBO", label: "SLASH · ROAMING_GLOW", prep: "무영 잔광", motion: "오점 연참", contact: "여섯째 검흔", impact: "무영 종결", hold: "검흔 감쇠", detail: "4×4 / 16프레임 · F01-F08 각도·길이·깊이가 서로 다른 5개 매끈한 은자색 연참 · F09 여섯째 백색 광핵 종결 베기/피해 표시 · F10-F15 분산 감쇠 · F16 소실" }],
  [DRAGONSLAYER_ID, { action: "DRAGON_HEART_SCALE_EXECUTION", flow: "THREE_FRAME_DRAGONSLAYER_800MS", label: "SWORD · DRAGON_HEART_SEVER", prep: "용심선·용린 갑주 압축", motion: "3프레임 처형 베기", contact: "용린 갑주 관통", impact: "용심선 절단", hold: "절단흔·용린 잔류", detail: "4×4 / 16프레임 · 레벨 65 강도: 흑청색 대형 용린 갑주와 적색 용심선 · F01-F06 용심선을 감싸는 용린 갑주 축적 · F07-F09 최대 3프레임 대각 처형 베기 · 800ms F09 단일 최대 용심선 절단/피해 표시 · F10-F14 동일 절단흔과 대형 용린의 단조 감쇠 · F15 유지/1220-1400ms 페이드아웃 · F16 빈 소실 셀" }],
  [HEAVEN_ERASING_FLASH_ID, { action: "HEAVEN_ERASING_HORIZON_FLASH", flow: "SINGLE_HORIZON_VOID_SEVER", label: "SLASH · HEAVEN_ERASING_HORIZON", prep: "천공층 압축", motion: "단일 일섬 진입", contact: "천공 절단 직전", impact: "천공 소거", hold: "분리층 잔류", detail: "4×4 / 16프레임 · 확정 1안의 거대 천공층이 F01-F06에 압축 · F07-F08 한 번의 절단 궤적 진입 · 800ms F09 유일한 최고 광량 천공 소거/피해 표시 · F10-F14 동일 분리층과 대형 파편 잔류 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }],
  [BOUNDLESS_FLASH_ID, { action: "BOUNDLESS_VOID_DOMAIN_TWELVE_ECHO", flow: "TWELVE_ECHOES_TO_SINGLE_INFINITY_SEVER", label: "SLASH · BOUNDLESS_VOID_DOMAIN", prep: "무극 궤적 전개", motion: "십이 잔향 수렴", contact: "수평 타격 직전", impact: "무극 일섬", hold: "분리 궤적 잔류", detail: "4×4 / 16프레임 · 확정 5안의 비대칭 무극 궤적이 F01-F08에 수렴하며 이 구간에는 밝은 수평 타격선이 없음 · F09에서만 수평 접촉광과 12타 피해가 처음 발생 · F10-F14는 타격선 없이 분리된 궤적과 대형 파편을 원본 불투명도로 유지 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }],
  [FINAL_BLOW_ID, { action: "FINAL_BLOW_FALLING_VOID_GUILLOTINE", flow: "TOP_HEAVY_PRESSURE_TO_SINGLE_VERTICAL_EXECUTION", label: "EXECUTE · FALLING_VOID_GUILLOTINE", prep: "상공 검압 응축", motion: "수직 처형 낙하", contact: "수평 접촉 직전", impact: "최후의 일격", hold: "분리 장막 잔류", detail: "4×4 / 16프레임 · 확정 5안의 무거운 처형 장막이 F01-F08에 상공에서 낙하하며 이 구간에는 밝은 수평 접촉선이 없음 · 800ms F09에서만 접촉광과 유일한 최고 광량 타격/피해가 발생 · F10-F14는 접촉선 없이 분리된 장막과 대형 파편을 원본 불투명도로 유지 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }],
  [DOOM_SWORD_ID, { action: "DOOM_SWORD_LIVING_CURTAIN_REND", flow: "INTACT_DOOM_CURTAIN_TO_FIRST_ABYSS_OPEN", label: "SWORD · LIVING_DOOM_CURTAIN", prep: "파멸 장막 전개", motion: "처형축 압축", contact: "장막 절단 직전", impact: "파멸의 검", hold: "분리 장막 잔류", detail: "4×4 / 16프레임 · 확정 2안의 거대 파멸 장막이 F01-F08에 결속·압축 · 800ms F09에서만 장막이 처음 분리되며 유일한 최고 광량 타격/피해 표시 · F10-F14 동일 절단면과 대형 파편 잔류 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }],
  [HEAVEN_EARTH_DIVIDE_ID, { action: "HEAVEN_EARTH_REGENERATED_WORLD_DIVIDE", flow: "INTACT_WORLD_BODY_TO_SINGLE_VARIANT_FAULT", label: "SLASH · REGENERATED_WORLD_DIVIDE", prep: "천지 세계체 결속", motion: "단일 절단압 전개", contact: "세계체 분리 직전", impact: "천지 가르기", hold: "분리 세계체 잔류", detail: "4×4 / 16프레임 · 확정 1안은 F01-F06에 결합된 천지 세계체를 형성하고 F07-F08에 단일 절단압을 전개 · 800ms F09에서만 세계체가 처음 분리되며 유일한 최고 광량 타격/피해 표시 · F10-F14 동일 분리면과 대형 파편을 원본 불투명도로 유지 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }],
  [WORLD_OCEAN_SEVER_ID, { action: "SOVEREIGN_OCEAN_HORIZONTAL_LATERAL_HALF_SPLIT", flow: "INTACT_OCEAN_TO_UPPER_LEFT_LOWER_RIGHT_SPLIT", label: "SLASH · OCEAN_LATERAL_HALF_SPLIT", prep: "일체형 대양상 결속", motion: "수평 타격압 압축", contact: "수평 분리 직전", impact: "천하대양단", hold: "상좌·하우 대양면 잔류", detail: "4×4 / 16프레임 · 확정 4안의 일체형 대양상이 F01-F08에 형성되며 이 구간에는 밝은 수평 타격선이 없음 · 800ms F09에서만 금빛 수평 타격광과 유일한 최고 광량 타격/피해가 발생 · F10-F14는 접촉선 없이 위쪽 대양면이 왼쪽, 아래쪽 대양면이 오른쪽으로 엇갈려 분리된 상태와 대형 파편을 원본 불투명도로 유지 · F15는 F14를 그대로 유지하며 런타임 페이드 · F16 빈 소실 셀" }]
]);
const SWORD_MASTER_LATE_IMPACT_IDS = new Set(SWORD_MASTER_LATE_IMPACT_META.keys());

const WARRIOR_SPRITES = new Map(SIGNATURE_WARRIOR_SPRITES);
const WARRIOR_READY_SPRITES = new Set(SIGNATURE_WARRIOR_SPRITES.keys());
const WARRIOR_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "CLEAVE", flow: "SOVEREIGN_RIFT_CLEAVE", label: "CLEAVE · SOVEREIGN_RIFT" }],
  ["c02", { action: "CRUSH", flow: "TITAN_SKYFALL_CRUSH", label: "CRUSH · TITAN_SKYFALL" }],
  ["c03", { action: "CHARGE", flow: "CENTER_BREAKTHROUGH", label: "CHARGE · CENTER_BREAKTHROUGH" }],
  ["c04", { action: "RUPTURE", flow: "TECTONIC_REBIRTH", label: "RUPTURE · TECTONIC_REBIRTH" }],
  ["c05", { action: "FLURRY", flow: "CENTERED_BATTLE_TEMPEST", label: "FLURRY · CENTERED_TEMPEST" }],
]);

const INITIAL_ROGUE_VFX_VARIANT = "1";
const ROGUE_FINALIZED_VFX_DIRECTORY = "custom-assets/rogue-finalized-vfx";
const DEFAULT_ROGUE_VFX_SELECTIONS = new Map([
  ["rogue_t01_c01", "1"], ["rogue_t02_c02", "4"], ["rogue_t03_c03", "1"],
  ["rogue_t04_c04", "1"], ["rogue_t05_c01", "1"], ["rogue_t06_c05", "4"],
  ["rogue_t07_c01", "1"], ["rogue_t08_c02", "2"], ["rogue_t09_c02", "1"],
  ["rogue_t10_c04", "1"], ["rogue_t11_c04", "1"], ["rogue_t12_c05", "4"],
  ["rogue_t13_c03", "1"], ["rogue_t14_c04", "1"], ["rogue_t15_c04", "2"],
  ["rogue_t16_c04", "original"], ["rogue_t17_c05", "original"], ["rogue_t18_c01", "original"],
  ["rogue_t19_c02", "original"], ["rogue_t20_c05", "original"],
]);
const INITIAL_ROGUE_VFX_SELECTIONS = new Map(DEFAULT_ROGUE_VFX_SELECTIONS);

function rogueVfxSpritePath(skillId) {
  return `${ROGUE_FINALIZED_VFX_DIRECTORY}/${skillId}.png?v=rogue-finalized-20260825-r6`;
}

const SIGNATURE_ROGUE_SPRITES = new Map(SIGNATURE_ROGUE_SKILLS.map(({ id }) => [
  id,
  rogueVfxSpritePath(id),
]));
const ROGUE_SPRITES = new Map(SIGNATURE_ROGUE_SPRITES);
const ROGUE_READY_SPRITES = new Set(SIGNATURE_ROGUE_SPRITES.keys());
const ROGUE_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "NEEDLE", flow: "PHANTOM_NEEDLE_CROWN", label: "NEEDLE · PHANTOM_CROWN" }],
  ["c02", { action: "AMBUSH", flow: "UMBRAL_AFTERIMAGE_COLLAPSE", label: "AMBUSH · UMBRAL_COLLAPSE" }],
  ["c03", { action: "VENOM", flow: "VENOM_HELIX_BLOOM", label: "VENOM · HELIX_BLOOM" }],
  ["c04", { action: "SNARE", flow: "SILENT_SNARE_CONVERGENCE", label: "SNARE · SILENT_CONVERGENCE" }],
  ["c05", { action: "EXECUTE", flow: "HEARTSEAL_EXECUTION", label: "EXECUTE · HEARTSEAL" }],
]);

const RANGER_SPRITES = new Map();
const RANGER_READY_SPRITES = new Set([]);
const RANGER_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "SNIPE", flow: "HORIZON_PINPOINT_RUPTURE", label: "SNIPE · HORIZON_PINPOINT" }],
  ["c02", { action: "VOLLEY", flow: "SKYBURST_VOLLEY_VAULT", label: "VOLLEY · SKYBURST_VAULT" }],
  ["c03", { action: "TEMPEST", flow: "TEMPEST_BOWSTRING_SINGULARITY", label: "TEMPEST · STRING_SINGULARITY" }],
  ["c04", { action: "HUNT", flow: "PREDATOR_SNARE_DOMAIN", label: "HUNT · PREDATOR_DOMAIN" }],
  ["c05", { action: "CONSTELLATION", flow: "LUNAR_CONSTELLATION_PIERCER", label: "CONSTELLATION · LUNAR_PIERCER" }],
]);

const MAGE_SPRITES = new Map();
const MAGE_READY_SPRITES = new Set([]);
const MAGE_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "PYRE", flow: "SOLAR_FORGE_DETONATION", label: "PYRE · SOLAR_FORGE" }],
  ["c02", { action: "CRYOSTASIS", flow: "CRYOSTASIS_CATHEDRAL_SHATTER", label: "CRYOSTASIS · CATHEDRAL_SHATTER" }],
  ["c03", { action: "THUNDER", flow: "THUNDER_CIRCUIT_JUDGMENT", label: "THUNDER · CIRCUIT_JUDGMENT" }],
  ["c04", { action: "ARCANE", flow: "ARCANE_GEOMETRY_COLLAPSE", label: "ARCANE · GEOMETRY_COLLAPSE" }],
  ["c05", { action: "COSMIC", flow: "COSMIC_ORBITAL_CATACLYSM", label: "COSMIC · ORBITAL_CATACLYSM" }],
]);

const CLERIC_SPRITES = new Map();
const CLERIC_READY_SPRITES = new Set([]);
const CLERIC_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "REVELATION", flow: "DAWN_REVELATION_BEAM", label: "REVELATION · DAWN_BEAM" }],
  ["c02", { action: "VERDICT", flow: "CELESTIAL_VERDICT_DESCENT", label: "VERDICT · CELESTIAL_DESCENT" }],
  ["c03", { action: "EXORCISE", flow: "EXORCISM_SEAL_CONSUMPTION", label: "EXORCISE · SEAL_CONSUMPTION" }],
  ["c04", { action: "SACRED_FLAME", flow: "SACRED_FLAME_ASCENSION", label: "SACRED_FLAME · ASCENSION" }],
  ["c05", { action: "HOST", flow: "ANGELIC_HOST_CONVERGENCE", label: "HOST · ANGELIC_CONVERGENCE" }],
]);

const PALADIN_SPRITES = new Map();
const PALADIN_READY_SPRITES = new Set([]);
const PALADIN_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "HOLY_RIFT", flow: "CONSECRATED_CROSS_RIFT", label: "HOLY_RIFT · CONSECRATED_CROSS" }],
  ["c02", { action: "AEGIS", flow: "AEGIS_CITADEL_IMPACT", label: "AEGIS · CITADEL_IMPACT" }],
  ["c03", { action: "GRAVITY_SMITE", flow: "JUDGMENT_HAMMER_GRAVITY", label: "SMITE · JUDGMENT_GRAVITY" }],
  ["c04", { action: "AURORA", flow: "AURORA_VOW_WAVE", label: "AURORA · VOW_WAVE" }],
  ["c05", { action: "OATH", flow: "SOVEREIGN_OATH_CRUSADE", label: "OATH · SOVEREIGN_CRUSADE" }],
]);

const AUTHORED_SPRITES = new Map([
  ...WARRIOR_SPRITES,
  ...ROGUE_SPRITES,
  ...RANGER_SPRITES,
  ...MAGE_SPRITES,
  ...CLERIC_SPRITES,
  ...PALADIN_SPRITES,
]);
const AUTHORED_READY_SPRITES = new Set([
  ...WARRIOR_READY_SPRITES,
  ...ROGUE_READY_SPRITES,
  ...RANGER_READY_SPRITES,
  ...MAGE_READY_SPRITES,
  ...CLERIC_READY_SPRITES,
  ...PALADIN_READY_SPRITES,
]);
const APEX_CLASS_REWORK_VARIANTS = new Map();
const APEX_CLASS_REWORK_LAYOUTS = new Map();
const AUTHORED_SPRITE_PRESENTATIONS = new Map([
  ["warrior", WARRIOR_SPRITE_PRESENTATIONS],
  ["rogue", ROGUE_SPRITE_PRESENTATIONS],
  ["ranger", RANGER_SPRITE_PRESENTATIONS],
  ["mage", MAGE_SPRITE_PRESENTATIONS],
  ["cleric", CLERIC_SPRITE_PRESENTATIONS],
  ["paladin", PALADIN_SPRITE_PRESENTATIONS],
]);
const AUTHORED_SPRITE_COLUMNS = 4;
const AUTHORED_SPRITE_ROWS = 4;
const AUTHORED_SPRITE_FRAME_COUNT = AUTHORED_SPRITE_COLUMNS * AUTHORED_SPRITE_ROWS;
const AUTHORED_SPRITE_FRAME_MILLIS = 62.5;
const AUTHORED_SPRITE_MIN_FRAME_COUNT = 8;
const AUTHORED_SPRITE_MAX_FRAME_COUNT = 16;
const AUTHORED_SPRITE_FINAL_FRAME_HOLD_MILLIS = 100;
const AUTHORED_SPRITE_FADE_OUT_MILLIS = 250;
const DEFAULT_AUTHORED_SPRITE_LAYOUT = Object.freeze({
  columns: AUTHORED_SPRITE_COLUMNS,
  rows: AUTHORED_SPRITE_ROWS,
  frameCount: AUTHORED_SPRITE_FRAME_COUNT,
  finalFrameHoldMillis: AUTHORED_SPRITE_FINAL_FRAME_HOLD_MILLIS,
  fadeOutMillis: AUTHORED_SPRITE_FADE_OUT_MILLIS,
});
function signatureDamageTimings(hitCount) {
  if (hitCount === 12) return [63, 100, 138, 175, 213, 250, 288, 325, 363, 400, 438, 500];
  if (hitCount === 6) return [63, 125, 188, 250, 313, 500];
  if (hitCount === 5) return [125, 219, 313, 406, 500];
  if (hitCount === 4) return [125, 250, 375, 500];
  if (hitCount === 3) return [188, 313, 500];
  if (hitCount === 2) return [313, 500];
  return [500];
}

const ROGUE_VFX_TIMING_PROFILES = Object.freeze({
  "1": Object.freeze({
    1: [500], 2: [313, 500], 3: [188, 313, 500], 4: [125, 250, 375, 500],
  }),
  "2": Object.freeze({
    1: [500], 2: [250, 500], 3: [188, 375, 500], 4: [125, 313, 438, 500],
  }),
  "3": Object.freeze({
    1: [500], 2: [375, 500], 3: [250, 375, 500], 4: [188, 313, 438, 500],
  }),
  "4": Object.freeze({
    1: [500], 2: [438, 500], 3: [313, 438, 500], 4: [250, 375, 438, 500],
  }),
});
function rogueVfxDamageTimings(hitCount, variant = INITIAL_ROGUE_VFX_VARIANT) {
  if (variant === "original" || variant === "legacy") return signatureDamageTimings(hitCount);
  return [...(ROGUE_VFX_TIMING_PROFILES[variant]?.[hitCount] ?? signatureDamageTimings(hitCount))];
}
function rogueVfxEnding(variant = INITIAL_ROGUE_VFX_VARIANT) {
  return variant === "4"
    ? { finalFrameHoldMillis: 125, fadeOutMillis: 275 }
    : {};
}

function rogueVfxLayout(hitCount, variant = INITIAL_ROGUE_VFX_VARIANT) {
  return Object.freeze({
    columns: 4,
    rows: 4,
    frameCount: 16,
    damageTimings: rogueVfxDamageTimings(hitCount, variant),
    damageMillis: 500,
    damageFadeStartMillis: 900,
    damageHideMillis: 1100,
    ...rogueVfxEnding(variant),
  });
}

function appHitWeights(hitCount) {
  const fixed = new Map([
    [1, [100]],
    [2, [48, 52]],
    [3, [28, 32, 40]],
    [4, [20, 22, 25, 33]],
    [5, [15, 17, 18, 20, 30]],
  ]);
  if (fixed.has(hitCount)) return [...fixed.get(hitCount)];
  const weights = Array(hitCount).fill(Math.floor(100 / hitCount));
  const remainder = 100 - weights.reduce((sum, weight) => sum + weight, 0);
  for (let offset = 0; offset < remainder; offset += 1) {
    weights[hitCount - 1 - (offset % hitCount)] += 1;
  }
  const finisherBoost = Math.min(12, (hitCount - 1) * (weights[0] - 1));
  for (let offset = 0; offset < finisherBoost; offset += 1) {
    weights[offset % (hitCount - 1)] -= 1;
    weights[hitCount - 1] += 1;
  }
  return weights;
}

function editorDefaultHitTimings(hitCount) {
  if (hitCount <= 6 || hitCount === 12) return signatureDamageTimings(hitCount);
  return Array.from({ length: hitCount }, (_, index) =>
    63 + Math.floor((500 - 63) * index / Math.max(1, hitCount - 1))
  );
}
const AUTHORED_SPRITE_LAYOUTS = new Map([
  [BLADE_SLASH_ID, Object.freeze({ columns: 4, rows: 4, frameCount: 16, finalFrameHoldMillis: 0, fadeOutMillis: 0, damageMillis: 490 })],
  [SHATTER_STRIKE_ID, Object.freeze({ columns: 4, rows: 3, frameCount: 12, damageMillis: 250, damageFadeStartMillis: 390, damageHideMillis: 470 })],
  [SHIELD_BREAK_ID, Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })],
  [EARTH_CLEAVE_ID, Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })],
  [CROSS_SLASH_ID, Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })],
  [STORM_SLASH_ID, Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageTimings: [63, 125, 188, 250, 313, 500], damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })],
  ...INTUITIVE_WARRIOR_SKILL_IDS.map((skillId) => [skillId, Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })]),
  [DRAGONSLAYER_ID, Object.freeze({
    columns: 4,
    rows: 4,
    frameCount: 16,
    impactFrameIndex: 8,
    impactMillis: 800,
    postImpactFrameDurationMillis: 70,
    finalFrameHoldMillis: 0,
    fadeOutMillis: 40,
    damageTimings: [800],
    damageMillis: 800,
    damageFadeStartMillis: 1200,
    damageHideMillis: 1360,
  })],
  ["warrior_t16_c05", Object.freeze({ columns: 4, rows: 4, frameCount: 16, damageTimings: [63, 100, 138, 175, 213, 250, 288, 325, 363, 400, 438, 500], damageMillis: 500, damageFadeStartMillis: 900, damageHideMillis: 1100 })],
  ...APEX_800MS_WARRIOR_IDS.map((skillId) => [skillId, Object.freeze({
    columns: 4,
    rows: 4,
    frameCount: 16,
    impactFrameIndex: 8,
    impactMillis: 800,
    postImpactFrameDurationMillis: 70,
    finalFrameHoldMillis: 0,
    fadeOutMillis: 40,
    damageTimings: [800],
    damageMillis: 800,
    damageFadeStartMillis: 1200,
    damageHideMillis: 1360,
  })]),
  ...SIGNATURE_ROGUE_SKILLS.map(({ id, hits }) => [id, rogueVfxLayout(hits, INITIAL_ROGUE_VFX_SELECTIONS.get(id))]),
]);
const AUTHORED_SPRITE_PRESENTATION_OVERRIDES = new Map([
  [BLADE_SLASH_ID, {
    action: "SNAP_TOP_DOWN_SLASH",
    flow: "LOCKED_SPLINE_SNAP_CUT_IMPACT",
    label: "SNAP_SLASH · IMPACT_500MS",
  }],
  [SHIELD_BREAK_ID, { action: "STEEL_SLASH", flow: "AIRBORNE_DIAGONAL_STEEL_SEVER", label: "SLASH · STEEL_SEVER" }],
  [SHATTER_STRIKE_ID, { action: "SHATTER_STRIKE", flow: "STEEL_CRATER_SHATTER_IMPACT", label: "CRUSH · SHATTER_IMPACT" }],
  ...Array.from(SWORD_MASTER_LATE_IMPACT_META, ([skillId, meta]) => [skillId, {
    action: meta.action,
    flow: meta.flow,
    label: meta.label,
  }]),
  ...Array.from(SIGNATURE_ROGUE_META, ([skillId, meta]) => [skillId, {
    action: meta.action,
    flow: meta.flow,
    label: meta.label,
  }]),
]);
function installRemainingSignatureSkills(payload) {
  for (const meta of payload.skills ?? []) {
    SIGNATURE_REMAINING_META.set(meta.id, meta);
    SIGNATURE_REMAINING_NAMES.set(meta.id, meta.name);
    WEB_REVIEW_SKILL_IDS.add(meta.id);
    AUTHORED_SPRITES.set(meta.id, meta.spritePath);
    AUTHORED_READY_SPRITES.add(meta.id);
    AUTHORED_SPRITE_LAYOUTS.set(meta.id, Object.freeze({
      columns: 4,
      rows: 4,
      frameCount: 16,
      damageTimings: signatureDamageTimings(meta.hits),
      damageMillis: 500,
      damageFadeStartMillis: 900,
      damageHideMillis: 1100,
    }));
    AUTHORED_SPRITE_PRESENTATION_OVERRIDES.set(meta.id, {
      action: meta.action,
      flow: meta.flow,
      label: meta.label,
    });
  }
}

function installApexClassReworkVariants(payload) {
  const variants = payload.variants ?? ["1", "2", "3", "4"];
  const installApexLayout = (skillId) => {
    const originalLayout = AUTHORED_SPRITE_LAYOUTS.get(skillId) ?? DEFAULT_AUTHORED_SPRITE_LAYOUT;
    const originalDamageTimings = Array.isArray(originalLayout.damageTimings)
      ? originalLayout.damageTimings
      : [originalLayout.damageMillis ?? 500];
    APEX_CLASS_REWORK_LAYOUTS.set(skillId, Object.freeze({
      columns: 4,
      rows: 4,
      frameCount: 16,
      impactFrameIndex: 8,
      impactMillis: 800,
      postImpactFrameDurationMillis: 70,
      finalFrameHoldMillis: 0,
      fadeOutMillis: 40,
      damageTimings: Object.freeze(originalDamageTimings.map((timing) => Math.round(timing * 1.6))),
      damageMillis: 800,
      damageFadeStartMillis: 1200,
      damageHideMillis: 1360,
    }));
  };
  for (const skillId of payload.skills ?? []) {
    const originalPath = AUTHORED_SPRITES.get(skillId);
    if (!originalPath) throw new Error(`Apex class rework original is missing: ${skillId}`);
    const spriteRoot = payload.skillSpriteRoots?.[skillId] ?? payload.spriteRoot;
    APEX_CLASS_REWORK_VARIANTS.set(skillId, Object.freeze({
      originalPath,
      generator: payload.skillGenerators?.[skillId] ?? "Codex 자체생성",
      title: "75–95 스킬 시안 비교",
      defaultSelection: "original",
      options: Object.freeze([
        Object.freeze({ value: "original", label: "원본", shortLabel: "원본", detailLabel: "기존 원본" }),
        ...variants.map((variant) => Object.freeze({
          value: variant,
          label: `${variant}안`,
          shortLabel: `${variant}안`,
          detailLabel: `신규 ${variant}안`,
        })),
      ]),
      variants: Object.freeze(Object.fromEntries(variants.map((variant) => [
        variant,
        `${spriteRoot}/variant-${variant}/${skillId}.png?v=apex-class-rework-20260825-v4`,
      ]))),
    }));
    installApexLayout(skillId);
  }
  for (const skillId of payload.finalizedSkillLayouts ?? []) {
    if (!AUTHORED_SPRITES.has(skillId)) {
      throw new Error(`Finalized Apex class skill is missing: ${skillId}`);
    }
    installApexLayout(skillId);
  }
  for (const [skillId, customReview] of Object.entries(payload.customReviews ?? {})) {
    const originalPath = AUTHORED_SPRITES.get(skillId);
    if (!originalPath) throw new Error(`Custom VFX review original is missing: ${skillId}`);
    const options = (customReview.choices ?? []).map((choice) => Object.freeze({ ...choice }));
    if (!options.length || !options.some((choice) => choice.value === customReview.defaultSelection)) {
      throw new Error(`Custom VFX review default selection is invalid: ${skillId}`);
    }
    APEX_CLASS_REWORK_VARIANTS.set(skillId, Object.freeze({
      originalPath,
      generator: customReview.generator ?? "복구 보관본",
      title: customReview.title ?? "스킬 이미지 비교",
      defaultSelection: customReview.defaultSelection,
      options: Object.freeze(options),
      variants: Object.freeze(Object.fromEntries(options.map((choice) => [choice.value, choice.path]))),
    }));
  }
}

function authoredSpriteLayout(skillId) {
  const hasApexReview = APEX_CLASS_REWORK_VARIANTS.has(skillId);
  const usesApexRework = APEX_CLASS_REWORK_LAYOUTS.has(skillId)
    && (!hasApexReview || selectedApexVfxVariant(skillId) !== "original");
  const configured = (usesApexRework ? APEX_CLASS_REWORK_LAYOUTS.get(skillId) : null)
    ?? AUTHORED_SPRITE_LAYOUTS.get(skillId)
    ?? DEFAULT_AUTHORED_SPRITE_LAYOUT;
  const frameCount = Math.min(
    AUTHORED_SPRITE_MAX_FRAME_COUNT,
    Math.max(AUTHORED_SPRITE_MIN_FRAME_COUNT, Number.isInteger(configured.frameCount) ? configured.frameCount : AUTHORED_SPRITE_FRAME_COUNT),
  );
  const columns = configured.columns ?? AUTHORED_SPRITE_COLUMNS;
  return {
    ...configured,
    columns,
    rows: Math.ceil(frameCount / columns),
    frameCount,
    finalFrameHoldMillis: configured.finalFrameHoldMillis ?? AUTHORED_SPRITE_FINAL_FRAME_HOLD_MILLIS,
    fadeOutMillis: configured.fadeOutMillis ?? AUTHORED_SPRITE_FADE_OUT_MILLIS,
  };
}

function authoredSpriteEndMillis(skillId) {
  const layout = authoredSpriteLayout(skillId);
  return Number.isInteger(layout.impactFrameIndex) && Number.isInteger(layout.impactMillis)
    && Number.isFinite(layout.postImpactFrameDurationMillis)
    ? layout.impactMillis + Math.round((layout.frameCount - layout.impactFrameIndex) * layout.postImpactFrameDurationMillis)
    : Math.round(layout.frameCount * AUTHORED_SPRITE_FRAME_MILLIS);
}

function authoredSpriteFrameStartMillis(skillId, frameIndex) {
  const layout = authoredSpriteLayout(skillId);
  if (Number.isInteger(layout.impactFrameIndex) && Number.isInteger(layout.impactMillis)
    && Number.isFinite(layout.postImpactFrameDurationMillis)) {
    if (frameIndex < layout.impactFrameIndex) {
      return Math.round(frameIndex * layout.impactMillis / layout.impactFrameIndex);
    }
    return layout.impactMillis
      + Math.round((frameIndex - layout.impactFrameIndex) * layout.postImpactFrameDurationMillis);
  }
  return Math.round(frameIndex * AUTHORED_SPRITE_FRAME_MILLIS);
}

function authoredSpriteFadeFrameIndex(skillId) {
  const layout = authoredSpriteLayout(skillId);
  // FrameSmith's 4x4 contract reserves F16 as a transparent terminal cell.
  // Hold F15 so the shared runtime fade remains visible; compact sheets fade
  // their actual final frame instead.
  return layout.frameCount === AUTHORED_SPRITE_FRAME_COUNT
    ? layout.frameCount - 2
    : layout.frameCount - 1;
}

function authoredSpriteFadeStartMillis(skillId) {
  return authoredSpriteFrameStartMillis(skillId, authoredSpriteFadeFrameIndex(skillId));
}

function authoredSpriteHideMillis(skillId) {
  const layout = authoredSpriteLayout(skillId);
  return authoredSpriteEndMillis(skillId) + layout.finalFrameHoldMillis + layout.fadeOutMillis;
}

function authoredSpriteEndingDetail(skillId) {
  const layout = authoredSpriteLayout(skillId);
  const fadeFrameNumber = authoredSpriteFadeFrameIndex(skillId) + 1;
  const fadeDetail = `F${String(fadeFrameNumber).padStart(2, "0")} 유지 · ${authoredSpriteFadeStartMillis(skillId)}-${authoredSpriteHideMillis(skillId)}ms 런타임 페이드아웃`;
  return layout.frameCount === AUTHORED_SPRITE_FRAME_COUNT
    ? `${fadeDetail} · F16 빈 소실 셀`
    : fadeDetail;
}

function withAuthoredSpriteEnding(detail, skillId) {
  const endingOffsets = [" · F15 유지/", " · F16"]
    .map((marker) => detail.indexOf(marker))
    .filter((offset) => offset >= 0);
  const baseDetail = endingOffsets.length > 0
    ? detail.slice(0, Math.min(...endingOffsets))
    : detail;
  return `${baseDetail} · ${authoredSpriteEndingDetail(skillId)}`;
}

function authoredSpritePresentation(skillId) {
  if (!AUTHORED_READY_SPRITES.has(skillId)) return null;
  const override = AUTHORED_SPRITE_PRESENTATION_OVERRIDES.get(skillId);
  if (override) return override;
  const heroClass = skillId.split("_", 1)[0];
  return AUTHORED_SPRITE_PRESENTATIONS.get(heroClass)?.get(skillId.slice(-3)) ?? null;
}

function authoredSpriteFrameIndex(skillId, elapsed) {
  const layout = authoredSpriteLayout(skillId);
  if (Number.isInteger(layout.impactFrameIndex) && Number.isInteger(layout.impactMillis)
    && Number.isFinite(layout.postImpactFrameDurationMillis)) {
    if (elapsed < layout.impactMillis) {
      return clamp(Math.floor(elapsed * layout.impactFrameIndex / layout.impactMillis), 0, layout.impactFrameIndex - 1);
    }
    return clamp(
      layout.impactFrameIndex + Math.floor((elapsed - layout.impactMillis) / layout.postImpactFrameDurationMillis),
      layout.impactFrameIndex,
      layout.frameCount - 1,
    );
  }
  return Math.min(layout.frameCount - 1, Math.floor(elapsed / AUTHORED_SPRITE_FRAME_MILLIS));
}

const state = {
  payload: null,
  skills: [],
  selectedClass: "WARRIOR",
  selected: null,
  selectedFrames: new Map(),
  selectedPresentation: new Map(),
  frameRequest: 0,
  elapsed: 0,
  playing: true,
  speed: 1,
  reducedMotion: false,
  previewScale: 1,
  roleFocus: "ALL",
  lastFrame: 0,
  hitSettings: { schemaVersion: 1, updatedAt: null, skills: {} },
  hitEditorDraft: { hitCount: 1, timings: [500] },
  hitEditorSaving: false,
  spriteFinderOpening: false,
  rogueVfxSelections: new Map(INITIAL_ROGUE_VFX_SELECTIONS),
  apexVfxSelections: new Map(),
  layers: { secondary: true, primary: true, impact: true, debris: true, residual: true, label: true, damage: true },
};

const $ = (id) => document.getElementById(id);
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const fraction = (value, start, end) => clamp((value - start) / Math.max(1, end - start), 0, 1);
const assetUrl = (filename) => filename.startsWith("custom-assets/") ? `/${filename}` : `/assets/${filename}`;
const activeSpritePath = (skill) => {
  if (!skill) return null;
  const review = APEX_CLASS_REWORK_VARIANTS.get(skill.id);
  if (!review) return AUTHORED_SPRITES.get(skill.id) ?? null;
  const selection = selectedApexVfxVariant(skill.id);
  return selection === "original" ? review.originalPath : review.variants[selection];
};
const sourceImageCache = new Map();
const renderedAssetCache = new Map();

function selectedRogueVfxVariant(skillId = state.selected?.id) {
  return state.rogueVfxSelections.get(skillId) ?? INITIAL_ROGUE_VFX_VARIANT;
}

function rogueVfxSelectionLabel(skillId, variant = selectedRogueVfxVariant(skillId)) {
  if (variant === "original" || variant === "legacy") return "원본";
  return `확정 ${variant}안`;
}

function selectedApexVfxVariant(skillId = state.selected?.id) {
  return state.apexVfxSelections.get(skillId)
    ?? APEX_CLASS_REWORK_VARIANTS.get(skillId)?.defaultSelection
    ?? "original";
}

function apexVfxSelectionLabel(skillId, selection = selectedApexVfxVariant(skillId)) {
  const option = APEX_CLASS_REWORK_VARIANTS.get(skillId)?.options.find((candidate) => candidate.value === selection);
  return option?.detailLabel ?? option?.label ?? (selection === "original" ? "기존 원본" : `신규 ${selection}안`);
}

function apexVfxSelectionShortLabel(skillId, selection = selectedApexVfxVariant(skillId)) {
  const option = APEX_CLASS_REWORK_VARIANTS.get(skillId)?.options.find((candidate) => candidate.value === selection);
  return option?.shortLabel ?? option?.label ?? selection;
}

function loadSourceImage(filename) {
  if (!sourceImageCache.has(filename)) {
    sourceImageCache.set(filename, new Promise((resolve, reject) => {
      const image = new Image();
      image.decoding = "async";
      image.onload = () => resolve(image);
      image.onerror = reject;
      image.src = assetUrl(filename);
    }));
  }
  return sourceImageCache.get(filename);
}

function tintColor(tintArgb) {
  const value = String(tintArgb || "ffffffff").padStart(8, "0");
  return `#${value.slice(2, 8)}`;
}

function normalizeScreenPixels(context, width, height) {
  // CSS/Canvas implementations may raster an opaque black source rectangle
  // before applying mix-blend-mode. Screen treats black as a neutral color, so
  // move the source brightness into alpha and normalize RGB. This is
  // algebraically equivalent to Android's Screen result but makes true black
  // transparent and prevents the rotated black-card artifact in browsers.
  const imageData = context.getImageData(0, 0, width, height);
  const pixels = imageData.data;
  for (let index = 0; index < pixels.length; index += 4) {
    const brightness = Math.max(pixels[index], pixels[index + 1], pixels[index + 2]);
    if (brightness === 0) {
      pixels[index + 3] = 0;
      continue;
    }
    const scale = 255 / brightness;
    pixels[index] = Math.round(pixels[index] * scale);
    pixels[index + 1] = Math.round(pixels[index + 1] * scale);
    pixels[index + 2] = Math.round(pixels[index + 2] * scale);
    pixels[index + 3] = Math.round(pixels[index + 3] * brightness / 255);
  }
  context.putImageData(imageData, 0, 0);
}

async function renderedAsset(filename, drawMode, tintArgb) {
  const key = `${filename}|${drawMode}|${tintArgb || ""}`;
  if (!renderedAssetCache.has(key)) {
    renderedAssetCache.set(key, loadSourceImage(filename).then((image) => {
      const canvas = document.createElement("canvas");
      canvas.width = image.naturalWidth;
      canvas.height = image.naturalHeight;
      const context = canvas.getContext("2d", { alpha: true });
      context.drawImage(image, 0, 0);
      if (drawMode === "LEGACY_TINTED") {
        // Compose uses ColorFilter.tint(..., Modulate), then Screen. Multiplying
        // RGB and restoring the source alpha reproduces that two-stage draw.
        context.globalCompositeOperation = "multiply";
        context.fillStyle = tintColor(tintArgb);
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.globalCompositeOperation = "destination-in";
        context.drawImage(image, 0, 0);
        context.globalCompositeOperation = "source-over";
      }
      if (drawMode === "AUTHORED_SCREEN" || drawMode === "LEGACY_TINTED") {
        normalizeScreenPixels(context, canvas.width, canvas.height);
      }
      return canvas;
    }));
  }
  return renderedAssetCache.get(key);
}

function syncCanvasBitmap(node, asset, frame) {
  const key = `${asset}|${frame.drawMode}|${frame.tintArgb || ""}`;
  node.dataset.renderKey = key;
  node.style.opacity = "0";
  renderedAsset(asset, frame.drawMode, frame.tintArgb).then((source) => {
    if (node.dataset.renderKey !== key) return;
    if (node.width !== source.width || node.height !== source.height) {
      node.width = source.width;
      node.height = source.height;
    }
    const context = node.getContext("2d", { alpha: true });
    context.clearRect(0, 0, node.width, node.height);
    context.drawImage(source, 0, 0);
    node.dataset.readyKey = key;
    node.style.opacity = node.dataset.frameAlpha;
  }).catch((error) => {
    node.dataset.readyKey = "";
    console.error(`VFX asset load failed: ${asset}`, error);
  });
}

function frameSlices() { return [{ key: "full", clip: "none", inside: false }]; }

function ensureLayerSlices(group, slices) {
  const signature = slices.map((slice) => slice.key).join(",");
  if (group.dataset.slices === signature) return;
  group.replaceChildren(...slices.map((slice) => {
    const clip = document.createElement("div");
    clip.className = "vfx-layer-clip";
    clip.dataset.insideSafe = slice.inside ? "1" : "0";
    clip.style.clipPath = slice.clip;
    const canvas = document.createElement("canvas");
    canvas.className = "vfx-layer";
    clip.appendChild(canvas);
    return clip;
  }));
  group.dataset.slices = signature;
}

function exactSampleTime(elapsed) {
  const exact = clamp(Math.round(elapsed), 0, 1350);
  // Android's export includes every damage landmark in addition to the regular 10 ms playback
  // grid. Keep those authored contact instants exact when a hit marker is selected.
  if (state.selectedFrames.has(exact) && state.selectedPresentation.has(exact)) return exact;
  return clamp(Math.round(elapsed / state.payload.meta.sampleStepMillis) * state.payload.meta.sampleStepMillis, 0, 1350);
}

function syncFrameNodes(frames) {
  const container = $("androidFrameLayers");
  const viewport = $("vfxViewport");
  const width = viewport.clientWidth || 361;
  const height = viewport.clientHeight || 160;
  const visibleFrames = frames.filter((frame) =>
    state.layers[ROLE_TOGGLE[frame.role]] !== false &&
    (state.roleFocus === "ALL" || frame.role === state.roleFocus)
  );
  while (container.children.length < visibleFrames.length) {
    const group = document.createElement("div");
    group.className = "vfx-layer-group";
    container.appendChild(group);
  }
  [...container.children].forEach((group, index) => {
    const frame = visibleFrames[index];
    if (!frame) {
      group.style.display = "none";
      return;
    }
    const asset = state.payload.meta.assetsById[String(frame.asset)];
    const renderKey = `${asset}|${frame.drawMode}|${frame.tintArgb || ""}`;
    const slices = frameSlices(frame);
    ensureLayerSlices(group, slices);
    group.dataset.role = frame.role;
    group.dataset.hit = String(frame.hit);
    group.dataset.instance = String(frame.instance);
    group.style.display = "block";
    [...group.children].forEach((clip, sliceIndex) => {
      const slice = slices[sliceIndex];
      const node = clip.firstElementChild;
      node.style.left = `${frame.x * width}px`;
      node.style.top = `${frame.y * height}px`;
      node.style.width = `${frame.w * width}px`;
      node.style.height = `${frame.h * height}px`;
      // Keep every frame at source opacity, then apply the one shared ending
      // fade while the final visible frame is held.
      const sliceAlpha = authoredSpriteOpacity(state.selected.id, state.elapsed);
      node.dataset.frameAlpha = String(sliceAlpha);
      node.style.opacity = node.dataset.readyKey === renderKey ? sliceAlpha : 0;
      node.style.mixBlendMode = frame.drawMode === "AUTHORED_SCREEN" || frame.drawMode === "LEGACY_TINTED" ? "screen" : "normal";
      node.style.clipPath = frame.drawMode === "LEGACY_STEEL_REVEAL"
        ? `inset(0 ${Math.max(0, 1 - frame.reveal) * 100}% 0 0)`
        : "none";
      node.style.transform = `translate(-50%, -50%) rotate(${frame.r}deg) scaleX(${frame.mirror})`;
      if (node.dataset.readyKey !== renderKey && node.dataset.renderKey !== renderKey) syncCanvasBitmap(node, asset, frame);
    });
  });
  return {
    count: visibleFrames.length,
    roles: [...new Set(visibleFrames.map((frame) => frame.role))],
  };
}

const APP_PREVIEW_TOTAL_DAMAGE = 10_000;
const APP_PREVIEW_SKILL_LEVEL = 1;
const APP_PRESENTATION_DURATION_MILLIS = 1_400;
const APP_DAMAGE_DISPLAY_EXTENSION_MILLIS = 500;
const APP_HIT_TIMING_MIN_MILLIS = 0;
const APP_HIT_TIMING_MAX_MILLIS = 900;
const HIT_MARKER_LANE_GAP_MILLIS = 90;

function appSkillDamageTimings(skill) {
  if (skill.hitTimingOverride) return [...skill.timings];
  if (skill.id === BLADE_SLASH_ID) return [490];
  if (skill.id === SHATTER_STRIKE_ID) return [250];
  const configured = authoredSpriteLayout(skill.id).damageTimings;
  return Array.isArray(configured) && configured.length === skill.hits
    ? configured
    : signatureDamageTimings(skill.hits);
}

function applyHitSettingToSkill(skill, setting) {
  if (!setting) return skill;
  const timings = [...setting.hitTimingsMillis];
  return {
    ...skill,
    hits: setting.hitCount,
    weights: [...(setting.hitWeights ?? appHitWeights(setting.hitCount))],
    timings,
    presentationTimings: timings,
    presentationGroups: timings.map((timing, index) => ({
      timing,
      sourceIndices: [index],
      weight: (setting.hitWeights ?? appHitWeights(setting.hitCount))[index],
    })),
    hitTimingOverride: true,
    hitSettingUpdatedAt: setting.updatedAt,
  };
}

function appSkillDisplayName(skill) {
  return `${skill.name} LV.${APP_PREVIEW_SKILL_LEVEL}`;
}

function appSkillLabelAlpha(skill, elapsed) {
  const firstHit = previewSkillDamageTimings(skill)[0];
  const vfxEnd = authoredSpriteHideMillis(skill.id);
  if (elapsed < 0 || elapsed >= vfxEnd) return 0;
  const enterStart = Math.max(0, firstHit - 140);
  const enterEnd = Math.min(firstHit, enterStart + 80);
  const exitStart = Math.max(enterEnd, vfxEnd - 90);
  if (elapsed < enterStart) return 0;
  if (elapsed < enterEnd) return fraction(elapsed, enterStart, enterEnd);
  if (elapsed < exitStart) return 1;
  return 1 - fraction(elapsed, exitStart, vfxEnd);
}

function previewSkillHitWeights(skill) {
  const previewHitCount = previewSkillDamageTimings(skill).length;
  return previewHitCount === skill.weights.length
    ? skill.weights
    : appHitWeights(previewHitCount);
}

function cumulativeAppDamage(skill) {
  const weights = previewSkillHitWeights(skill);
  let accumulated = 0;
  return weights.map((weight, index) => {
    const damage = index === weights.length - 1
      ? APP_PREVIEW_TOTAL_DAMAGE - accumulated
      : Math.floor(APP_PREVIEW_TOTAL_DAMAGE * weight / 100);
    accumulated += damage;
    return accumulated;
  });
}

function appDamagePresentation(skill, elapsed) {
  const timings = previewSkillDamageTimings(skill);
  const hitIndex = timings.findLastIndex((timing) => elapsed >= timing);
  if (hitIndex < 0) return null;
  const isFinal = hitIndex === timings.length - 1;
  const start = timings[hitIndex];
  const finalBaseDuration = skill.id === BLADE_SLASH_ID ? 400 : 420;
  const layout = authoredSpriteLayout(skill.id);
  const finalEnd = Number.isInteger(layout.damageHideMillis)
    ? layout.damageHideMillis
    : Math.min(
      timings.at(-1) + finalBaseDuration + APP_DAMAGE_DISPLAY_EXTENSION_MILLIS,
      APP_PRESENTATION_DURATION_MILLIS,
    );
  const end = timings[hitIndex + 1] ?? finalEnd;
  if (end <= start || elapsed >= end) return null;

  const pulseEnd = Math.min(start + 72, end);
  const pulseProgress = fraction(elapsed, start, pulseEnd);
  const easedPulse = 1 - (1 - pulseProgress) * (1 - pulseProgress);
  const fadeStart = isFinal
    ? Math.max(pulseEnd, Number.isInteger(layout.damageFadeStartMillis) ? layout.damageFadeStartMillis : end - 160)
    : end;
  const fadeProgress = isFinal && elapsed >= fadeStart ? fraction(elapsed, fadeStart, end) : 0;
  const peakScale = isFinal ? 1.15 : 1.08;
  const alpha = elapsed < pulseEnd
    ? .72 + .28 * easedPulse
    : isFinal && elapsed >= fadeStart
      ? 1 - fadeProgress
      : 1;
  const scale = elapsed < pulseEnd
    ? peakScale - (peakScale - 1) * easedPulse
    : isFinal && elapsed >= fadeStart
      ? 1 - .04 * fadeProgress
      : 1;

  return {
    damageVisible: true,
    damage: cumulativeAppDamage(skill)[hitIndex],
    damageAlpha: state.reducedMotion ? 1 : clamp(alpha, 0, 1),
    damageScale: state.reducedMotion ? 1 : scale,
    damageY: state.reducedMotion ? 0 : (isFinal ? -16 * fadeProgress : 0),
    damageFinal: isFinal,
  };
}

function appDamageFontSize(hitCount, isFinal) {
  if (hitCount === 1) return 46;
  if (hitCount === 2) return isFinal ? 43 : 38;
  if (hitCount === 3) return isFinal ? 42 : 34;
  if (hitCount === 4) return isFinal ? 40 : 31;
  return isFinal ? 40 : 28;
}

function renderFrame() {
  const skill = state.selected;
  if (!skill) return;
  const elapsed = Math.round(state.elapsed);
  const previewTimings = previewSkillDamageTimings(skill);
  $("timeline").value = elapsed;
  $("timeOutput").textContent = `${elapsed}ms`;
  const sampleTime = exactSampleTime(elapsed);
  const authoredSpriteActive = AUTHORED_READY_SPRITES.has(skill.id) && state.layers.primary && (state.roleFocus === "ALL" || state.roleFocus === "PRIMARY");
  const activeLayerState = syncFrameNodes(authoredSpriteActive ? [] : (state.selectedFrames.get(sampleTime) ?? []));
  renderAuthoredSprite(authoredSpriteActive, elapsed, skill.id);
  const minimumPeak = skill.minimumPeakConcurrent ?? skill.normalPeakConcurrent ?? 1;
  $("layerCount").textContent = `${activeLayerState.count}층 · ${activeLayerState.roles.length}역할 / 최소 피크 ${minimumPeak}층 · 전체 ${skill.actualLayers}역할`;

  const presentation = state.selectedPresentation.get(sampleTime);
  const damagePresentation = appDamagePresentation(skill, elapsed);
  const damageNode = $("damageNumber");
  damageNode.textContent = damagePresentation?.damageVisible ? `-${damagePresentation.damage.toLocaleString("ko-KR")}` : "";
  damageNode.style.opacity = state.layers.damage && damagePresentation?.damageVisible ? damagePresentation.damageAlpha : 0;
  damageNode.style.fontSize = `${appDamageFontSize(previewTimings.length, damagePresentation?.damageFinal ?? true)}px`;
  damageNode.style.transform = `translate(-50%, -50%) translateY(${damagePresentation?.damageY ?? 0}px) scale(${damagePresentation?.damageScale ?? 1})`;

  const colors = ELEMENT_COLORS[skill.element] ?? ELEMENT_COLORS.PHYSICAL;
  const labelNode = $("skillLabel");
  labelNode.textContent = appSkillDisplayName(skill);
  labelNode.style.opacity = state.layers.label ? appSkillLabelAlpha(skill, elapsed) : 0;
  labelNode.style.borderColor = `${colors[0]}73`;
  damageNode.style.color = colors[0];
  const previewFinalHit = previewTimings.at(-1);
  const backdropAlpha = elapsed < 110 ? elapsed / 110 : elapsed < previewFinalHit + 100 ? 1 : 1 - fraction(elapsed, previewFinalHit + 100, 1300);
  $("elementBackdrop").style.background = `radial-gradient(circle at 50% 72%, ${colors[1]}, transparent 64%)`;
  $("elementBackdrop").style.opacity = clamp(backdropAlpha, 0, 1) * .055;
  $("energyFill").style.width = `${(presentation?.energy ?? .78) * 100}%`;
  const camera = presentation ?? { cameraX: 0, cameraY: 0, cameraScale: 1 };
  $("cameraPlane").style.transform = `translate(${camera.cameraX}px, ${camera.cameraY}px) scale(${camera.cameraScale * 1.02})`;
}

function authoredSpriteOpacity(skillId, elapsed) {
  const fadeStartMillis = authoredSpriteFadeStartMillis(skillId);
  const hideAtMillis = authoredSpriteHideMillis(skillId);
  return FULL_RUNTIME_VFX_OPACITY * (1 - fraction(elapsed, fadeStartMillis, hideAtMillis));
}

function renderAuthoredSprite(active, elapsed, skillId) {
  const canvas = $("chargeSprite");
  const sheetAsset = activeSpritePath({ id: skillId });
  const layout = authoredSpriteLayout(skillId);
  const hideAtMillis = authoredSpriteHideMillis(skillId);
  if (!active || !sheetAsset || elapsed >= hideAtMillis) {
    canvas.style.opacity = "0";
    return;
  }
  const fadeFrameIndex = authoredSpriteFadeFrameIndex(skillId);
  const frameIndex = elapsed >= authoredSpriteFadeStartMillis(skillId)
    ? fadeFrameIndex
    : Math.min(authoredSpriteFrameIndex(skillId, elapsed), fadeFrameIndex);
  const renderKey = `${sheetAsset}-${frameIndex}`;
  const spriteOpacity = authoredSpriteOpacity(skillId, elapsed);
  if (canvas.dataset.renderKey === renderKey) {
    canvas.style.opacity = String(spriteOpacity);
    return;
  }
  canvas.dataset.renderKey = renderKey;
  renderedAsset(sheetAsset, "AUTHORED_SOURCE", null).then((sheet) => {
    if (canvas.dataset.renderKey !== renderKey) return;
    const frameWidth = sheet.width / layout.columns;
    const frameHeight = sheet.height / layout.rows;
    const sourceX = (frameIndex % layout.columns) * frameWidth;
    const sourceY = Math.floor(frameIndex / layout.columns) * frameHeight;
    const context = canvas.getContext("2d", { alpha: true });
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(sheet, sourceX, sourceY, frameWidth, frameHeight, 0, 0, canvas.width, canvas.height);
    canvas.style.opacity = String(authoredSpriteOpacity(skillId, state.elapsed));
  }).catch((error) => {
    canvas.style.opacity = "0";
    console.error("Authored sprite sheet load failed", error);
  });
}

async function loadFrames(skill) {
  const request = ++state.frameRequest;
  state.selectedFrames = new Map();
  state.selectedPresentation = new Map();
  $("syncStatus").textContent = "Android 프레임 불러오는 중";
  renderFrame();
  const [frameResponse, presentationResponse] = await Promise.all([
    fetch(`/api/frames?skill=${encodeURIComponent(skill.id)}&reduced=${state.reducedMotion ? 1 : 0}`, { cache: "no-store" }),
    fetch(`/api/presentation?skill=${encodeURIComponent(skill.id)}&reduced=${state.reducedMotion ? 1 : 0}`, { cache: "no-store" }),
  ]);
  if (!frameResponse.ok) throw new Error(`Frame export request failed: ${frameResponse.status}`);
  if (!presentationResponse.ok) throw new Error(`Presentation export request failed: ${presentationResponse.status}`);
  const [payload, presentationPayload] = await Promise.all([frameResponse.json(), presentationResponse.json()]);
  if (request !== state.frameRequest || state.selected?.id !== skill.id) return;
  const timeline = new Map();
  payload.frames.forEach((frame) => {
    if (!timeline.has(frame.t)) timeline.set(frame.t, []);
    timeline.get(frame.t).push(frame);
  });
  state.selectedFrames = timeline;
  state.selectedPresentation = new Map(presentationPayload.samples.map((sample) => [sample.t, sample]));
  $("syncStatus").textContent = state.reducedMotion
    ? "Android 모션 줄이기 직접 재생"
    : "Android 연출·피해 직접 재생";
  $("syncStatus").classList.add("exact");
  renderFrame();
}

function renderClassTabs() {
  const labels = Object.fromEntries(state.skills.map((skill) => [skill.heroClass, skill.classLabel]));
  const availableClasses = CLASS_ORDER.filter((heroClass) => state.skills.some((skill) => skill.heroClass === heroClass));
  $("classTabs").innerHTML = availableClasses.map((heroClass) => `<button class="class-tab" role="tab" data-class="${heroClass}" aria-selected="${state.selectedClass === heroClass}">${labels[heroClass]}</button>`).join("");
  $("classTabs").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
    state.selectedClass = button.dataset.class;
    renderClassTabs();
    renderSkillList(true);
  }));
}

function filteredSkills() {
  const query = $("searchInput").value.trim().toLowerCase();
  return state.skills
    .filter((skill) => skill.heroClass === state.selectedClass && (!query || skill.name.toLowerCase().includes(query) || skill.id.includes(query)))
    .sort((left, right) => left.level - right.level || left.id.localeCompare(right.id));
}

function renderSkillList(selectFirst = false) {
  const skills = filteredSkills();
  if (selectFirst && skills.length) selectSkill(skills[0], false);
  $("skillCount").textContent = `${skills.length} / ${state.skills.length}`;
  $("skillList").innerHTML = skills.map((skill) => {
    const apexSelection = APEX_CLASS_REWORK_VARIANTS.has(skill.id) ? selectedApexVfxVariant(skill.id) : null;
    const selectedVariant = !apexSelection && skill.heroClass === "ROGUE" ? selectedRogueVfxVariant(skill.id) : null;
    const variantBadge = apexSelection
      ? `<span class="skill-variant-pill">${apexVfxSelectionShortLabel(skill.id, apexSelection)}</span>`
      : selectedVariant
        ? `<span class="skill-variant-pill">${selectedVariant === "original" || selectedVariant === "legacy" ? "원본" : `${selectedVariant}안`}</span>`
        : "";
    return `
    <button class="skill-row ${state.selected?.id === skill.id ? "selected" : ""}" data-id="${skill.id}">
      <span class="skill-level">Lv.${skill.level}</span>
      <span class="skill-copy"><strong>${skill.name}</strong><span>${skill.id}</span></span>
      <span class="skill-meta">${variantBadge}<span class="hit-pill">${skill.hits}타</span></span>
    </button>`;
  }).join("") || `<p class="empty-result">일치하는 스킬이 없습니다.</p>`;
  $("skillList").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => selectSkill(state.skills.find((skill) => skill.id === button.dataset.id))));
}

function updateSpriteFinderButton(skill) {
  const spritePath = activeSpritePath(skill);
  const button = $("revealSpriteButton");
  const status = $("spriteFinderStatus");
  button.disabled = state.spriteFinderOpening || !spritePath;
  button.title = spritePath?.split("?")[0] ?? "현재 스프라이트 경로가 없습니다";
  status.dataset.tone = "default";
  status.textContent = spritePath ? spritePath.split("?")[0].split("/").at(-1) : "스프라이트 경로 없음";
}

async function revealSelectedSprite() {
  const skill = state.selected;
  const spritePath = activeSpritePath(skill);
  if (!skill || !spritePath || state.spriteFinderOpening) return;
  const button = $("revealSpriteButton");
  const status = $("spriteFinderStatus");
  state.spriteFinderOpening = true;
  button.disabled = true;
  status.dataset.tone = "default";
  status.textContent = "Finder 여는 중…";
  try {
    const response = await fetch("/api/reveal-sprite", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ catalogId: skill.id, spritePath }),
    });
    const result = await response.json();
    if (!response.ok || !result.ok) throw new Error(result.error || `Finder 열기 실패 (${response.status})`);
    status.dataset.tone = "success";
    status.textContent = "Finder에서 파일을 표시했습니다";
    button.title = result.relativePath;
  } catch (error) {
    status.dataset.tone = "error";
    status.textContent = error instanceof Error ? error.message : "Finder 열기에 실패했습니다";
  } finally {
    state.spriteFinderOpening = false;
    button.disabled = !activeSpritePath(state.selected);
  }
}

function renderHitMarkers() {
  const skill = state.selected;
  if (!skill) return;
  const visualTimings = hitEditorMarkerTimings(skill);
  const lanes = hitMarkerLanes(visualTimings);
  const markerContainer = $("hitMarkers");
  markerContainer.style.setProperty("--hit-marker-lanes", String(Math.max(...lanes, 0) + 1));
  markerContainer.innerHTML = visualTimings.map((timing, index) => `
    <button
      type="button"
      class="hit-marker"
      style="left:${hitMarkerLeftPercent(timing)}%;--hit-lane:${lanes[index]}"
      data-index="${index}"
      data-time="${timing}"
      aria-label="${index + 1}타 타이밍 ${timing}ms. 좌우로 드래그해 조정"
      title="${index + 1}타 · ${timing}ms · 좌우로 드래그"
    >${index + 1}타</button>
  `).join("");
  markerContainer.querySelectorAll("button").forEach(installHitMarkerDrag);
}

function hitEditorMarkerTimings(skill) {
  const draft = state.hitEditorDraft;
  if (draft?.hitCount === draft?.timings?.length) {
    const timings = draft.timings.map(Number);
    const valid = timings.every((timing) =>
      Number.isInteger(timing) &&
      timing >= APP_HIT_TIMING_MIN_MILLIS &&
      timing <= APP_HIT_TIMING_MAX_MILLIS
    ) && timings.every((timing, index) => index === 0 || timings[index - 1] < timing);
    if (valid) return timings;
  }
  return appSkillDamageTimings(skill);
}

function previewSkillDamageTimings(skill) {
  return skill === state.selected ? hitEditorMarkerTimings(skill) : appSkillDamageTimings(skill);
}

function hitMarkerLeftPercent(timing) {
  return timing / APP_PRESENTATION_DURATION_MILLIS * 100;
}

function hitMarkerLanes(timings) {
  const laneEnds = [];
  return timings.map((timing) => {
    let lane = laneEnds.findIndex((previousTiming) => timing - previousTiming >= HIT_MARKER_LANE_GAP_MILLIS);
    if (lane < 0) lane = laneEnds.length;
    laneEnds[lane] = timing;
    return lane;
  });
}

function hitTimingDragBounds(index, timings) {
  return {
    min: index === 0 ? APP_HIT_TIMING_MIN_MILLIS : timings[index - 1] + 1,
    max: index === timings.length - 1 ? APP_HIT_TIMING_MAX_MILLIS : timings[index + 1] - 1,
  };
}

function syncHitMarker(button, index, timing) {
  button.dataset.time = String(timing);
  button.style.left = `${hitMarkerLeftPercent(timing)}%`;
  button.setAttribute("aria-label", `${index + 1}타 타이밍 ${timing}ms. 좌우로 드래그해 조정`);
  button.title = `${index + 1}타 · ${timing}ms · 좌우로 드래그`;
}

function updateDraftHitTiming(index, requestedTiming, button) {
  const timings = hitEditorMarkerTimings(state.selected);
  const bounds = hitTimingDragBounds(index, timings);
  const timing = clamp(Math.round(requestedTiming), bounds.min, bounds.max);
  state.hitEditorDraft = {
    hitCount: timings.length,
    timings: timings.map((value, timingIndex) => timingIndex === index ? timing : value),
  };
  const input = $("hitTimingInputs").querySelectorAll("input")[index];
  if (input) {
    input.value = String(timing);
    input.setAttribute("aria-invalid", "false");
  }
  syncHitMarker(button, index, timing);
  state.elapsed = timing;
  state.playing = false;
  updatePlayButton();
  setHitSaveStatus("저장되지 않은 변경", "dirty");
  renderFrame();
}

function installHitMarkerDrag(button) {
  const index = Number(button.dataset.index);
  let drag = null;
  const updateFromPointer = (event) => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const containerRect = $("hitMarkers").getBoundingClientRect();
    const markerCenterX = event.clientX - drag.grabOffsetX;
    const fractionAcrossTimeline = clamp((markerCenterX - containerRect.left) / Math.max(1, containerRect.width), 0, 1);
    updateDraftHitTiming(index, fractionAcrossTimeline * APP_PRESENTATION_DURATION_MILLIS, button);
    if (Math.abs(event.clientX - drag.startX) >= 2) drag.moved = true;
  };
  const endDrag = (event) => {
    if (!drag || event.pointerId !== drag.pointerId) return;
    if (button.hasPointerCapture(event.pointerId)) button.releasePointerCapture(event.pointerId);
    button.classList.remove("dragging");
    drag = null;
    renderHitMarkers();
  };
  button.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    const markerRect = button.getBoundingClientRect();
    drag = {
      pointerId: event.pointerId,
      startX: event.clientX,
      moved: false,
      grabOffsetX: event.clientX - (markerRect.left + markerRect.width / 2),
    };
    button.classList.add("dragging");
    button.setPointerCapture(event.pointerId);
    state.elapsed = Number(button.dataset.time);
    state.playing = false;
    updatePlayButton();
    renderFrame();
    event.preventDefault();
  });
  button.addEventListener("pointermove", updateFromPointer);
  button.addEventListener("pointerup", endDrag);
  button.addEventListener("pointercancel", endDrag);
  button.addEventListener("keydown", (event) => {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    const direction = event.key === "ArrowLeft" ? -1 : 1;
    const step = event.shiftKey ? 10 : 1;
    updateDraftHitTiming(index, Number(button.dataset.time) + direction * step, button);
    event.preventDefault();
  });
}

function setHitSaveStatus(message, tone = "default") {
  const node = $("hitSaveStatus");
  node.textContent = message;
  node.dataset.tone = tone;
}

function setHitEditorExpanded(expanded) {
  const editor = $("hitEditor");
  const button = $("hitEditorToggle");
  $("hitEditorBody").hidden = !expanded;
  editor.classList.toggle("expanded", expanded);
  button.setAttribute("aria-expanded", String(expanded));
  $("hitEditorToggleLabel").textContent = expanded ? "접기" : "편집";
}

function renderHitTimingInputs() {
  $("hitTimingInputs").innerHTML = state.hitEditorDraft.timings.map((timing, index) => `
    <label class="hit-timing-field">
      <span>${index + 1}타</span>
      <input type="number" min="0" max="900" step="1" inputmode="numeric" value="${timing}" aria-label="${index + 1}타 타이밍">
    </label>
  `).join("");
  $("hitTimingInputs").querySelectorAll("input").forEach((input, index) => {
    input.addEventListener("input", () => {
      state.hitEditorDraft.timings[index] = input.value;
      input.setAttribute("aria-invalid", "false");
      setHitSaveStatus("저장되지 않은 변경", "dirty");
      renderHitMarkers();
    });
  });
}

function resetHitEditor(skill) {
  state.hitEditorDraft = {
    hitCount: skill.hits,
    timings: appSkillDamageTimings(skill),
  };
  $("hitEditorSkillName").textContent = `${skill.name} · ${skill.id}`;
  $("hitCountInput").value = skill.hits;
  $("hitCountInput").setAttribute("aria-invalid", "false");
  renderHitTimingInputs();
  if (skill.hitTimingOverride) {
    const savedAt = skill.hitSettingUpdatedAt
      ? new Date(skill.hitSettingUpdatedAt).toLocaleString("ko-KR", { hour12: false })
      : "";
    setHitSaveStatus(savedAt ? `저장됨 · ${savedAt}` : "저장됨", "saved");
  } else {
    setHitSaveStatus("앱 기본값", "default");
  }
}

function readHitEditorDraft() {
  const countInput = $("hitCountInput");
  const hitCount = Number(countInput.value);
  countInput.setAttribute("aria-invalid", "false");
  if (!Number.isInteger(hitCount) || hitCount < 1 || hitCount > 12) {
    countInput.setAttribute("aria-invalid", "true");
    setHitSaveStatus("타수는 1~12 정수여야 합니다", "error");
    return null;
  }
  const timingInputs = [...$("hitTimingInputs").querySelectorAll("input")];
  timingInputs.forEach((input) => input.setAttribute("aria-invalid", "false"));
  if (timingInputs.length !== hitCount) {
    setHitSaveStatus("타수와 타이밍 개수가 다릅니다", "error");
    return null;
  }
  const timings = timingInputs.map((input) => input.value.trim() === "" ? Number.NaN : Number(input.value));
  let invalid = false;
  timings.forEach((timing, index) => {
    if (!Number.isInteger(timing) || timing < 0 || timing > 900) {
      timingInputs[index].setAttribute("aria-invalid", "true");
      invalid = true;
    }
  });
  if (invalid) {
    setHitSaveStatus("각 타이밍은 0~900ms 정수여야 합니다", "error");
    return null;
  }
  for (let index = 1; index < timings.length; index += 1) {
    if (timings[index - 1] >= timings[index]) {
      timingInputs[index - 1].setAttribute("aria-invalid", "true");
      timingInputs[index].setAttribute("aria-invalid", "true");
      setHitSaveStatus("타이밍은 앞에서부터 커져야 합니다", "error");
      return null;
    }
  }
  return { hitCount, hitTimingsMillis: timings };
}

async function saveHitSettings() {
  if (!state.selected || state.hitEditorSaving) return;
  const draft = readHitEditorDraft();
  if (!draft) return;
  const catalogId = state.selected.id;
  const button = $("saveHitSettingsButton");
  state.hitEditorSaving = true;
  button.disabled = true;
  setHitSaveStatus("저장 중", "saving");
  try {
    const response = await fetch("/api/hit-settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ catalogId, ...draft }),
    });
    const result = await response.json();
    if (!response.ok || !result.ok) throw new Error(result.error || `저장 실패 (${response.status})`);
    state.hitSettings.skills[catalogId] = result.setting;
    state.hitSettings.updatedAt = result.setting.updatedAt;
    const skillIndex = state.skills.findIndex((skill) => skill.id === catalogId);
    const updatedSkill = applyHitSettingToSkill(state.skills[skillIndex], result.setting);
    state.skills[skillIndex] = updatedSkill;
    state.selected = updatedSkill;
    state.elapsed = 0;
    renderSkillList(false);
    resetHitEditor(updatedSkill);
    renderHitMarkers();
    renderPhaseMarkers();
    renderDetails();
    renderFrame();
  } catch (error) {
    setHitSaveStatus(error instanceof Error ? error.message : "저장에 실패했습니다", "error");
  } finally {
    state.hitEditorSaving = false;
    button.disabled = false;
  }
}

function renderPhaseMarkers() {
  const skill = state.selected;
  if (skill.id === BLADE_SLASH_ID) {
    const phases = [
      ["예고", 0],
      ["발도 직전", 313],
      ["순간 베기", 420],
      ["타격", 490],
      ["주변 이펙트", 550],
      ["소실", 999],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === SHATTER_STRIKE_ID) {
    const phases = [
      ["상단 예고", 0],
      ["직선 낙하", 63],
      ["낙하 가속", 125],
      ["순간 베기", 188],
      ["지면 충돌", 250],
      ["지면 충돌 유지", 313],
      ["파편 상승", 375],
      ["최종 프레임 유지", 688],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === SHIELD_BREAK_ID) {
    const phases = [
      ["냉철 예고", 0],
      ["공중 절단 가속", 313],
      ["중앙 베기", 438],
      ["최대 절단면", 500],
      ["강철 잔압", 625],
      ["소실 잔광", 875],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === EARTH_CLEAVE_ID) {
    const phases = [
      ["검광 예고", 0],
      ["공중 사선 베기", 63],
      ["지면 첫 접촉", 188],
      ["균열 시작", 250],
      ["주 단층 분리", 438],
      ["최대 대지 파열", 500],
      ["잔해 감쇠", 625],
      ["소실 잔광", 938],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === CROSS_SLASH_ID) {
    const phases = [
      ["수평 검광 예고", 0],
      ["가로 베기 시작", 63],
      ["가로 잔상 완성", 188],
      ["세로 베기 예고", 250],
      ["세로 베기", 313],
      ["십자 첫 완성", 375],
      ["주 십자 잔상", 438],
      ["최대 십자 잔상", 500],
      ["잔상 감쇠", 625],
      ["소실 잔광", 938],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === STORM_SLASH_ID) {
    const phases = [
      ["검풍 예고", 0],
      ["첫 번째 풍절", 63],
      ["두 번째 풍절", 125],
      ["세 번째 풍절", 188],
      ["네 번째 풍절", 250],
      ["다섯 번째 풍절", 313],
      ["여섯 번째 풍절", 375],
      ["폭풍 전선 완성", 438],
      ["최대 폭풍 베기", 500],
      ["바람 리본 감쇠", 625],
      ["소실 잔풍", 938],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === DRAGONSLAYER_ID) {
    const phases = [
      ["용심선 예고", 0],
      ["용린 갑주 형성", 300],
      ["용심선·갑주 압축", 500],
      ["처형 베기 진입", 600],
      ["용린 갑주 관통", 700],
      ["최대 용심선 절단", 800],
      ["용심 절단흔·용린 잔류", 1064],
      ["짧은 소멸", 1416],
      ["최종 페이드", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  const swordMasterMeta = SWORD_MASTER_LATE_IMPACT_META.get(skill.id);
  if (swordMasterMeta) {
    const phases = [
      ["예고", 0],
      [swordMasterMeta.prep, 188],
      [swordMasterMeta.motion, 313],
      [swordMasterMeta.contact, 438],
      [swordMasterMeta.impact, 500],
      [swordMasterMeta.hold, 625],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  const signatureMeta = SIGNATURE_ROGUE_META.get(skill.id) ?? SIGNATURE_REMAINING_META.get(skill.id);
  if (signatureMeta) {
    const hasApexReview = APEX_CLASS_REWORK_VARIANTS.has(skill.id);
    const usesApexRework = APEX_CLASS_REWORK_LAYOUTS.has(skill.id)
      && (!hasApexReview || selectedApexVfxVariant(skill.id) !== "original");
    const phases = usesApexRework
      ? [
          ["예고", 0],
          [signatureMeta.prep, 300],
          [signatureMeta.motion, 500],
          [signatureMeta.contact, 700],
          [signatureMeta.impact, 800],
          [signatureMeta.hold, 1010],
          ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
          ["소실", authoredSpriteHideMillis(skill.id)],
        ]
      : [
          ["예고", 0],
          [signatureMeta.prep, 188],
          [signatureMeta.motion, 313],
          [signatureMeta.contact, 438],
          [signatureMeta.impact, 500],
          [signatureMeta.hold, 625],
          ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
          ["소실", authoredSpriteHideMillis(skill.id)],
        ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (skill.id === SHIELD_BREAK_ID) {
    const phases = [
      ["예고", 0],
      ["발도 직전", 313],
      ["순간 베기", 420],
      ["타격", 490],
      ["주변 이펙트", 550],
      ["소실", 999],
      ["페이드 아웃", authoredSpriteFadeStartMillis(skill.id)],
      ["소실", authoredSpriteHideMillis(skill.id)],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  if (SIGNATURE_WARRIOR_IDS.has(skill.id)) {
    const phases = [
      ["예고", 0],
      ["동작 전개", 313],
      ["1차 타격", 438],
      ["최대 타격", 500],
      ["파편 감쇠", 625],
      ["소실", 999],
    ];
    $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
      `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
    ).join("");
    $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.elapsed = Number(button.dataset.time);
      state.playing = false;
      updatePlayButton();
      renderFrame();
    }));
    return;
  }
  const first = skill.timings[0];
  const last = skill.timings.at(-1);
  const phases = [
    ["예고", Math.max(0, first - 150)],
    ["접촉", last],
    ["파편", Math.min(1230, last + 70)],
    ["잔광", Math.min(1230, last + 180)],
    ["마무리", Math.min(1230, last + 300)],
  ];
  $("phaseMarkers").innerHTML = phases.map(([label, timing]) =>
    `<button type="button" class="phase-marker" data-time="${timing}">${label}<small>${timing}ms</small></button>`
  ).join("");
  $("phaseMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
    state.elapsed = Number(button.dataset.time);
    state.playing = false;
    updatePlayButton();
    renderFrame();
  }));
}

function renderRoleFocusControls() {
  $("roleFocusControls").innerHTML = ROLE_FOCUS_OPTIONS.map(([role, label]) =>
    `<button type="button" data-role-focus="${role}" class="role-focus-button ${state.roleFocus === role ? "selected" : ""}" aria-pressed="${state.roleFocus === role}">${label}</button>`
  ).join("");
  $("roleFocusControls").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
    state.roleFocus = button.dataset.roleFocus;
    renderRoleFocusControls();
    renderFrame();
  }));
}

function renderLayerToggles() {
  const skill = state.selected;
  const assets = skill.roleAssets;
  const rows = [
    ["secondary", "후면 예고", assets.secondary.join(", ") || "—"],
    ["primary", "공격 본체", assets.primary.join(", ")],
    ["impact", "접촉 타격", assets.impact.join(", ") || "—"],
    ["debris", "전경 파편", assets.debris.join(", ") || "—"],
    ["residual", "잔광 / 피니셔", assets.residual.join(", ") || "—"],
    ["label", "스킬명", "앱 중앙 라벨"], ["damage", "피해 숫자", "최상위 표시 타이밍"],
  ];
  $("layerToggles").innerHTML = rows.map(([key, label, asset]) => `<label class="layer-toggle"><input type="checkbox" data-layer="${key}" ${state.layers[key] ? "checked" : ""}><span>${label}</span><code title="${asset}">${asset}</code></label>`).join("");
  $("layerToggles").querySelectorAll("input").forEach((input) => input.addEventListener("change", () => {
    state.layers[input.dataset.layer] = input.checked;
    renderFrame();
  }));
}

function renderApexVariantControls() {
  const targets = [
    { section: $("apexVariantSection"), title: $("apexVariantTitle"), controls: $("apexVariantControls"), note: $("apexVariantNote"), mobile: false },
    { section: $("mobileApexVariantSection"), title: $("mobileApexVariantTitle"), controls: $("mobileApexVariantControls"), note: $("mobileApexVariantNote"), mobile: true },
  ];
  const skill = state.selected;
  const review = skill ? APEX_CLASS_REWORK_VARIANTS.get(skill.id) : null;
  targets.forEach(({ section }) => { section.hidden = !review; });
  if (!review) {
    targets.forEach(({ controls, note }) => {
      controls.innerHTML = "";
      note.textContent = "";
    });
    return;
  }
  const selected = selectedApexVfxVariant(skill.id);
  const selectedLabel = apexVfxSelectionLabel(skill.id, selected);
  targets.forEach(({ title, controls, note, mobile }) => {
    title.textContent = review.title;
    controls.setAttribute("aria-label", `${skill.name} 이미지 시안 선택`);
    controls.innerHTML = review.options.map(({ value, label }) => `
      <button type="button" class="apex-variant-button ${selected === value ? "selected" : ""}"
        data-apex-variant="${value}" aria-pressed="${selected === value}">${label}</button>
    `).join("");
    note.textContent = mobile
      ? `선택됨 · ${selectedLabel}`
      : `${review.generator} · ${selectedLabel}`;
    controls.querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      state.apexVfxSelections.set(skill.id, button.dataset.apexVariant);
      state.elapsed = 0;
      state.playing = !state.reducedMotion;
      state.lastFrame = 0;
      renderApexVariantControls();
      renderSkillList(false);
      renderHitMarkers();
      renderPhaseMarkers();
      renderDetails();
      updateSpriteFinderButton(skill);
      updatePlayButton();
      renderFrame();
    }));
  });
}

function renderDetails() {
  const skill = state.selected;
  const customPresentation = authoredSpritePresentation(skill.id);
  const signatureMeta = SIGNATURE_ROGUE_META.get(skill.id) ?? SIGNATURE_REMAINING_META.get(skill.id);
  const appTimings = appSkillDamageTimings(skill);
  const rows = [
    ["catalogId", skill.id], ["레벨 / 후보", `Lv.${skill.level} · C${skill.candidate + 1}`],
    ...(skill.heroClass === "WARRIOR" ? [["프레임 알파", "F01-F15 소재 알파 유지 · F15 유지 후 남은 시간 런타임 페이드 · F16 투명"]] : []),
    ...(APEX_CLASS_REWORK_VARIANTS.has(skill.id) ? [["이미지 시안", apexVfxSelectionLabel(skill.id)]] : []),
    ...(skill.heroClass === "ROGUE" && !APEX_CLASS_REWORK_VARIANTS.has(skill.id)
      ? [["확정 시안", rogueVfxSelectionLabel(skill.id)]]
      : []),
    ["Android 분기", skill.branchKey], ["행동", customPresentation?.action ?? skill.action], ["방향", customPresentation?.flow ?? skill.flow], ["원소", skill.element],
    ["경로", `${skill.path} · BAND ${skill.growthBand + 1}`], ["타격", `${skill.hits}회 · ${appTimings.join(", ")}ms`],
    ["표시 타격", appTimings.join(", ") + "ms"],
    ["타격 데이터", skill.hitTimingOverride ? "저장 오버라이드" : "앱 기본값"],
    ...(skill.id === BLADE_SLASH_ID
      ? [["스프라이트 연출", `F01-F05 예고 · F06-F08 순간 베기 · F09 주변 임팩트 · ${authoredSpriteEndingDetail(skill.id)}`]]
      : SWORD_MASTER_LATE_IMPACT_META.has(skill.id)
        ? [["스프라이트 연출", withAuthoredSpriteEnding(SWORD_MASTER_LATE_IMPACT_META.get(skill.id).detail, skill.id)]]
      : signatureMeta
        ? [["스프라이트 연출", `4×4 / 16프레임 · F01 예고 · F02-F07 ${signatureMeta.motion} · F08 ${signatureMeta.contact} · F09 ${signatureMeta.impact}/최대 피해 · F10-F14 ${signatureMeta.hold} · ${authoredSpriteEndingDetail(skill.id)}`]]
      : skill.id === SHIELD_BREAK_ID
        ? [["스프라이트 연출", `4×4 / 16프레임 · F01-F04 냉철 예고 · F05-F07 공중 절단 가속 · F08 주 베기 · F09 최대 강철 절단면/피해 표시 · F10-F14 잔압 감쇠 · ${authoredSpriteEndingDetail(skill.id)}`]]
      : skill.id === SHATTER_STRIKE_ID
        ? [["스프라이트 연출", `4×3 / 12프레임 · F01-F04 빠른 상단 직선 베기 · F05-F06 지면 충돌/피해 표시 · F07-F11 파편 정리 · ${authoredSpriteEndingDetail(skill.id)}`]]
      : SIGNATURE_WARRIOR_IDS.has(skill.id)
        ? [["스프라이트 연출", "F01 예고 · F02-F08 고유 동작 · F09 최대 타격 · F10-F16 즉시 감쇠/소실"]]
        : []),
  ];
  $("skillDetails").innerHTML = rows.map(([term, value]) => `<dt>${term}</dt><dd>${value}</dd>`).join("");
}

function selectSkill(skill, rerenderList = true) {
  state.selected = skill;
  state.selectedClass = skill.heroClass;
  if (skill.heroClass === "ROGUE") {
    document.documentElement.dataset.rogueVfxVariant = selectedRogueVfxVariant(skill.id);
  }
  state.elapsed = 0;
  // Respect the reduced-motion mode when switching skills. The user can still
  // start the preview explicitly with the play button.
  state.playing = !state.reducedMotion;
  state.lastFrame = 0;
  $("selectedSummary").textContent = `Lv.${skill.level} ${skill.name}`;
  $("heroLevel").textContent = skill.level;
  $("heroClass").textContent = skill.classLabel;
  $("combatPower").textContent = 120 + skill.level * 6;
  $("mobileSkillName").textContent = skill.name;
  $("mobileAction").textContent = authoredSpritePresentation(skill.id)?.label ?? `${skill.action} · ${skill.flow}`;
  $("layerCount").textContent = `0층 / 최소 피크 ${skill.minimumPeakConcurrent ?? 1}층 · 전체 ${skill.actualLayers}역할`;
  updateSpriteFinderButton(skill);
  if (rerenderList) renderSkillList(false);
  resetHitEditor(skill);
  renderHitMarkers();
  renderPhaseMarkers();
  renderRoleFocusControls();
  renderLayerToggles();
  renderApexVariantControls();
  renderDetails();
  updatePlayButton();
  loadFrames(skill).catch((error) => {
    $("syncStatus").textContent = "Android 프레임 연결 실패";
    $("syncStatus").style.color = "#df626b";
    console.error(error);
  });
}

function updatePlayButton() { $("playButton").textContent = state.playing ? "일시정지" : "재생"; }

function updatePreviewScale(scale, preserveCenter = false) {
  const viewport = $("previewViewport");
  const canvas = $("previewCanvas");
  const phone = $("phoneFrame");
  const previousScale = state.previewScale;
  const centerX = viewport.scrollLeft + viewport.clientWidth / 2;
  const centerY = viewport.scrollTop + viewport.clientHeight / 2;
  state.previewScale = scale;
  phone.style.transformOrigin = "top left";
  phone.style.transform = `scale(${scale})`;
  canvas.style.width = `${phone.offsetWidth * scale}px`;
  canvas.style.height = `${phone.offsetHeight * scale}px`;
  viewport.classList.toggle("zoomed", scale > 1);
  document.querySelectorAll(".zoom-button").forEach((button) => {
    const selected = Number(button.dataset.zoom) === scale;
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-pressed", String(selected));
  });
  if (preserveCenter && previousScale > 0) {
    const ratio = scale / previousScale;
    requestAnimationFrame(() => {
      viewport.scrollLeft = Math.max(0, centerX * ratio - viewport.clientWidth / 2);
      viewport.scrollTop = Math.max(0, centerY * ratio - viewport.clientHeight / 2);
    });
  } else viewport.scrollTo({ left: 0, top: 0 });
}

function animationLoop(timestamp) {
  try {
    if (state.playing && state.selected) {
      if (state.lastFrame) state.elapsed += (timestamp - state.lastFrame) * state.speed;
      if (state.elapsed >= APP_PRESENTATION_DURATION_MILLIS) state.elapsed %= APP_PRESENTATION_DURATION_MILLIS;
      renderFrame();
    }
  } catch (error) {
    state.playing = false;
    updatePlayButton();
    console.error("VFX preview frame render failed", error);
  } finally {
    state.lastFrame = timestamp;
    requestAnimationFrame(animationLoop);
  }
}

async function initialize() {
  const [response, remainingResponse, apexVariantsResponse, hitSettingsResponse] = await Promise.all([
    fetch("data/skills.json", { cache: "no-store" }),
    fetch("data/remaining-signature-skills.json", { cache: "no-store" }),
    fetch("data/apex-class-rework-variants.json", { cache: "no-store" }),
    fetch("/api/hit-settings", { cache: "no-store" }),
  ]);
  if (!response.ok || !remainingResponse.ok || !apexVariantsResponse.ok || !hitSettingsResponse.ok) throw new Error("VFX Lab skill metadata load failed");
  installRemainingSignatureSkills(await remainingResponse.json());
  installApexClassReworkVariants(await apexVariantsResponse.json());
  state.hitSettings = await hitSettingsResponse.json();
  state.payload = await response.json();
  state.skills = state.payload.skills
    .filter((skill) => WEB_REVIEW_SKILL_IDS.has(skill.id))
    .map((skill) => ({ ...skill, name: SIGNATURE_WARRIOR_NAMES.get(skill.id) ?? SIGNATURE_ROGUE_NAMES.get(skill.id) ?? SIGNATURE_REMAINING_NAMES.get(skill.id) ?? skill.name }))
    .map((skill) => applyHitSettingToSkill(skill, state.hitSettings.skills[skill.id]));
  const prefersReducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
  if (prefersReducedMotion) {
    state.reducedMotion = true;
    state.playing = false;
    $("reducedMotionToggle").checked = true;
  }
  $("timeline").max = APP_PRESENTATION_DURATION_MILLIS;
  renderClassTabs();
  renderRoleFocusControls();
  renderSkillList(true);
  $("searchInput").addEventListener("input", () => renderSkillList(false));
  $("revealSpriteButton").addEventListener("click", revealSelectedSprite);
  $("hitEditorToggle").addEventListener("click", () => {
    setHitEditorExpanded($("hitEditorToggle").getAttribute("aria-expanded") !== "true");
  });
  setHitEditorExpanded(false);
  $("hitCountInput").addEventListener("input", (event) => {
    const hitCount = Number(event.target.value);
    event.target.setAttribute("aria-invalid", "false");
    if (!Number.isInteger(hitCount) || hitCount < 1 || hitCount > 12) {
      event.target.setAttribute("aria-invalid", "true");
      setHitSaveStatus("타수는 1~12 정수여야 합니다", "error");
      return;
    }
    state.hitEditorDraft = { hitCount, timings: editorDefaultHitTimings(hitCount) };
    renderHitTimingInputs();
    renderHitMarkers();
    setHitSaveStatus("저장되지 않은 변경", "dirty");
  });
  $("saveHitSettingsButton").addEventListener("click", saveHitSettings);
  $("playButton").addEventListener("click", () => { state.playing = !state.playing; state.lastFrame = 0; updatePlayButton(); });
  $("replayButton").addEventListener("click", () => { state.elapsed = 0; state.playing = true; state.lastFrame = 0; updatePlayButton(); renderFrame(); });
  $("backFrameButton").addEventListener("click", () => { state.playing = false; state.elapsed = clamp(state.elapsed - 16, 0, APP_PRESENTATION_DURATION_MILLIS); updatePlayButton(); renderFrame(); });
  $("nextFrameButton").addEventListener("click", () => { state.playing = false; state.elapsed = clamp(state.elapsed + 16, 0, APP_PRESENTATION_DURATION_MILLIS); updatePlayButton(); renderFrame(); });
  $("speedSelect").addEventListener("change", (event) => { state.speed = Number(event.target.value); });
  $("reducedMotionToggle").addEventListener("change", (event) => {
    state.reducedMotion = event.target.checked;
    state.playing = !state.reducedMotion;
    state.elapsed = 0;
    state.lastFrame = 0;
    updatePlayButton();
    loadFrames(state.selected).catch((error) => {
      $("syncStatus").textContent = "Android 프레임 연결 실패";
      $("syncStatus").style.color = "#df626b";
      console.error(error);
    });
  });
  document.querySelectorAll(".zoom-button").forEach((button) => button.addEventListener("click", () => updatePreviewScale(Number(button.dataset.zoom), true)));
  const previewViewport = $("previewViewport");
  let panStart = null;
  previewViewport.addEventListener("pointerdown", (event) => {
    if (state.previewScale <= 1 || event.button !== 0) return;
    panStart = { x: event.clientX, y: event.clientY, left: previewViewport.scrollLeft, top: previewViewport.scrollTop };
    previewViewport.classList.add("panning");
    previewViewport.setPointerCapture(event.pointerId);
  });
  previewViewport.addEventListener("pointermove", (event) => {
    if (!panStart) return;
    previewViewport.scrollLeft = panStart.left - (event.clientX - panStart.x);
    previewViewport.scrollTop = panStart.top - (event.clientY - panStart.y);
  });
  const endPan = (event) => {
    if (!panStart) return;
    panStart = null;
    previewViewport.classList.remove("panning");
    if (previewViewport.hasPointerCapture(event.pointerId)) previewViewport.releasePointerCapture(event.pointerId);
  };
  previewViewport.addEventListener("pointerup", endPan);
  previewViewport.addEventListener("pointercancel", endPan);
  $("timeline").addEventListener("input", (event) => { state.playing = false; state.elapsed = Number(event.target.value); updatePlayButton(); renderFrame(); });
  updatePreviewScale(1);
  window.addEventListener("resize", () => updatePreviewScale(state.previewScale, true));
  requestAnimationFrame(animationLoop);
}

initialize().catch((error) => {
  $("syncStatus").textContent = "데이터 연결 실패";
  $("syncStatus").style.color = "#df626b";
  console.error(error);
});
