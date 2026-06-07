package com.momir.android.data

import com.momir.android.model.Creature
import kotlin.random.Random

class CardRepository(private val bootstrapper: AtomicBootstrapper) {
    private var byMv: Map<Int, List<Creature>> = emptyMap()

    fun ensureLoaded(onStage: (AtomicBootstrapper.Stage) -> Unit = {}): Int {
        byMv = bootstrapper.loadOrBootstrap(onStage)
        return byMv.values.sumOf { it.size }
    }

    fun refreshData(onStage: (AtomicBootstrapper.Stage) -> Unit = {}): Int {
        byMv = bootstrapper.forceRefresh(onStage)
        return byMv.values.sumOf { it.size }
    }

    fun getRandomCreature(mv: Int, includeFunny: Boolean): Creature? {
        val bucket = byMv[mv].orEmpty().let { cards ->
            if (includeFunny) cards else cards.filter { !it.isFunny }
        }
        if (bucket.isEmpty()) return null
        return bucket[Random.nextInt(bucket.size)]
    }
}
