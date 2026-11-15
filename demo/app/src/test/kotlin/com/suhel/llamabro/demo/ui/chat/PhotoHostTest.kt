package com.suhel.llamabro.demo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The viewer decodes a picture no larger than the screen needs. */
class PhotoHostTest {
    @Test fun aPictureThatFitsIsDecodedWhole() {
        assertEquals(1, sampleSizeFor(1024, 768, 2400))
        assertEquals(1, sampleSizeFor(2400, 1000, 2400))
    }

    @Test fun aLargerPictureIsHalvedUntilItFits() {
        assertEquals(2, sampleSizeFor(4000, 3000, 2400))
        assertEquals(4, sampleSizeFor(3000, 8000, 2400))
    }

    @Test fun anUnknownScreenDecodesWhole() {
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }
}
