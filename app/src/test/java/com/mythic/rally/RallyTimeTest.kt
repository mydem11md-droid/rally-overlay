package com.mythic.rally

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RallyTimeTest {
    @Test fun insertsColonDuringTyping() {
        assertEquals("1:01",RallyTime.preview("101"))
        assertEquals("1:02",RallyTime.preview("102"))
        assertEquals("2:38",RallyTime.preview("238"))
        assertEquals("7:40",RallyTime.preview("740"))
        assertEquals("1:11",RallyTime.preview("111"))
        assertEquals("12:30",RallyTime.preview("1230"))
        assertEquals("3:28",RallyTime.preview("0328"))
    }
    @Test fun shortInputIsNormalizedOnFinish() {
        assertEquals("5",RallyTime.preview("5"))
        assertEquals("50",RallyTime.preview("50"))
        assertEquals("0:05",RallyTime.normalize("5"))
        assertEquals("0:50",RallyTime.normalize("50"))
    }
    @Test fun overflowSecondsCarryIntoMinutes() {
        assertEquals("3:08",RallyTime.normalize("2:68"))
        assertEquals("60:00",RallyTime.normalize("6000"))
        assertNull(RallyTime.normalize("6001"))
    }
    @Test fun stripsNonDigitsAndCapsFourDigits() {
        assertEquals("3:28",RallyTime.preview("03:28abc"))
        assertEquals("12:30",RallyTime.preview("123099"))
    }
}