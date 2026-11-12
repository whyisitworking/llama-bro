package com.suhel.llamabro.demo.models

/**
 * Finding models (open so the phone tests can stand a fake in for the network): Hugging Face's search and a repo's
 * files, and the quick fit of a model the list can't afford a
 * real estimate for (pd-design 5.3). The view models take its functions, not the class, so tests pass plain lambdas.
 */
open class ModelDiscovery(private val hf: HfSearch, private val facts: suspend () -> DeviceFacts) {
    open suspend fun search(query: String): List<SearchModel> = hf.search(query)

    open suspend fun files(repo: String): List<RepoFile> = hf.files(repo)

    /**
     * How the model in [repo] runs here, from the size of its Recommended file: at once for a [catalog] model (the
     * sizes ship with the app), one tree call for any other; null when the repo has no model file to size.
     */
    open suspend fun quickFit(repo: String, catalog: CatalogEntry?): FitView? {
        val device = facts()
        if (catalog != null) return Fits.quick(catalog, device)
        val recommended = Sizes.tiers(files(repo))[SizeTier.Recommended] ?: return null
        return Fits.quick(recommended.bytes, recommended.bytes, device)
    }
}
