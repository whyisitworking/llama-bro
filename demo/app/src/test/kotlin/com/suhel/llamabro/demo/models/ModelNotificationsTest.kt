package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The download notification names a model as every other surface does (D50). */
class ModelNotificationsTest {
    @Test fun aCatalogModelByItsOwnName() =
        assertEquals("Qwen3.5 0.8B", ModelNotifications.nameOf(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q8_0")))

    @Test fun anyOtherByItsRepoWithoutTheGgufSuffix() =
        assertEquals("Some-Model", ModelNotifications.nameOf(HfModel.parse("someone/Some-Model-GGUF:Q4_K_M")))
}
