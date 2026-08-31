#!/usr/bin/env python3
"""PD audit for P5m mixed survival correction and composite."""
from __future__ import annotations
import argparse, hashlib, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK_HASH = "195584d893d861677a8327b936ee73cf060ccdddc6ea39c6f47951d3c0d5d7ec"
APK_SIZE = 37_384_351
MIXED_HASH = "40af66522488993d60a518d986f865bc9e8782e620aef4f0c3f0c18cd8c7e2c2"
COMPOSITE_HASH = "190599b9c947a2fb30ba8dfc8c6f3a5641fadfbfb96be53200de6b9632fc2d15"
FREEZE = {
 "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt":"79c02fe182f5c6bddb70c6deeee275c2679d26de2871287386a34dc7dd2b65c5",
 "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5m.kt":"7f70179fa3a58ac4d932bff9d2e3eb5de8171e136fd938989cf3d5c35367b6fa",
 "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextClassSurvivalSemanticAdapterP5l.kt":"c420219821cec4742c6db2a7dd0c34f0a8502b9b2976281d060ca0582cd17215",
 "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt":"a1687db6647f9226dc4673a83d4b8471aaabe5c34c9afd7c8fdb406ffe378137",
 "game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt":"ce858160b6a6820ddcc43a157e26de8b494f99d3be300d8deab1fd8be71ae681",
 "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5mTest.kt":"4bb85a32438efb8ca82587c916ef986d1e8f18441a6d4780fb7a89dbf37b05c5",
 "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5mTest.kt":"d7b07e14eb62678d911ac333509b11fc9ac8de98c8136e5e6ae2b32568763342",
 "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt":"bf9cf1882f931020d5c957d40fab4fc231376155528f375fe671e474a9e3ce13",
 "game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt":"bc3fe0a571c5901a4e2115d16572caf7aa98ccd7cd8d32233d0110efc5ed2ec4",
 "game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt":"06215e632784e0c730aff19f503b6f7a475d27e7fef5e0984b3169f8411db7c6",
}
def sha(p: Path)->str: return hashlib.sha256(p.read_bytes()).hexdigest()
def run(c:list[str]): return subprocess.run(c,cwd=ROOT,text=True,capture_output=True,check=False)
def totals(m:str):
 v=[0,0,0,0]
 for p in (ROOT/m/"build/test-results").glob("test*/TEST-*.xml"):
  x=ET.parse(p).getroot()
  for i,k in enumerate(("tests","failures","errors","skipped")): v[i]+=int(x.attrib.get(k,0))
 return tuple(v)
def main()->int:
 a=argparse.ArgumentParser(); a.add_argument("--with-gradle",action="store_true"); a.add_argument("--with-emulator",action="store_true"); args=a.parse_args()
 if args.with_gradle:
  r=run(["./gradlew","test","lintDebug","assembleDebug"])
  if r.returncode: print(r.stdout); print(r.stderr,file=sys.stderr); return r.returncode
 mixed=(ROOT/"game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5m.kt").read_text()
 comp=(ROOT/"game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5m.kt").read_text()
 resolver=(ROOT/"game-engine/src/main/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransaction.kt").read_text()
 ai=(ROOT/"game-engine/src/main/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicy.kt").read_text()
 tests=(ROOT/"game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextMixedSurvivalCorrectionP5mTest.kt").read_text()
 ctests=(ROOT/"game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextCompositeSemanticDispatcherP5mTest.kt").read_text()
 rtests=(ROOT/"game-engine/src/test/kotlin/com/alarmquest/engine/vnext/battle/VNextBattleResolverTransactionTest.kt").read_text()
 aitests=(ROOT/"game-engine/src/test/kotlin/com/alarmquest/engine/vnext/VNextAutoBattleAiPolicyTest.kt").read_text()
 app=(ROOT/"app/src/main/java/com/alarmquest/AlarmQuestApplication.kt").read_text(); settlement=(ROOT/"game-engine/src/main/kotlin/com/alarmquest/engine/SettlementEngine.kt").read_text()
 doc=(ROOT/"PRODUCT_MEETING_PRODUCTION_P5M_MIXED_SURVIVAL_v0.1.md").read_text(); audit=(ROOT/"artifacts/audit/production-p5m-emulator-verification-v0.1.txt").read_text()
 cs=[]
 def ck(n,c,d): cs.append((n,c,d))
 ck("mixed rules","aq.mixed-survival-correction.p5m.v0.1" in mixed,"v0.1"); ck("mixed hash",MIXED_HASH in mixed and MIXED_HASH in doc,MIXED_HASH)
 ck("six definitions","V_NEXT_P5M_MIXED_SURVIVAL_DEFINITION_COUNT: Int = 6" in mixed,"6"); ck("six drifts","V_NEXT_P5M_BASE_DESCRIPTOR_DRIFT_COUNT: Int = 6" in mixed,"6"); ck("twelve carriers","V_NEXT_P5M_OVERLAY_CARRIER_COUNT: Int = 12" in mixed,"12"); ck("live off","val liveReady: Boolean get() = false" in mixed,"OFF")
 rows=(("earlyward","MAGICAL","fixed(500)","80"),("focusedward","MAGICAL","listOf(550, 562, 575, 587, 600)","90"),("lastward","MAGICAL","fixed(600)","98"),("earlymercy","PHYSICAL","fixed(700)","82"),("measuredmercy","PHYSICAL","listOf(750, 756, 762, 768, 775)","92"),("crisismercy","PHYSICAL","fixed(800)","98"))
 for i,ch,s,p in rows:
  ck(i+" ID",".a3."+i in mixed,i); ck(i+" channel","VNextRuntimeDamageChannelV2."+ch in mixed,ch); ck(i+" support",s in mixed,s); ck(i+" priority",", "+p+"," in mixed,p)
 for n,s in (("registry hit","definition.hitPackets == 1"),("registry attack","definition.attackEquivalentValues.first() > 0"),("base support drift","descriptor.carriers.singleOrNull()"),("no enemy carrier","carrierTarget == VNextRuntimeCarrierTarget.CURRENT_ENEMY"),("class gate","ACTOR_CLASS_MISMATCH"),("behavior gate","BEHAVIOR_POLICY_INVALID"),("ledger gate","CLASS_PROTECTION_LEDGER_REJECTED"),("resource gate","RESOURCE_COST_UNPAYABLE"),("threshold gate","HP_THRESHOLD_REJECTED"),("missing hp","MISSING_HP_FLOOR_REJECTED"),("packet engine","VNextCombatPacketEngine.resolve"),("direct carrier","VNextRuntimeCarrierDeliveryV2.DIRECT"),("crit forbidden","VNextPacketCriticalPolicy.FORBIDDEN"),("sustain","VNextProtectionDeathPipeline.grantSustain"),("dual ledger","syncClassProtectionLedgerP5l"),("barrier detail","primary.barrierConsumed")):
  ck(n,s in mixed,"bound")
 ck("AI support field","val supportValues: List<Int>" in ai,"typed"); ck("AI priority field","val automationPriority: Int" in ai,"typed"); ck("AI corrected budget","skill.supportValues.ifEmpty { skill.levelValues }" in ai,"corrected"); ck("AI priority sort","delta + skill.automationPriority" in ai,"ordered")
 ck("resolver support bind","supportValues = VNextMixedSurvivalCorrectionP5m.supportValues" in resolver,"bound"); ck("resolver priority bind","automationPriority = VNextMixedSurvivalCorrectionP5m.automationPriority" in resolver,"bound")
 ck("composite rules","aq.composite-semantic-dispatcher.p5m.v0.1" in comp,"v0.1"); ck("composite hash",COMPOSITE_HASH in comp and COMPOSITE_HASH in doc,COMPOSITE_HASH); ck("families","V_NEXT_P5M_COMPOSITE_FAMILY_COUNT: Int = 7" in comp,"7"); ck("definitions","V_NEXT_P5M_COMPOSITE_DEFINITION_COUNT: Int = 45" in comp,"45"); ck("P5l bound","VNextCompositeSemanticDispatcherP5lCompiler.compile" in comp,"39"); ck("P5m bound","VNextMixedSurvivalCorrectionP5m.compile" in comp,"6"); ck("overlap gate","P5M_DUPLICATE_DEFINITION_OWNERS" in comp,"closed"); ck("unsupported","P5M_COMPOSITE_UNSUPPORTED_DEFINITION" in comp,"closed")
 for phrase in ("coverage detects six support-only base drifts and supplies twelve corrected carriers","correction exports exact support rows and tactic priorities for AI","cleric early ward commits magical packet fixed shield and expedition attrition","cleric focused ward grows shield while last ward grows only threshold","paladin three tactics keep fixed attack and distinct heal identity","self protection commits even when target barrier consumes the direct packet","class tactic group rejects a second mixed shield without target or cost mutation","wrong class and invalid behavior reject before the mixed packet"):
  ck("test "+phrase[:28],phrase in tests,"covered")
 for phrase in ("coverage extends thirty-nine definitions with six corrected mixed survival definitions","old direct and corrected mixed routes preserve complete nested provenance","unsupported definition and mixed class mismatch reject without frame mutation","corrected mixed replay is deterministic and exactly owned"):
  ck("composite test "+phrase[:22],phrase in ctests,"covered")
 ck("resolver mixed","p5m corrected cleric tactic deals damage grants shield once and carries expedition ledgers" in rtests,"covered"); ck("AI mixed","mixed survival uses corrected support budget and shared tactic priority" in aitests,"covered")
 ck("feature off","V_NEXT_BATTLE_RESOLVER_FEATURE_DEFAULT_ENABLED: Boolean = false" in resolver,"OFF"); ck("settlement off","V_NEXT_BATTLE_RESOLVER_LIVE_SETTLEMENT_ENABLED: Boolean = false" in resolver,"OFF"); ck("app unconnected","VNextCompositeSemanticDispatcherP5m" not in app,"safe"); ck("legacy unconnected","VNextCompositeSemanticDispatcherP5m" not in settlement,"safe"); ck("resolveKill","resolveKill" in settlement,"present")
 for p,h in FREEZE.items(): x=sha(ROOT/p); ck("freeze "+Path(p).name,x==h,x)
 ck("engine tests",totals("game-engine")== (415,0,0,0),str(totals("game-engine"))); ck("app tests",totals("app")== (176,0,0,0),str(totals("app")))
 lx=ET.parse(ROOT/"app/build/reports/lint-results-debug.xml").getroot(); le=sum(i.attrib.get("severity")=="Error" for i in lx.findall("issue")); lw=sum(i.attrib.get("severity")=="Warning" for i in lx.findall("issue")); ck("lint",(le,lw)==(0,22),f"{le}/{lw}")
 ck("APK",APK.is_file(),str(APK)); ck("APK hash",APK.is_file() and sha(APK)==APK_HASH,sha(APK) if APK.is_file() else "missing"); ck("APK size",APK.is_file() and APK.stat().st_size==APK_SIZE,str(APK.stat().st_size if APK.is_file() else 0))
 for f in ("verificationTarget=android-emulator","avdName=alarmquest-qa","androidRelease=15","apiLevel=35","installResult=Success","pmClearResult=Success","launchState=COLD","coldTotalTimeMs=1019","topResumedActivity=com.nullplaying/.MainActivity","freshRosterEmpty=true","androidRuntimeFatalCount=0","engineTests=415","appTests=176","totalTests=591","liveSettlementEnabled=false"): ck("audit "+f.split("=")[0],f in audit,f)
 if args.with_emulator:
  d=run(["adb","devices","-l"]); ck("emulator online","emulator-5554" in d.stdout and " device " in d.stdout,d.stdout.strip()); b=run(["adb","-s","emulator-5554","shell","getprop","sys.boot_completed"]); ck("boot",b.stdout.strip()=="1",b.stdout.strip()); v=run(["adb","-s","emulator-5554","shell","dumpsys","package","com.nullplaying"]); ck("version","versionCode=1" in v.stdout and "versionName=0.1.0" in v.stdout,"0.1.0(1)"); f=run(["adb","-s","emulator-5554","shell","dumpsys","activity","activities"]); ck("focus","topResumedActivity" in f.stdout and "com.nullplaying/.MainActivity" in f.stdout,"MainActivity")
  ui=""
  for _ in range(3): run(["adb","-s","emulator-5554","shell","uiautomator","dump","/sdcard/p5m-review-ui.xml"]); ui=run(["adb","-s","emulator-5554","shell","cat","/sdcard/p5m-review-ui.xml"]).stdout; time.sleep(0 if "아직 캐릭터가 없습니다" in ui else .5)
  ck("empty roster","아직 캐릭터가 없습니다" in ui and "새 캐릭터" in ui,"fresh"); log=run(["adb","-s","emulator-5554","logcat","-d","-v","brief"]).stdout; ck("fatal zero","FATAL EXCEPTION" not in log and "AndroidRuntime: FATAL" not in log,"0")
 passed=sum(c for _,c,_ in cs)
 for n,c,d in cs: print(f"[{'PASS' if c else 'FAIL'}] {n}: {d}")
 print(f"P5m PD verification: {passed}/{len(cs)} PASS"); return 0 if passed==len(cs) else 1
if __name__=="__main__": sys.exit(main())
