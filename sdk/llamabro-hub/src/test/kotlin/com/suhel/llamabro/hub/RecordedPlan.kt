package com.suhel.llamabro.hub

/**
 * The plan a [LlamaBroHub.download] makes and records as its first step: `refs/main` moves, the plan record is written
 * and an old-format copy is adopted. [LlamaBroHub.plan] is a look that does none of it, so a test that needs the cache
 * a started download leaves (or ports the resolution upstream's `-hf` does) plans through this.
 */
internal suspend fun LlamaBroHub.recordedPlan(model: HfModel, configure: PlanOptions.() -> Unit = {}): HubPlan =
    planInternal(model, PlanOptions().apply(configure), operationTransport(), record = true)
