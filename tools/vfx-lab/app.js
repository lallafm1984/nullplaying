const CLASS_ORDER = ["WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN"];
const ELEMENT_COLORS = {
  PHYSICAL: ["#e1b95f", "rgba(141,111,64,.22)"], FIRE: ["#ff8b5b", "rgba(176,52,24,.26)"],
  ICE: ["#8ad6ff", "rgba(55,131,186,.24)"], LIGHTNING: ["#a9b7ff", "rgba(81,76,201,.25)"],
  ARCANE: ["#cf8bea", "rgba(130,67,172,.25)"], DARK: ["#a883d7", "rgba(76,40,110,.25)"],
  POISON: ["#83dd83", "rgba(46,126,54,.24)"], WIND: ["#8fe3cf", "rgba(58,139,121,.22)"],
  EARTH: ["#d1a66f", "rgba(132,91,51,.24)"], HOLY: ["#ffe5a4", "rgba(201,151,54,.22)"],
  COSMIC: ["#d9a4ff", "rgba(95,57,180,.27)"],
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

const CREATION_EARTHQUAKE_ID = "warrior_t20_c04";
const OCEAN_SEVER_ID = "warrior_t19_c01";
const BLADE_SLASH_ID = "warrior_t01_c01";
const SHIELD_BREAK_ID = "warrior_t02_c03";
const SHATTER_STRIKE_ID = "warrior_t03_c02";
const CROSS_SLASH_ID = "warrior_t05_c02";
const OCEAN_SEVER_SHEET = "custom-assets/astral-cartographer-hit-sheet.png";
const OCEAN_SEVER_COLUMNS = 4;
const OCEAN_SEVER_ROWS = 3;
const OCEAN_SEVER_FRAME_COUNT = OCEAN_SEVER_COLUMNS * OCEAN_SEVER_ROWS;
const OCEAN_SEVER_FRAME_MILLIS = 70;
const CURRENT_CHARGE_SPRITES = new Map([
  ["warrior_t01_c03", "custom-assets/warrior-charge/warrior_t01_c03.png"],
  ["warrior_t02_c03", "custom-assets/warrior-charge/warrior_t02_c03.png"],
  ["warrior_t03_c03", "custom-assets/warrior-charge-revision/warrior_t03_c03.png"],
  ["warrior_t04_c03", "custom-assets/warrior-charge/warrior_t04_c03.png"],
  ["warrior_t05_c03", "custom-assets/warrior-charge/warrior_t05_c03.png"],
  ["warrior_t06_c03", "custom-assets/warrior-charge/warrior_t06_c03.png"],
  ["warrior_t07_c03", "custom-assets/warrior-charge/warrior_t07_c03.png"],
  ["warrior_t08_c03", "custom-assets/warrior-charge-revision/warrior_t08_c03.png"],
  ["warrior_t09_c03", "custom-assets/warrior-charge/warrior_t09_c03.png"],
  ["warrior_t10_c03", "custom-assets/warrior-charge-revision/warrior_t10_c03.png"],
  ["warrior_t11_c03", "custom-assets/warrior-charge/warrior_t11_c03.png"],
  ["warrior_t12_c03", "custom-assets/warrior-charge/warrior_t12_c03.png"],
  ["warrior_t13_c03", "custom-assets/warrior-charge-revision/warrior_t13_c03.png"],
  ["warrior_t14_c03", "custom-assets/warrior-charge-revision/warrior_t14_c03.png"],
  ["warrior_t15_c03", "custom-assets/warrior-charge-revision/warrior_t15_c03.png"],
  ["warrior_t16_c03", "custom-assets/warrior-charge-revision/warrior_t16_c03.png"],
  ["warrior_t17_c03", "custom-assets/warrior-charge-revision/warrior_t17_c03.png"],
  ["warrior_t18_c03", "custom-assets/warrior-charge-revision/warrior_t18_c03.png"],
  ["warrior_t19_c03", "custom-assets/warrior-charge/warrior_t19_c03.png"],
  ["warrior_t20_c03", "custom-assets/warrior-charge-revision/warrior_t20_c03.png"],
]);

function classFamilySpriteEntries(heroClass, candidate, folder) {
  return Array.from({ length: 20 }, (_, index) => {
    const tier = String(index + 1).padStart(2, "0");
    const catalogId = `${heroClass}_t${tier}_${candidate}`;
    return [catalogId, `custom-assets/${folder}/${catalogId}.png`];
  });
}

const warriorFamilySpriteEntries = (candidate, folder) => classFamilySpriteEntries("warrior", candidate, folder);
const rogueFamilySpriteEntries = (candidate, folder) => classFamilySpriteEntries("rogue", candidate, folder);

const SIGNATURE_WARRIOR_SPRITES = new Map([
  ["warrior_t01_c01", "custom-assets/warrior-t01-blade-slash-snap-impact-revision/warrior_t01_c01.png"],
  [SHIELD_BREAK_ID, "custom-assets/warrior-t02-shield-break-rightward-clean-regeneration/warrior_t02_c03.png"],
  [SHATTER_STRIKE_ID, "custom-assets/warrior-t03-shatter-strike-sword-late-impact/warrior_t03_c02.png"],
  ["warrior_t04_c03", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t04_c03.png"],
  [CROSS_SLASH_ID, "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t05_c02.png"],
  ["warrior_t06_c05", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t06_c05.png"],
  ["warrior_t07_c01", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t07_c01.png"],
  ["warrior_t08_c02", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t08_c02.png"],
  ["warrior_t09_c03", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t09_c03.png"],
  ["warrior_t10_c01", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t10_c01.png"],
  ["warrior_t11_c02", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t11_c02.png"],
  ["warrior_t12_c04", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t12_c04.png"],
  ["warrior_t13_c03", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t13_c03.png"],
  ["warrior_t14_c03", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t14_c03.png"],
  ["warrior_t15_c01", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t15_c01.png"],
  ["warrior_t16_c05", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t16_c05.png"],
  ["warrior_t17_c01", "custom-assets/warrior-sword-master-04-20-late-impact/warrior_t17_c01.png"],
  ["warrior_t18_c02", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t18_c02.png"],
  ["warrior_t19_c02", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t19_c02.png"],
  ["warrior_t20_c01", "custom-assets/warrior-sword-master-04-20-late-impact-revisions/warrior_t20_c01.png"],
]);
const SIGNATURE_WARRIOR_IDS = new Set([BLADE_SLASH_ID, ...SIGNATURE_WARRIOR_SPRITES.keys()]);
const WEB_REVIEW_SKILL_IDS = SIGNATURE_WARRIOR_IDS;
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
  ["warrior_t17_c01", "최후의 일격"],
  ["warrior_t18_c02", "파멸의 검"],
  ["warrior_t19_c02", "천지 가르기"],
  ["warrior_t20_c01", "천하대양단"],
]);

const SWORD_MASTER_LATE_IMPACT_META = new Map([
  ["warrior_t04_c03", { action: "EARTH_SEAM_CLEAVE", flow: "LOW_LEFT_EARTH_SEAM_CLEAVE", label: "SWORD · EARTH_SEAM", prep: "대검 압축", motion: "대각 절단", contact: "지면 접촉", impact: "단층 절개", hold: "균열 유지", detail: "F01-F09 대검 압축 · F10-F12 우상단에서 좌하단 대각 절단 · F13 첫 지면 접촉 · F15 단층 절개 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t05_c02", { action: "CROSS_SLASH", flow: "OFFSET_TWO_STROKE_CROSS_CUT", label: "SWORD · CROSS_CUT", prep: "교차 준비", motion: "2연 참격", contact: "교차 접촉", impact: "십자 파열", hold: "교차 유지", detail: "F01-F09 교차 준비 · F10-F12 한 자루 검의 2연 참격 · F13 두 번째 검날 접촉 · F15 비대칭 십자 파열 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t06_c05", { action: "STORM_SLASH", flow: "BROKEN_WIND_FAN_SIX_CUT", label: "SWORD · STORM_FAN", prep: "폭풍 축적", motion: "연속 풍절", contact: "폭풍 접촉", impact: "6단 풍절", hold: "폭풍 유지", detail: "F01-F09 폭풍 축적 · F10-F12 단일 검의 분리된 연속 풍절 · F13 첫 접촉 · F15 6단 개방형 폭풍 파열 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t07_c01", { action: "IRON_SWORD_LUNGE", flow: "FORESHORTENED_IRON_SWORD_LUNGE", label: "SWORD · IRON_LUNGE", prep: "찌르기 압축", motion: "철갑 돌진", contact: "관통 접촉", impact: "철판 관통", hold: "관통 유지", detail: "F01-F09 찌르기 압축 · F10-F12 검끝 우측 돌진 · F13 철판 첫 접촉 · F15 철판 관통 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t08_c02", { action: "BATTLEFIELD_RUSH_CUT", flow: "LOW_BATTLEFIELD_SWORD_RUSH", label: "SWORD · BATTLE_RUSH", prep: "낮은 자세", motion: "전장 돌격", contact: "갑주 접촉", impact: "전장 절개", hold: "절개 유지", detail: "F01-F09 낮은 검 자세 · F10-F12 좌우 수평 돌격 베기 · F13 갑주 첫 접촉 · F15 전장 절개 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t09_c03", { action: "WHIRLWIND_SLASH", flow: "OPEN_CRESCENT_TURNING_CUT", label: "SWORD · OPEN_WHIRL", prep: "회전 준비", motion: "개방 회전", contact: "회오리 접촉", impact: "곡풍 파열", hold: "회오리 유지", detail: "F01-F09 개방형 회전 준비 · F10-F12 240도 곡선 베기 · F13 첫 접촉 · F15 비대칭 곡풍 파열 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t10_c01", { action: "EARTH_SHATTER_SWORD", flow: "VERTICAL_SWORD_FAULT_DRIVE", label: "SWORD · EARTH_SHATTER", prep: "수직 압축", motion: "검 강하", contact: "대지 접촉", impact: "대지 분쇄", hold: "분쇄 유지", detail: "F01-F09 수직 검 압축 · F10-F12 순간 수직 강하 · F13 대지 첫 접촉 · F15 삼중 대지 분쇄 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t11_c02", { action: "TEMPEST_SWORD", flow: "LIGHTNING_GALE_SWORD_DIAGONAL", label: "SWORD · TEMPEST_EDGE", prep: "폭풍검 축적", motion: "상승 일섬", contact: "폭풍 접촉", impact: "폭풍 절개", hold: "폭풍검 유지", detail: "F01-F09 폭풍검 축적 · F10-F12 좌하단에서 우상단 상승 일섬 · F13 첫 접촉 · F15 폭풍 절개 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t12_c04", { action: "FLASH_IASSEN", flow: "INSTANT_WHITE_DRAW_CUT", label: "SWORD · FLASH_IASSEN", prep: "발도 압축", motion: "섬광 이동", contact: "찰나 접촉", impact: "일섬 분리", hold: "일섬 유지", detail: "F01-F09 발도 압축 · F10-F12 초고속 수평 일섬 · F13 찰나의 첫 접촉 · F15 무음 분리 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t13_c03", { action: "SHADOWLESS_COMBO", flow: "SHADOWLESS_NOMADIC_COMBO", label: "SWORD · SHADOWLESS", prep: "무영 준비", motion: "다중 궤적", contact: "최종 접촉", impact: "무영 파열", hold: "무영 유지", detail: "F01-F09 분리된 무영 궤적 준비 · F10-F12 세 번째 이동 · F13 최종 첫 접촉 · F15 무영 연참 파열 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t14_c03", { action: "DRAGONSLAYER_SWORD", flow: "DRAGON_SCALE_ASCENDING_BREAK", label: "SWORD · DRAGONSCALE_BREAK", prep: "대검 축적", motion: "용린 상승", contact: "용린 접촉", impact: "용린 파쇄", hold: "용살 유지", detail: "F01-F09 용살 대검 축적 · F10-F12 상승 베기 · F13 용린 첫 접촉 · F15 삼중 용린 파쇄 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t15_c01", { action: "HEAVEN_DESTROY_IASSEN", flow: "SKYFALL_SINGLE_VERTICAL_SEVER", label: "SWORD · HEAVEN_SEVER", prep: "천공 압축", motion: "수직 일섬", contact: "천지 접촉", impact: "멸천 분리", hold: "멸천 유지", detail: "F01-F09 천공 압축 · F10-F12 수직 일섬 · F13 첫 접촉 · F15 상하 압력면 분리 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t16_c05", { action: "BOUNDLESS_IASSEN", flow: "BOUNDLESS_BROKEN_ROUTE_IASSEN", label: "SWORD · BOUNDLESS_ROUTE", prep: "무극 준비", motion: "삼단 궤적", contact: "무극 접촉", impact: "무극 절단", hold: "무극 유지", detail: "F01-F09 무극 준비 · F10-F12 분리된 삼단 궤적 · F13 첫 접촉 · F15 무극 절단 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t17_c01", { action: "FINAL_EXECUTION_STRIKE", flow: "EXECUTIONER_FINAL_DESCENT", label: "SWORD · FINAL_DESCENT", prep: "최후 축적", motion: "처형 강하", contact: "최후 접촉", impact: "최종 파쇄", hold: "최후 유지", detail: "F01-F09 최후의 대검 축적 · F10-F12 처형 강하 · F13 첫 접촉 · F15 최종 파쇄 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t18_c02", { action: "DOOM_SWORD", flow: "OBSIDIAN_DOOM_RIFT_CUT", label: "SWORD · DOOM_RIFT", prep: "파멸 축적", motion: "흑요 절단", contact: "봉인 접촉", impact: "파멸 균열", hold: "파멸 유지", detail: "F01-F09 흑요 대검 축적 · F10-F12 파멸 절단 · F13 봉인 첫 접촉 · F15 파멸 균열 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t19_c02", { action: "HEAVEN_EARTH_SEVER", flow: "HEAVEN_EARTH_DUAL_FIELD_SEVER", label: "SWORD · HEAVEN_EARTH", prep: "천지 압축", motion: "천지 관통", contact: "대지 접촉", impact: "천지 분리", hold: "천지 유지", detail: "F01-F09 천지 이중 압력 축적 · F10-F12 수직 관통 · F13 대지 첫 접촉 · F15 천지 분리 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }],
  ["warrior_t20_c01", { action: "WORLD_OCEAN_SEVER", flow: "WORLD_OCEAN_HORIZONTAL_SOVEREIGN_SEVER", label: "SWORD · WORLD_OCEAN", prep: "대양 압축", motion: "천하 횡단", contact: "대양 접촉", impact: "천하대양단", hold: "대양 유지", detail: "F01-F09 삼중 대양 압력 축적 · F10-F12 전 화면 수평 대검 횡단 · F13 대양 첫 접촉 · F15 천하대양단 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃" }]
]);
const SWORD_MASTER_LATE_IMPACT_IDS = new Set(SWORD_MASTER_LATE_IMPACT_META.keys());

const WARRIOR_SPRITES = new Map([
  ...CURRENT_CHARGE_SPRITES,
  ...warriorFamilySpriteEntries("c01", "warrior-cleave"),
  ...warriorFamilySpriteEntries("c02", "warrior-crush"),
  ...warriorFamilySpriteEntries("c04", "warrior-earth"),
  ...warriorFamilySpriteEntries("c05", "warrior-flurry"),
  [BLADE_SLASH_ID, "custom-assets/warrior-t01-blade-slash-snap-impact-revision/warrior_t01_c01.png"],
  ["warrior_t01_c02", "custom-assets/warrior-crush-coverage-revision/warrior_t01_c02.png"],
  ["warrior_t04_c01", "custom-assets/warrior-motion-slicing-revision/warrior_t04_c01.png"],
  ["warrior_t05_c01", "custom-assets/warrior-motion-slicing-revision/warrior_t05_c01.png"],
  ["warrior_t07_c01", "custom-assets/warrior-motion-slicing-revision/warrior_t07_c01.png"],
  ["warrior_t12_c01", "custom-assets/warrior-motion-slicing-revision/warrior_t12_c01.png"],
  ["warrior_t01_c05", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t01_c05.png"],
  ["warrior_t05_c04", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t05_c04.png"],
  ["warrior_t08_c04", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t08_c04.png"],
  ["warrior_t10_c01", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t10_c01.png"],
  ["warrior_t14_c04", "custom-assets/warrior-t14-decay-revision/warrior_t14_c04.png"],
  ["warrior_t16_c02", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t16_c02.png"],
  ["warrior_t17_c05", "custom-assets/warrior-t17-route-revision/warrior_t17_c05.png"],
  ["warrior_t18_c04", "custom-assets/warrior-t18-cellband-revision/warrior_t18_c04.png"],
  ["warrior_t20_c02", "custom-assets/warrior-spatial-diversity-second-pass/warrior_t20_c02.png"],
  ["warrior_t06_c02", "custom-assets/warrior-spatial-diversity-revision/warrior_t06_c02.png"],
  ["warrior_t03_c03", "custom-assets/warrior-motion-slicing-revision/warrior_t03_c03.png"],
  ["warrior_t13_c03", "custom-assets/warrior-motion-slicing-revision/warrior_t13_c03.png"],
  ...SIGNATURE_WARRIOR_SPRITES,
]);
// Promote a family only after all of its PNGs pass the fixed sheet contract.
const WARRIOR_READY_SPRITES = new Set([
  ...CURRENT_CHARGE_SPRITES.keys(),
  ...warriorFamilySpriteEntries("c01", "warrior-cleave").map(([catalogId]) => catalogId),
  ...warriorFamilySpriteEntries("c02", "warrior-crush").map(([catalogId]) => catalogId),
  ...warriorFamilySpriteEntries("c04", "warrior-earth").map(([catalogId]) => catalogId),
  ...warriorFamilySpriteEntries("c05", "warrior-flurry").map(([catalogId]) => catalogId),
]);
const WARRIOR_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "CLEAVE", flow: "SOVEREIGN_RIFT_CLEAVE", label: "CLEAVE · SOVEREIGN_RIFT" }],
  ["c02", { action: "CRUSH", flow: "TITAN_SKYFALL_CRUSH", label: "CRUSH · TITAN_SKYFALL" }],
  ["c03", { action: "CHARGE", flow: "CENTER_BREAKTHROUGH", label: "CHARGE · CENTER_BREAKTHROUGH" }],
  ["c04", { action: "RUPTURE", flow: "TECTONIC_REBIRTH", label: "RUPTURE · TECTONIC_REBIRTH" }],
  ["c05", { action: "FLURRY", flow: "CENTERED_BATTLE_TEMPEST", label: "FLURRY · CENTERED_TEMPEST" }],
]);

const ROGUE_SPRITES = new Map([
  ...rogueFamilySpriteEntries("c01", "rogue-needle-crown"),
  ...rogueFamilySpriteEntries("c02", "rogue-umbra-collapse"),
  ...rogueFamilySpriteEntries("c03", "rogue-venom-helix"),
  ...rogueFamilySpriteEntries("c04", "rogue-snare-convergence"),
  ...rogueFamilySpriteEntries("c05", "rogue-heartseal-execution"),
]);
// Add a rogue catalogId only after its downloaded PNG passes the fixed sheet contract.
const ROGUE_READY_SPRITES = new Set([
  "rogue_t01_c01",
  "rogue_t03_c02",
  "rogue_t07_c01",
  "rogue_t08_c01",
  "rogue_t09_c02",
  "rogue_t10_c03",
]);
const ROGUE_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "NEEDLE", flow: "PHANTOM_NEEDLE_CROWN", label: "NEEDLE · PHANTOM_CROWN" }],
  ["c02", { action: "AMBUSH", flow: "UMBRAL_AFTERIMAGE_COLLAPSE", label: "AMBUSH · UMBRAL_COLLAPSE" }],
  ["c03", { action: "VENOM", flow: "VENOM_HELIX_BLOOM", label: "VENOM · HELIX_BLOOM" }],
  ["c04", { action: "SNARE", flow: "SILENT_SNARE_CONVERGENCE", label: "SNARE · SILENT_CONVERGENCE" }],
  ["c05", { action: "EXECUTE", flow: "HEARTSEAL_EXECUTION", label: "EXECUTE · HEARTSEAL" }],
]);

const RANGER_SPRITES = new Map([
  ...classFamilySpriteEntries("ranger", "c01", "ranger-horizon-pinpoint"),
  ...classFamilySpriteEntries("ranger", "c02", "ranger-skyburst-volley"),
  ...classFamilySpriteEntries("ranger", "c03", "ranger-tempest-string"),
  ...classFamilySpriteEntries("ranger", "c04", "ranger-predator-domain"),
  ...classFamilySpriteEntries("ranger", "c05", "ranger-lunar-constellation"),
]);
const RANGER_READY_SPRITES = new Set([]);
const RANGER_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "SNIPE", flow: "HORIZON_PINPOINT_RUPTURE", label: "SNIPE · HORIZON_PINPOINT" }],
  ["c02", { action: "VOLLEY", flow: "SKYBURST_VOLLEY_VAULT", label: "VOLLEY · SKYBURST_VAULT" }],
  ["c03", { action: "TEMPEST", flow: "TEMPEST_BOWSTRING_SINGULARITY", label: "TEMPEST · STRING_SINGULARITY" }],
  ["c04", { action: "HUNT", flow: "PREDATOR_SNARE_DOMAIN", label: "HUNT · PREDATOR_DOMAIN" }],
  ["c05", { action: "CONSTELLATION", flow: "LUNAR_CONSTELLATION_PIERCER", label: "CONSTELLATION · LUNAR_PIERCER" }],
]);

const MAGE_SPRITES = new Map([
  ...classFamilySpriteEntries("mage", "c01", "mage-solar-forge"),
  ...classFamilySpriteEntries("mage", "c02", "mage-cryostasis-cathedral"),
  ...classFamilySpriteEntries("mage", "c03", "mage-thunder-circuit"),
  ...classFamilySpriteEntries("mage", "c04", "mage-arcane-geometry"),
  ...classFamilySpriteEntries("mage", "c05", "mage-cosmic-orbital"),
]);
const MAGE_READY_SPRITES = new Set([]);
const MAGE_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "PYRE", flow: "SOLAR_FORGE_DETONATION", label: "PYRE · SOLAR_FORGE" }],
  ["c02", { action: "CRYOSTASIS", flow: "CRYOSTASIS_CATHEDRAL_SHATTER", label: "CRYOSTASIS · CATHEDRAL_SHATTER" }],
  ["c03", { action: "THUNDER", flow: "THUNDER_CIRCUIT_JUDGMENT", label: "THUNDER · CIRCUIT_JUDGMENT" }],
  ["c04", { action: "ARCANE", flow: "ARCANE_GEOMETRY_COLLAPSE", label: "ARCANE · GEOMETRY_COLLAPSE" }],
  ["c05", { action: "COSMIC", flow: "COSMIC_ORBITAL_CATACLYSM", label: "COSMIC · ORBITAL_CATACLYSM" }],
]);

const CLERIC_SPRITES = new Map([
  ...classFamilySpriteEntries("cleric", "c01", "cleric-dawn-revelation"),
  ...classFamilySpriteEntries("cleric", "c02", "cleric-celestial-verdict"),
  ...classFamilySpriteEntries("cleric", "c03", "cleric-exorcism-seal"),
  ...classFamilySpriteEntries("cleric", "c04", "cleric-sacred-flame"),
  ...classFamilySpriteEntries("cleric", "c05", "cleric-angelic-host"),
]);
const CLERIC_READY_SPRITES = new Set([]);
const CLERIC_SPRITE_PRESENTATIONS = new Map([
  ["c01", { action: "REVELATION", flow: "DAWN_REVELATION_BEAM", label: "REVELATION · DAWN_BEAM" }],
  ["c02", { action: "VERDICT", flow: "CELESTIAL_VERDICT_DESCENT", label: "VERDICT · CELESTIAL_DESCENT" }],
  ["c03", { action: "EXORCISE", flow: "EXORCISM_SEAL_CONSUMPTION", label: "EXORCISE · SEAL_CONSUMPTION" }],
  ["c04", { action: "SACRED_FLAME", flow: "SACRED_FLAME_ASCENSION", label: "SACRED_FLAME · ASCENSION" }],
  ["c05", { action: "HOST", flow: "ANGELIC_HOST_CONVERGENCE", label: "HOST · ANGELIC_CONVERGENCE" }],
]);

const PALADIN_SPRITES = new Map([
  ...classFamilySpriteEntries("paladin", "c01", "paladin-consecrated-rift"),
  ...classFamilySpriteEntries("paladin", "c02", "paladin-aegis-citadel"),
  ...classFamilySpriteEntries("paladin", "c03", "paladin-judgment-gravity"),
  ...classFamilySpriteEntries("paladin", "c04", "paladin-aurora-vow"),
  ...classFamilySpriteEntries("paladin", "c05", "paladin-sovereign-oath"),
]);
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
// Newly reworked warrior skills use this ending unless a skill explicitly asks
// for a different finish: hold F16 through 1099ms, then fade out to 1350ms.
const WARRIOR_STANDARD_ENDING_IDS = new Set([
  SHIELD_BREAK_ID,
  SHATTER_STRIKE_ID,
  ...SWORD_MASTER_LATE_IMPACT_IDS,
]);
const WARRIOR_SPRITE_FADE_START_MILLIS = 1100;
const WARRIOR_SPRITE_HIDE_MILLIS = 1350;
const AUTHORED_SPRITE_PRESENTATION_OVERRIDES = new Map([
  [BLADE_SLASH_ID, {
    action: "SNAP_TOP_DOWN_SLASH",
    flow: "LOCKED_SPLINE_SNAP_CUT_IMPACT",
    label: "SNAP_SLASH · IMPACT_500MS",
  }],
  [SHIELD_BREAK_ID, { action: "SHIELD_RAM", flow: "CONNECTED_SHIELD_RAM_LATE_IMPACT", label: "SHIELD · RAM_LATE_IMPACT" }],
  [SHATTER_STRIKE_ID, { action: "SWORD_SHATTER", flow: "LOCKED_GREATSWORD_SHATTER_CUT_LATE_IMPACT", label: "SWORD · SHATTER_CUT" }],
  ...Array.from(SWORD_MASTER_LATE_IMPACT_META, ([skillId, meta]) => [skillId, {
    action: meta.action,
    flow: meta.flow,
    label: meta.label,
  }]),
]);
const AUTHORED_DAMAGE_DELAY_MILLIS = new Map([
  [SHIELD_BREAK_ID, 375],
  [SHATTER_STRIKE_ID, 375],
  [CROSS_SLASH_ID, 375],
]);

function authoredSpritePresentation(skillId) {
  if (!AUTHORED_READY_SPRITES.has(skillId)) return null;
  const override = AUTHORED_SPRITE_PRESENTATION_OVERRIDES.get(skillId);
  if (override) return override;
  const heroClass = skillId.split("_", 1)[0];
  return AUTHORED_SPRITE_PRESENTATIONS.get(heroClass)?.get(skillId.slice(-3)) ?? null;
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
  layers: { secondary: true, primary: true, impact: true, debris: true, residual: true, label: true, damage: true },
};

const $ = (id) => document.getElementById(id);
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const fraction = (value, start, end) => clamp((value - start) / Math.max(1, end - start), 0, 1);
const assetUrl = (filename) => filename.startsWith("custom-assets/") ? `/${filename}` : `/assets/${filename}`;
const sourceImageCache = new Map();
const renderedAssetCache = new Map();

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
      const sliceAlpha = frame.a;
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

function standardEndingDamagePresentation(skill, elapsed) {
  const source = state.selectedPresentation.get(skill.timings.at(-1));
  if (!source) return null;
  const fadeIn = fraction(elapsed, 795, 875);
  const fadeOut = 1 - fraction(elapsed, WARRIOR_SPRITE_FADE_START_MILLIS, 1300);
  const alpha = clamp(Math.min(fadeIn, fadeOut), 0, 1);
  return {
    ...source,
    damageVisible: alpha > 0,
    damageFinal: true,
    damageAlpha: alpha,
    damageScale: .88 + .12 * fadeIn,
    damageY: 8 - 14 * fadeIn,
  };
}

function renderFrame() {
  const skill = state.selected;
  if (!skill) return;
  const elapsed = Math.round(state.elapsed);
  $("timeline").value = elapsed;
  $("timeOutput").textContent = `${elapsed}ms`;
  const sampleTime = exactSampleTime(elapsed);
  const authoredSpriteActive = AUTHORED_READY_SPRITES.has(skill.id) && state.layers.primary && (state.roleFocus === "ALL" || state.roleFocus === "PRIMARY");
  const oceanSeverActive = !authoredSpriteActive && skill.id === OCEAN_SEVER_ID && state.layers.primary && (state.roleFocus === "ALL" || state.roleFocus === "PRIMARY");
  const activeLayerState = syncFrameNodes(oceanSeverActive || authoredSpriteActive ? [] : (state.selectedFrames.get(sampleTime) ?? []));
  renderAuthoredSprite(authoredSpriteActive, elapsed, skill.id);
  renderOceanSeverSprite(oceanSeverActive, elapsed);
  const creationEarthquakeActive = !authoredSpriteActive && skill.id === CREATION_EARTHQUAKE_ID;
  $("combatCard").classList.toggle("creation-earthquake-active", creationEarthquakeActive);
  const minimumPeak = skill.minimumPeakConcurrent ?? skill.normalPeakConcurrent ?? 1;
  $("layerCount").textContent = `${activeLayerState.count}층 · ${activeLayerState.roles.length}역할 / 최소 피크 ${minimumPeak}층 · 전체 ${skill.actualLayers}역할`;

  const presentation = state.selectedPresentation.get(sampleTime);
  const damageDelay = AUTHORED_DAMAGE_DELAY_MILLIS.get(skill.id) ?? 0;
  const damageSampleTime = exactSampleTime(Math.max(0, elapsed - damageDelay));
  const damagePresentation = WARRIOR_STANDARD_ENDING_IDS.has(skill.id)
    ? standardEndingDamagePresentation(skill, elapsed)
    : damageDelay > 0
      ? state.selectedPresentation.get(damageSampleTime)
      : presentation;
  const damageNode = $("damageNumber");
  damageNode.textContent = damagePresentation?.damageVisible ? `-${damagePresentation.damage.toLocaleString("ko-KR")}` : "";
  damageNode.style.opacity = state.layers.damage && damagePresentation?.damageVisible ? damagePresentation.damageAlpha : 0;
  damageNode.style.fontSize = `${skill.hits === 1 ? 46 : damagePresentation?.damageFinal ? (skill.hits <= 3 ? 42 : 40) : skill.hits === 2 ? 38 : skill.hits === 3 ? 34 : skill.hits === 4 ? 31 : 28}px`;
  damageNode.style.transform = `translate(-50%, -50%) translateY(${damagePresentation?.damageY ?? 0}px) scale(${damagePresentation?.damageScale ?? 1})`;

  const colors = ELEMENT_COLORS[skill.element] ?? ELEMENT_COLORS.PHYSICAL;
  const labelNode = $("skillLabel");
  labelNode.textContent = skill.name;
  labelNode.style.opacity = state.layers.label ? (presentation?.labelAlpha ?? 0) : 0;
  labelNode.style.borderColor = `${colors[0]}73`;
  damageNode.style.color = colors[0];
  const backdropAlpha = elapsed < 110 ? elapsed / 110 : elapsed < skill.timings.at(-1) + 100 ? 1 : 1 - fraction(elapsed, skill.timings.at(-1) + 100, 1300);
  $("elementBackdrop").style.background = creationEarthquakeActive
    ? "radial-gradient(circle at 50% 76%, rgba(255, 58, 18, .92), rgba(120, 8, 18, .55) 42%, transparent 72%)"
    : `radial-gradient(circle at 50% 72%, ${colors[1]}, transparent 64%)`;
  $("elementBackdrop").style.opacity = clamp(backdropAlpha, 0, 1) * (creationEarthquakeActive ? .58 : .055);
  $("energyFill").style.width = `${(presentation?.energy ?? .78) * 100}%`;
  const camera = presentation ?? { cameraX: 0, cameraY: 0, cameraScale: 1 };
  $("cameraPlane").style.transform = `translate(${camera.cameraX}px, ${camera.cameraY}px) scale(${camera.cameraScale * 1.02})`;
}

function authoredSpriteOpacity(skillId, elapsed) {
  if (!WARRIOR_STANDARD_ENDING_IDS.has(skillId)) return 1;
  return 1 - fraction(elapsed, WARRIOR_SPRITE_FADE_START_MILLIS, WARRIOR_SPRITE_HIDE_MILLIS);
}

function renderAuthoredSprite(active, elapsed, skillId) {
  const canvas = $("chargeSprite");
  const sheetAsset = AUTHORED_SPRITES.get(skillId);
  const hideAtMillis = WARRIOR_STANDARD_ENDING_IDS.has(skillId)
    ? WARRIOR_SPRITE_HIDE_MILLIS
    : AUTHORED_SPRITE_FRAME_COUNT * AUTHORED_SPRITE_FRAME_MILLIS;
  if (!active || !sheetAsset || elapsed >= hideAtMillis) {
    canvas.style.opacity = "0";
    return;
  }
  const frameIndex = Math.min(AUTHORED_SPRITE_FRAME_COUNT - 1, Math.floor(elapsed / AUTHORED_SPRITE_FRAME_MILLIS));
  const renderKey = `${skillId}-${frameIndex}`;
  const spriteOpacity = authoredSpriteOpacity(skillId, elapsed);
  if (canvas.dataset.renderKey === renderKey) {
    canvas.style.opacity = String(spriteOpacity);
    return;
  }
  canvas.dataset.renderKey = renderKey;
  renderedAsset(sheetAsset, "AUTHORED_SCREEN", null).then((sheet) => {
    if (canvas.dataset.renderKey !== renderKey) return;
    const frameWidth = sheet.width / AUTHORED_SPRITE_COLUMNS;
    const frameHeight = sheet.height / AUTHORED_SPRITE_ROWS;
    const sourceX = (frameIndex % AUTHORED_SPRITE_COLUMNS) * frameWidth;
    const sourceY = Math.floor(frameIndex / AUTHORED_SPRITE_COLUMNS) * frameHeight;
    const context = canvas.getContext("2d", { alpha: true });
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(sheet, sourceX, sourceY, frameWidth, frameHeight, 0, 0, canvas.width, canvas.height);
    canvas.style.opacity = String(authoredSpriteOpacity(skillId, state.elapsed));
  }).catch((error) => {
    canvas.style.opacity = "0";
    console.error("Authored sprite sheet load failed", error);
  });
}

function renderOceanSeverSprite(active, elapsed) {
  const canvas = $("oceanSeverSprite");
  if (!active || elapsed >= OCEAN_SEVER_FRAME_COUNT * OCEAN_SEVER_FRAME_MILLIS) {
    canvas.style.opacity = "0";
    return;
  }
  const frameIndex = Math.min(OCEAN_SEVER_FRAME_COUNT - 1, Math.floor(elapsed / OCEAN_SEVER_FRAME_MILLIS));
  const renderKey = `ocean-sever-${frameIndex}`;
  if (canvas.dataset.renderKey === renderKey) {
    canvas.style.opacity = "1";
    return;
  }
  canvas.dataset.renderKey = renderKey;
  renderedAsset(OCEAN_SEVER_SHEET, "AUTHORED_SCREEN", null).then((sheet) => {
    if (canvas.dataset.renderKey !== renderKey) return;
    const frameWidth = sheet.width / OCEAN_SEVER_COLUMNS;
    const frameHeight = sheet.height / OCEAN_SEVER_ROWS;
    const sourceX = (frameIndex % OCEAN_SEVER_COLUMNS) * frameWidth;
    const sourceY = Math.floor(frameIndex / OCEAN_SEVER_COLUMNS) * frameHeight;
    const context = canvas.getContext("2d", { alpha: true });
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(sheet, sourceX, sourceY, frameWidth, frameHeight, 0, 0, canvas.width, canvas.height);
    canvas.style.opacity = "1";
  }).catch((error) => {
    canvas.style.opacity = "0";
    console.error("Ocean Sever sprite sheet load failed", error);
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
  return state.skills.filter((skill) => skill.heroClass === state.selectedClass && (!query || skill.name.toLowerCase().includes(query) || skill.id.includes(query)));
}

function renderSkillList(selectFirst = false) {
  const skills = filteredSkills();
  if (selectFirst && skills.length) selectSkill(skills[0], false);
  $("skillCount").textContent = `${skills.length} / ${state.skills.length}`;
  $("skillList").innerHTML = skills.map((skill) => `
    <button class="skill-row ${state.selected?.id === skill.id ? "selected" : ""}" data-id="${skill.id}">
      <span class="skill-level">Lv.${skill.level}</span>
      <span class="skill-copy"><strong>${skill.name}</strong><span>${skill.id}</span></span>
      <span class="hit-pill">${skill.hits}타</span>
    </button>`).join("") || `<p class="empty-result">일치하는 스킬이 없습니다.</p>`;
  $("skillList").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => selectSkill(state.skills.find((skill) => skill.id === button.dataset.id))));
}

function renderHitMarkers() {
  const skill = state.selected;
  const visualTimings = WARRIOR_STANDARD_ENDING_IDS.has(skill.id) ? [875] : skill.timings;
  $("hitMarkers").innerHTML = visualTimings.map((timing, index) => `<button class="hit-marker" style="left:${timing / 13.5}%" data-time="${timing}">${index + 1}타</button>`).join("");
  $("hitMarkers").querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
    state.elapsed = Number(button.dataset.time);
    state.playing = false;
    updatePlayButton();
    renderFrame();
  }));
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
      ["예고", 0],
      ["대검 준비", 313],
      ["순간 내려베기", 625],
      ["첫 접촉", 750],
      ["최대 파쇄", 875],
      ["파쇄 유지", 999],
      ["페이드 아웃", WARRIOR_SPRITE_FADE_START_MILLIS],
      ["소실", WARRIOR_SPRITE_HIDE_MILLIS],
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
      [swordMasterMeta.prep, 313],
      [swordMasterMeta.motion, 625],
      [swordMasterMeta.contact, 750],
      [swordMasterMeta.impact, 875],
      [swordMasterMeta.hold, 999],
      ["페이드 아웃", WARRIOR_SPRITE_FADE_START_MILLIS],
      ["소실", WARRIOR_SPRITE_HIDE_MILLIS],
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
      ["방패 압축", 313],
      ["순간 돌파", 625],
      ["첫 접촉", 750],
      ["최대 타격", 875],
      ["파쇄 유지", 999],
      ["페이드 아웃", WARRIOR_SPRITE_FADE_START_MILLIS],
      ["소실", WARRIOR_SPRITE_HIDE_MILLIS],
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

function renderDetails() {
  const skill = state.selected;
  const customPresentation = authoredSpritePresentation(skill.id);
  const isStandardEndingRevision = WARRIOR_STANDARD_ENDING_IDS.has(skill.id);
  const rows = [
    ["catalogId", skill.id], ["레벨 / 후보", `Lv.${skill.level} · C${skill.candidate + 1}`],
    ["Android 분기", skill.branchKey], ["행동", customPresentation?.action ?? skill.action], ["방향", customPresentation?.flow ?? skill.flow], ["원소", skill.element],
    ["경로", `${skill.path} · BAND ${skill.growthBand + 1}`], ["타격", isStandardEndingRevision ? "웹 수정본 1회 · 875ms (Android 현재 420ms)" : `${skill.hits}회 · ${skill.timings.join(", ")}ms`],
    ["표시 타격", isStandardEndingRevision ? "웹 수정본 875ms (Android 현재 420ms)" : skill.presentationTimings.join(", ") + "ms"],
    ...(skill.id === BLADE_SLASH_ID
      ? [["스프라이트 연출", "F01-F05 예고 · F06-F08 순간 베기 · F09 주변 임팩트 · F16 소실"]]
      : SWORD_MASTER_LATE_IMPACT_META.has(skill.id)
        ? [["스프라이트 연출", SWORD_MASTER_LATE_IMPACT_META.get(skill.id).detail]]
      : skill.id === SHIELD_BREAK_ID
        ? [["스프라이트 연출", "F01-F09 방패 압축 · F10-F12 순간 돌파 · F13 첫 접촉 · F15 최대 파쇄 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃"]]
      : skill.id === SHATTER_STRIKE_ID
        ? [["스프라이트 연출", "F01-F09 대검 준비 · F10-F12 순간 내려베기 · F13 첫 접촉 · F15 갑주 파쇄 · F16 1099ms까지 유지 · 1100-1350ms 페이드아웃"]]
      : SIGNATURE_WARRIOR_IDS.has(skill.id)
        ? [["스프라이트 연출", "F01 예고 · F02-F08 고유 동작 · F09 최대 타격 · F10-F16 즉시 감쇠/소실"]]
        : []),
  ];
  $("skillDetails").innerHTML = rows.map(([term, value]) => `<dt>${term}</dt><dd>${value}</dd>`).join("");
}

function selectSkill(skill, rerenderList = true) {
  state.selected = skill;
  state.selectedClass = skill.heroClass;
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
  if (rerenderList) renderSkillList(false);
  renderHitMarkers();
  renderPhaseMarkers();
  renderRoleFocusControls();
  renderLayerToggles();
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
  if (state.playing && state.selected) {
    if (state.lastFrame) state.elapsed += (timestamp - state.lastFrame) * state.speed;
    if (state.elapsed >= state.payload.meta.durationMillis) state.elapsed %= state.payload.meta.durationMillis;
    renderFrame();
  }
  state.lastFrame = timestamp;
  requestAnimationFrame(animationLoop);
}

async function initialize() {
  const response = await fetch("data/skills.json", { cache: "no-store" });
  state.payload = await response.json();
  state.skills = state.payload.skills
    .filter((skill) => WEB_REVIEW_SKILL_IDS.has(skill.id))
    .map((skill) => ({ ...skill, name: SIGNATURE_WARRIOR_NAMES.get(skill.id) ?? skill.name }));
  const prefersReducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
  if (prefersReducedMotion) {
    state.reducedMotion = true;
    state.playing = false;
    $("reducedMotionToggle").checked = true;
  }
  $("timeline").max = state.payload.meta.durationMillis;
  renderClassTabs();
  renderRoleFocusControls();
  renderSkillList(true);
  $("searchInput").addEventListener("input", () => renderSkillList(false));
  $("playButton").addEventListener("click", () => { state.playing = !state.playing; state.lastFrame = 0; updatePlayButton(); });
  $("replayButton").addEventListener("click", () => { state.elapsed = 0; state.playing = true; state.lastFrame = 0; updatePlayButton(); renderFrame(); });
  $("backFrameButton").addEventListener("click", () => { state.playing = false; state.elapsed = clamp(state.elapsed - 16, 0, state.payload.meta.durationMillis); updatePlayButton(); renderFrame(); });
  $("nextFrameButton").addEventListener("click", () => { state.playing = false; state.elapsed = clamp(state.elapsed + 16, 0, state.payload.meta.durationMillis); updatePlayButton(); renderFrame(); });
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
