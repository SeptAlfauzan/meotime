package com.septaalfauzan.meotime.presentation.limit

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelColumnMathTest {

    @Test
    fun centeredIndex_roundsToNearestRow() {
        assertEquals(7, wheelCenteredIndex(7, 0, 64f))
        assertEquals(7, wheelCenteredIndex(7, 23, 64f))
        assertEquals(8, wheelCenteredIndex(7, 40, 64f))
        assertEquals(8, wheelCenteredIndex(7, 64, 64f))
    }

    @Test
    fun centeredIndex_addsScrolledRows() {
        assertEquals(10, wheelCenteredIndex(7, 192, 64f))
    }

    @Test
    fun wheelValue_wrapsAtBothEnds() {
        assertEquals(0, wheelValue(24, 24))
        assertEquals(0, wheelValue(60, 60))
        assertEquals(23, wheelValue(47, 24))
        assertEquals(59, wheelValue(119, 60))
        assertEquals(0, wheelValue(0, 60))
    }

    @Test
    fun centeredValue_returnsModuloValueAtCenter() {
        assertEquals(7, wheelCenteredValue(7, 0, 64f, 24))
        assertEquals(0, wheelCenteredValue(23, 40, 64f, 24))
    }

    @Test
    fun visibleValues_returnsTopCenterBottom() {
        assertEquals(listOf(6, 7, 8), wheelVisibleValues(7, 0, 64f, 24))
        assertEquals(listOf(23, 0, 1), wheelVisibleValues(24, 0, 64f, 24))
    }
}
