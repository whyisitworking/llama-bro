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

    @Test fun timeLeftTextFormats() {
        assertEquals("under a minute left", Format.timeLeftText(20))
        assertEquals("3 min left", Format.timeLeftText(180))
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

    @Test fun arrivedSaysBothNumbersInTheTotalsUnit() {
        // one string, never a flip at 1 GB
        assertEquals("0.0 of 3.4 GB", Format.arrived(45_000_000, 3_400_000_000))
        assertEquals("0.9 of 3.4 GB", Format.arrived(900_000_000, 3_400_000_000))
        assertEquals("1.2 of 3.4 GB", Format.arrived(1_200_000_000, 3_400_000_000))
        assertEquals("35 of 740 MB", Format.arrived(35_000_000, 740_000_000))
        assertEquals("310 of 740 MB", Format.arrived(310_000_000, 740_000_000))
    }
}
