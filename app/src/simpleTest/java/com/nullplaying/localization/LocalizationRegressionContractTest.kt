package com.nullplaying.localization

import android.app.Application
import com.nullplaying.R
import com.nullplaying.engine.AdventureTaleCatalog
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.TaleKind
import com.nullplaying.ui.labyrinthDepthContentDescription
import com.nullplaying.ui.localizedStoryText
import com.nullplaying.ui.taleChapterLabel
import com.nullplaying.ui.taleSubtitleLabel
import com.nullplaying.ui.taleVolumeLabel
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalizationRegressionContractTest {
    @Before
    fun setUp() {
        GameLocalization.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `ranking and sync failures are complete sentences in English and Japanese`() {
        assertTranslations(
            "랭킹 정산 정보가 올바르지 않습니다",
            "Ranking data is invalid.",
            "ランキングの集計情報が正しくありません。",
        )
        assertTranslations(
            "저장된 랭킹 정산본이 없습니다",
            "No saved ranking snapshot is available.",
            "保存されたランキングの集計結果がありません。",
        )
        assertTranslations(
            "결투장 랭킹 정산 정보가 올바르지 않습니다",
            "Arena ranking data is invalid.",
            "闘技場ランキングの集計情報が正しくありません。",
        )
        assertTranslations(
            "저장된 결투장 랭킹 정산본이 없습니다",
            "No saved Arena ranking snapshot is available.",
            "保存された闘技場ランキングの集計結果がありません。",
        )
        assertTranslations(
            "서버 시각 응답을 확인할 수 없습니다",
            "Could not verify the server time response.",
            "サーバー時刻の応答を確認できませんでした。",
        )
        assertTranslations(
            "랭킹 계정이 변경되어 다시 확인합니다",
            "The ranking account changed. Checking again.",
            "ランキングのアカウントが変更されたため、再確認します。",
        )
        assertTranslations(
            "결투장 랭킹 계정이 변경되어 다시 확인합니다",
            "The Arena ranking account changed. Checking again.",
            "闘技場ランキングのアカウントが変更されたため、再確認します。",
        )
        assertTranslations(
            "결투장 랭킹 서버가 아직 활성화되지 않았습니다",
            "Arena rankings are not available on the server yet.",
            "闘技場ランキングはサーバーでまだ利用できません。",
        )
        assertTranslations(
            "선택을 저장하지 못했습니다.",
            "Could not save your choice.",
            "選択を保存できませんでした。",
        )
        assertTranslations(
            "공개 영웅 정보 변경은 잠시 후 동기화됩니다",
            "Public hero changes will sync shortly.",
            "公開キャラクター情報の変更は、まもなく同期されます。",
        )
    }

    @Test
    fun `bag and playback terms use their actual UI meaning`() {
        assertTranslations("가방이 가득 찼습니다", "The bag is full", "バッグがいっぱいです")
        assertTranslations("가방 가득 참", "Bag full", "バッグがいっぱい")
        assertTranslations("장비 · 가방에 보관", "Gear · Stored in the bag", "装備 · バッグに収納")
        assertTranslations("재생", "Play", "再生")
        assertTranslations("재생 중", "Playing", "再生中")
        assertTranslations("칼날 베기 연출 재생", "Play Blade Slash effect", "刃の斬撃の演出を再生")
    }

    @Test
    fun `accessibility labels describe expansion and collapse as actions and states`() {
        assertTranslations("지난 모험담 펼치기", "Expand past tales", "過去の冒険譚を開く")
        assertTranslations("지난 모험담 접기", "Collapse past tales", "過去の冒険譚を閉じる")
        assertTranslations("모험담 회고 펼치기", "Expand adventure recap", "冒険の回想を開く")
        assertTranslations("모험담 회고 접기", "Collapse adventure recap", "冒険の回想を閉じる")
        assertTranslations("펼쳐짐", "Expanded", "展開中")
        assertTranslations("접힘", "Collapsed", "折りたたみ中")
    }

    @Test
    fun `reviewed quest results keep actor voice and meaning`() {
        assertTranslations(
            "로웬이 남긴 밧줄과 니아가 찾은 옛 교각으로 추격자보다 먼저 계곡을 건넜다.",
            "Using Rowen's rope and the old supports Nia found, the party crossed the ravine ahead of its pursuers.",
            "ロウェンが残した縄とニアが見つけた古い橋脚を使い、追っ手より先に谷を渡った。",
        )
        assertTranslations(
            "얼굴에 응답하지 않고 뿌리의 박동만 따라 중심 씨앗문에 도착했다.",
            "Reached the central Seed Gate by following the root's pulse and ignoring the face.",
            "顔には応えず、根の鼓動だけをたどって中心の種門に着いた。",
        )
        assertTranslations(
            "열쇠의 홈을 북쪽 신호탑 지도와 맞춰 숨은 입구의 위치를 찾았다.",
            "Matched the key's groove to the northern signal-tower map and found the hidden entrance.",
            "鍵の溝を北の信号塔の地図と合わせ、隠された入口を見つけた。",
        )
        assertTranslations(
            "겨울뿌리 파수자를 쓰러뜨리고 흰 정원으로 이어지는 봉인로를 열었다.",
            "Defeated the Winterroot Warden and opened the sealed path to the White Garden.",
            "冬根の番人を倒し、白い庭へ続く封印路を開いた。",
        )
        assertTranslations(
            "울림 간격을 귀환 규칙으로 정해 후속 원정대가 길을 잃지 않게 했다.",
            "Set the bell intervals as the return rule so later expeditions would not get lost.",
            "鐘の響く間隔を帰還の規則として定め、後続の遠征隊が道に迷わないようにした。",
        )
        val heroResult = "Lina는 전복된 수레에서 두 번째 새벽종을 되찾았지만 마지막 수레는 성채 문을 통과했다."
        assertEquals(
            "Lina recovered the second Dawn Bell from the overturned cart, but the final cart had passed through the citadel gates.",
            localizedStoryText(heroResult, "Lina", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Linaは横転した荷車から二つ目の暁の鐘を取り戻したが、最後の荷車は城塞の門を通り抜けていた。",
            localizedStoryText(heroResult, "Lina", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `reviewed epilogue records and monster names preserve source meaning`() {
        assertTranslations(
            "세라의 이름을 열세 번째 묘표에 새기고 최초 수호대의 실패를 공식 기록에 남겼다.",
            "Sera's name was carved into the thirteenth grave marker, and the first guardian squad's failure was entered into the official record.",
            "セラの名を第十三の墓標に刻み、初代守護隊の失敗を公式記録に残した。",
        )
        assertTranslations(
            "남은 징조: 공동묘지 명부에 없는 열세 번째 묘표가 밤마다 젖어 있다.",
            "Remaining signs: The thirteenth grave marker missing from the cemetery register is wet every night.",
            "残る兆候：墓地の名簿にない第十三の墓標が毎夜濡れている。",
        )
        assertTranslations(
            "마지막 물길을 봉인하고 젖은 열세 번째 묘표의 위치를 확인했다.",
            "Sealed the final waterway and confirmed the location of the wet thirteenth grave marker.",
            "最後の水路を封印し、濡れた第十三の墓標の位置を確認した。",
        )
        assertTranslations(
            "청동 가루를 따라가 울림 장치와 봉인 계단을 잇는 틈을 찾았다.",
            "Following the bronze dust, they found the gap connecting the resonance device and the sealed staircase.",
            "青銅の粉をたどり、共鳴装置と封印階段をつなぐ隙間を見つけた。",
        )
        assertTranslations(
            "기억나무의 가장 굵은 뿌리가 네 번째 울림에 맞춰 갈라지며 돌계단을 드러냈다.",
            "The thickest root of the Memory Tree split with the fourth echo, revealing the stone steps.",
            "記憶の木の最も太い根が四つ目の鐘の響きに合わせて裂け、石段を露わにした。",
        )
        assertTranslations(
            "남은 문장과 이전 구역 기록을 대조해 사라진 수문 번호를 되찾았다.",
            "By comparing the remaining text with the previous sector records, we recovered the lost sluice gate number.",
            "残った文と前区域の記録を照合し、失われた水門番号を取り戻した。",
        )
        assertTranslations(
            "남은 준비: 아래에서 떠오르는 별빛이 첫 미궁 구역의 문을 비춘다.",
            "Remaining preparations: The starlight rising from below shines on the entrance of the first Labyrinth sector.",
            "残る準備：下から昇る星明かりが迷宮の第一区域の門を照らしている。",
        )
        assertTranslations(
            "빛의 이동을 따라 첫 구역 문이 열리는 시각과 위치를 확인했다.",
            "Followed the movement of light to identify when and where the first sector gate would open.",
            "光の動きを追い、第一区域の門が開く時刻と場所を特定した。",
        )
        assertTranslations(
            "첫 구역으로 이어지는 하강로를 쇠사슬과 표지석으로 고정했다.",
            "The descent path leading to the first sector was secured with chains and signposts.",
            "第一区域へ続く下降路を鎖と道標で固定した。",
        )
        assertTranslations(
            "최초 수호대의 신호가 한 구역씩 아래로 이어져 있음을 확인했다.",
            "Confirmed that the first guardian squad's signal continued downward, one sector at a time.",
            "初代守護隊の信号が一区域ずつ下へ続いていることを確認した。",
        )
        assertTranslations(
            "남은 세 계절의 위치를 맞춰 사라진 봄 방의 윤곽을 드러냈다.",
            "By aligning the remaining three seasons, they revealed the outline of the vanished Spring Chamber.",
            "残る三つの季節の位置を合わせ、消えた春の間の輪郭を明らかにした。",
        )
        assertTranslations(
            "입구를 향한 발자국은 최초 수호대의 인원과 정확히 같았다.",
            "The footprints leading to the entrance exactly matched the number of members in the first guardian squad.",
            "入口へ向かう足跡の数は、初代守護隊の人数と正確に一致した。",
        )
        assertTranslations(
            "밖으로 향한 자국 세 줄은 어느 명부에도 맞지 않았다.",
            "The three sets of tracks leading outward matched no roster.",
            "外へ向かう三本の足跡は、どの名簿とも一致しなかった。",
        )
        assertTranslations(
            "유품을 되찾아 묘표와 명부의 이름을 하나씩 연결했다.",
            "Recovered the belongings and matched each grave marker to a name in the roster.",
            "遺品を取り戻し、墓標と名簿の名前を一つずつ結びつけた。",
        )
        assertTranslations(
            "첫 종혀로 비밀 계단을 열어",
            "Open the Secret Stairway with the First Clapper",
            "最初の鐘の舌で秘密の階段を開く",
        )
        assertTranslations("거꾸로수로 이끼골렘", "Backflow-Channel Moss Golem", "逆流水路の苔ゴーレム")
        assertTranslations("계절먹이 살쾡이", "Season-Eating Wildcat", "季節喰いのヤマネコ")
        assertTranslations("계단밑 들쥐", "Under-the-Stairs Field Mouse", "階段下の野ネズミ")
        assertTranslations("명부밖 들쥐", "Unlisted Field Mouse", "名簿にない野ネズミ")
        assertTranslations("북풍 송곳니", "Northwind Fang", "北風の牙")
    }

    @Test
    fun `every normal skill description reaches English and Japanese without Korean`() {
        assertEquals(120, SkillCatalog.all.size)
        SkillCatalog.all.forEach { skill ->
            listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
                val name = GameLocalization.translate(skill.name, language)
                val description = GameLocalization.translate(skill.description, language)
                val maximumMastery = GameLocalization.translate("${skill.name}, 스킬 경험치 최대", language)
                val progressingMastery = GameLocalization.translate("${skill.name}, 스킬 경험치 3/10", language)
                assertTrue("${skill.catalogId} $language name is blank", name.isNotBlank())
                assertTrue("${skill.catalogId} $language description is blank", description.isNotBlank())
                assertFalse("${skill.catalogId} $language name leaked Korean: $name", KOREAN.containsMatchIn(name))
                assertFalse("${skill.catalogId} $language description leaked Korean: $description", KOREAN.containsMatchIn(description))
                assertFalse("${skill.catalogId} $language max mastery leaked Korean: $maximumMastery", KOREAN.containsMatchIn(maximumMastery))
                assertFalse("${skill.catalogId} $language mastery leaked Korean: $progressingMastery", KOREAN.containsMatchIn(progressingMastery))
            }
        }
    }

    @Test
    fun `arena runtime and QA copy translates as complete text`() {
        assertTranslations("전적", "History", "戦績")
        assertTranslations("랭킹", "Rankings", "ランキング")
        assertTranslations("+1시간", "+1 hr", "+1時間")
        assertTranslations("AI 장면 대기 중", "Waiting for AI scene", "AIシーン待機中")
        assertTranslations("Qwen 장면 생성 중", "Generating Qwen scene", "Qwenシーンを生成中")
        assertTranslations(
            "전투 결과는 이미 확정되어 있습니다",
            "The battle result is already final.",
            "戦闘結果はすでに確定しています。",
        )
        assertTranslations("AI 장면 연결 실패", "Could not connect the AI scene.", "AIシーンに接続できませんでした。")
        assertTranslations("장면 다시 생성", "Regenerate scene", "シーンを再生成")
        assertTranslations("전적에서 보기", "View in history", "戦績で見る")
        assertTranslations("확인하고 계속", "Confirm and continue", "確認して続ける")
        assertTranslations("공식전 · 승리", "Official match · Victory", "公式戦・勝利")
        assertTranslations("Qwen 실응답 · 3문장", "Live Qwen response · 3 lines", "Qwen実応答・3文")
        assertTranslations(
            "DB 접근 없음 · 250ms · 토큰 10/20",
            "No DB access · 250 ms · Tokens 10/20",
            "DBアクセスなし・250ms・トークン10/20",
        )
        assertTranslations(
            "공격성 3 · 강공 2 · 승부수 1",
            "Aggression 3 · Power attacks 2 · Gambits 1",
            "攻撃性 3・強攻撃 2・勝負手 1",
        )
        assertTranslations(
            "안정성 4 · 단기전 1 · 장기전 5",
            "Stability 4 · Short battles 1 · Long battles 5",
            "安定性 4・短期戦 1・長期戦 5",
        )
    }

    @Test
    fun `dynamic arena copy preserves a Korean player name while translating UI words`() {
        assertEquals(
            "전적 · Placement participant",
            GameLocalization.translatePreserving(
                "전적 · 배치전 참가자",
                AppLanguage.ENGLISH,
                listOf("전적"),
            ),
        )
        assertEquals(
            "전적・順位決定戦参加者",
            GameLocalization.translatePreserving(
                "전적 · 배치전 참가자",
                AppLanguage.JAPANESE,
                listOf("전적"),
            ),
        )
    }

    @Test
    fun `quest narration preserves a Korean hero name that is also a UI term`() {
        val source = "전적는 전복된 수레에서 두 번째 새벽종을 되찾았지만 마지막 수레는 성채 문을 통과했다."

        assertEquals(
            "전적 recovered the second Dawn Bell from the overturned cart, but the final cart had passed through the citadel gates.",
            localizedStoryText(source, "전적", AppLanguage.ENGLISH),
        )
        assertEquals(
            "전적は横転した荷車から二つ目の暁の鐘を取り戻したが、最後の荷車は城塞の門を通り抜けていた。",
            localizedStoryText(source, "전적", AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `rendered bag name and detail combinations do not leak Korean UI copy`() {
        val equipmentNamesBySlot = mapOf(
            "무기" to "잘 벼린 정련 철제 장검 +3",
            "머리" to "수호자의 철제 사슬모자 +3",
            "몸" to "여행자의 모험식 파이터 갑옷",
            "손" to "용병의 철제 전투 장갑 +2",
            "발" to "훈련식 파이터 장화",
            "장신구" to "성좌의 초월 12단식 전투 목걸이 +7",
        )
        val rarities = listOf("일반", "고급", "희귀", "영웅", "전설", "신화")

        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            equipmentNamesBySlot.forEach { (slot, sourceName) ->
                val localizedName = GameNameLocalization.equipmentName(sourceName, language)
                rarities.forEach { rarity ->
                    val detail = GameLocalization.translate("$slot · 장비력 123 · $rarity", language)
                    assertFalse("$language equipment name leaked Korean: $localizedName", KOREAN.containsMatchIn(localizedName))
                    assertFalse("$language equipment detail leaked Korean: $detail", KOREAN.containsMatchIn(detail))
                }
            }

            val lootName = GameNameLocalization.itemName("별가루 가루병", language)
            rarities.forEach { rarity ->
                val detail = GameLocalization.translate("전리품 · $rarity · Lv.30", language)
                assertFalse("$language loot name leaked Korean: $lootName", KOREAN.containsMatchIn(lootName))
                assertFalse("$language loot detail leaked Korean: $detail", KOREAN.containsMatchIn(detail))
            }
        }
    }

    @Test
    fun `every rendered quest field reaches English and Japanese without authored Korean`() {
        val variantCount = AdventureTaleCatalog.variantCount()
        val failures = mutableListOf<String>()
        assertEquals(54, AdventureTaleCatalog.all.size)
        assertEquals(12, variantCount)
        AdventureTaleCatalog.all.forEachIndexed { definitionIndex, definition ->
            repeat(variantCount) { variantIndex ->
                val state = AdventureTaleCatalog.instantiate(
                    definition = definition,
                    sequence = (definitionIndex * variantCount + variantIndex + 1).toLong(),
                    heroName = "Lina",
                    heroLevel = 30L,
                    variant = AdventureTaleCatalog.variantAt(variantIndex),
                    labyrinthDepth = if (definition.kind == TaleKind.LABYRINTH) variantIndex.toLong() + 1L else 0L,
                )
                val authored = buildList {
                    addAll(listOf(state.volumeTitle, state.title, state.subtitle, state.opening, state.ending, state.nextHook))
                    addAll(state.openingSlides)
                    state.acts.forEach { act -> addAll(listOf(act.title, act.body, act.completionBody)) }
                }.filter(String::isNotBlank)

                listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
                    authored.forEach { source ->
                        val translated = localizedStoryText(source, "Lina", language)
                        assertTrue("${definition.id} variant $variantIndex $language translated to blank from $source", translated.isNotBlank())
                        if (KOREAN.containsMatchIn(translated)) {
                            failures += "${definition.id} variant $variantIndex $language authored: $translated"
                        }
                    }
                    val composed = buildList {
                        add(taleVolumeLabel(state.kind, state.volumeNumber, state.volumeTitle, 30L))
                        add(taleChapterLabel(state.kind, state.chapterNumber, state.title, state.labyrinthDepth))
                        add(taleSubtitleLabel(state.kind, state.subtitle, 30L))
                        state.acts.forEach { act ->
                            add("완료 · ${act.number}막  ${act.title}")
                            add("자동 진행 중 · ${act.progress}/${act.target}")
                        }
                    }
                    composed.forEach { source ->
                        val translated = localizedStoryText(source, "Lina", language)
                        if (KOREAN.containsMatchIn(translated)) {
                            failures += "${definition.id} variant $variantIndex $language composed: $translated"
                        }
                    }
                }
            }
        }
        assertTrue(
            "Quest localization leaks (${failures.size}):\n${failures.take(100).joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `labyrinth accessibility sentence translates as one coherent message`() {
        val source = labyrinthDepthContentDescription(
            heroLevel = 100L,
            currentDepth = 42L,
            highestCompletedDepth = 41L,
        )
        assertTranslations(
            source,
            "Deep Labyrinth, current depth: Sector 42, deepest clear: Sector 41, " +
                "Title · Gate 4 Conqueror, Next gate · Sector 50, Threat level 5 · Reward +4%",
            "深層迷宮、現在の深度は第42区域、最深到達は第41区域、" +
                "称号・第4関門の征服者、次の関門・第50区域、脅威レベル5・報酬 +4%",
        )
    }

    @Test
    fun `runtime name fragments match the reviewed composition vocabulary`() {
        val reviewed = mapOf(
            "먼지를 뒤집어쓴" to ("Dust-Covered" to "埃まみれの"),
            "나이테에 갇힌" to ("Tree-Ring-Bound" to "年輪に囚われた"),
            "기억을 핥는" to ("Memory-Licking" to "記憶を舐める"),
            "수정 껍질의" to ("Crystal-Shelled" to "水晶殻の"),
            "잘 벼린" to ("Tempered" to "鍛え上げた"),
            "불길한" to ("Ominous" to "不吉な"),
            "성좌의" to ("Constellation" to "星座の"),
        )
        reviewed.forEach { (source, expected) ->
            assertTranslations(source, expected.first, expected.second)
        }
    }

    @Test
    fun `English and Japanese catalogs keep one aligned row per source key`() {
        val english = rawCatalogKeys(R.raw.localization_en)
        val japanese = rawCatalogKeys(R.raw.localization_ja)

        assertEquals(english, japanese)
        assertEquals("English catalog contains duplicate source keys", english.size, english.distinct().size)
        assertEquals("Japanese catalog contains duplicate source keys", japanese.size, japanese.distinct().size)
    }

    private fun assertTranslations(source: String, english: String, japanese: String) {
        assertEquals(source, english, GameLocalization.translate(source, AppLanguage.ENGLISH))
        assertEquals(source, japanese, GameLocalization.translate(source, AppLanguage.JAPANESE))
    }

    private fun rawCatalogKeys(resourceId: Int): List<Pair<String, String>> {
        val application: Application = RuntimeEnvironment.getApplication()
        return application.resources.openRawResource(resourceId)
            .bufferedReader()
            .useLines { lines ->
                lines.filter { line -> line.isNotBlank() && !line.startsWith("#") }.mapIndexed { index, line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) { "Malformed localization row ${index + 1}" }
                    columns[0] to String(Base64.getDecoder().decode(columns[1]), Charsets.UTF_8)
                }.toList()
            }
    }

    private companion object {
        val KOREAN = Regex("[가-힣]")
    }
}
