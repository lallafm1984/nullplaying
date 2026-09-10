package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArenaIdentityCopyExportTest {
    @Test fun `export final effects and authored opponent paths for review`() {
        val path=System.getenv("ARENA_REPORTED_FIXTURE_DIR")
        assumeTrue(!path.isNullOrBlank())
        val dir=File(requireNotNull(path));dir.mkdirs()
        val copy=StringBuilder("class\tslot\tid\trank\tko\ten\tja\n")
        for(d in ArenaIdentityCatalog.values) for(rank in listOf(1,5,10)) {
            val texts=listOf("ko","en","ja").map { ArenaIdentityCopy.effect(d.id,rank,it).replace('\n',' ') }
            copy.appendLine(listOf(d.heroClass,d.slot,d.id,rank).joinToString("\t")+"\t"+texts.joinToString("\t"))
        }
        File(dir,"skill-effects.tsv").writeText(copy.toString())
        val builds=StringBuilder("level\tclass\tpreset\tallocation\n")
        for(level in 10..100 step 5) for(c in HeroClass.entries) for(p in ArenaAutoBuildPreset.entries) {
            val owned=SkillCatalog.forClass(c).filter { it.unlockLevel<=level }.map { it.catalogId }.toSet()
            val state=ArenaIdentityBuilds.allocate(c,level,owned,p)
            val ranks=state.allocations.sortedBy { ArenaIdentityCatalog.find(it.nodeId)!!.slot }.joinToString(" ") {
                "${ArenaIdentityCatalog.find(it.nodeId)!!.slot}:${it.rank}"
            }
            builds.appendLine("$level\t$c\t$p\t$ranks")
        }
        File(dir,"opponent-builds.tsv").writeText(builds.toString())
    }
}
