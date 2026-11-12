package com.suhel.llamabro.demo.models

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun downloadCountFormats() {
        assertEquals("412", Format.downloads(412))
        assertEquals("880k", Format.downloads(880_000))
        assertEquals("1.2M", Format.downloads(1_234_567))
        assertEquals("0", Format.downloads(0))
        assertEquals("1M", Format.downloads(1_000_000))
        assertEquals("1.5k", Format.downloads(1_500))
        assertEquals("12k", Format.downloads(12_345))
    }

    @Test fun progressTextFormats() {
        assertEquals(
            "1.2 GB of 2.8 GB · 9.8 MB/s · 3 min left",
            Format.progress(1_200_000_000, 2_800_000_000, 9_800_000, 180),
        )
        assertEquals("532 MB of 2.8 GB", Format.progress(532_000_000, 2_800_000_000, null, null))
        assertEquals("1.2 GB of 2.8 GB · under a minute left", Format.progress(1_200_000_000, 2_800_000_000, null, 20))
        assertEquals("1.0 GB of 2.8 GB · 9.8 MB/s", Format.progress(1_000_000_000, 2_800_000_000, 9_800_000, null))
    }

    @Test fun downloadTimeFormats() {
        assertEquals("under a minute", Format.downloadTime(100_000_000))
        assertEquals("under a minute", Format.downloadTime(479_999_999))
        assertEquals("about 1 min", Format.downloadTime(480_000_000))
        assertEquals("about 2 min", Format.downloadTime(480_000_001))
        assertEquals("about 3 min", Format.downloadTime(1_440_000_000))
        assertEquals("about 6 min", Format.downloadTime(2_740_937_888))
    }

    @Test fun sizeInGbHasOneDecimal() {
        assertEquals("2.7 GB", Format.sizeInGb(2_740_937_888))
        assertEquals("0.5 GB", Format.sizeInGb(532_517_120))
        assertEquals("5.7 GB", Format.sizeInGb(5_680_522_464))
        assertEquals("10.0 GB", Format.sizeInGb(10_000_000_000))
    }

    @Test fun timeLeftReadsInSecondsThenMinutes() {
        assertEquals("about 1 s", Format.timeLeft(0))
        assertEquals("about 40 s", Format.timeLeft(40))
        assertEquals("about 1 min", Format.timeLeft(60))
        assertEquals("about 3 min", Format.timeLeft(121))
    }

    @Test fun storageDropsTheDecimalFromTenGigabytes() {
        assertEquals("6.1 GB", Format.storageGb(6_100_000_000))
        assertEquals("120 GB", Format.storageGb(120_000_000_000))
        assertEquals("10 GB", Format.storageGb(10_000_000_000))
    }
}
