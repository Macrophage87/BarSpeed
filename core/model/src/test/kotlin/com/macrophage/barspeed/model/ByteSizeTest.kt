package com.macrophage.barspeed.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ByteSize.format] at the boundaries between its three units, and at the
 * one value ([Long.MIN_VALUE] excluded) where a naive implementation is
 * likeliest to pick the wrong branch: exactly on a boundary rather than
 * comfortably inside a range.
 */
class ByteSizeTest {
    @Test
    fun `zero bytes formats in bytes`() {
        assertEquals("0 B", ByteSize.format(0L))
    }

    @Test
    fun `a value well under 1 KB formats in bytes`() {
        assertEquals("512 B", ByteSize.format(512L))
    }

    @Test
    fun `exactly 1000 bytes is the first value to format in KB, not B`() {
        assertEquals("1.0 KB", ByteSize.format(1_000L))
    }

    @Test
    fun `999 bytes is the last value to format in B, not KB`() {
        assertEquals("999 B", ByteSize.format(999L))
    }

    @Test
    fun `a value in the KB range rounds to one decimal place`() {
        assertEquals("4.2 KB", ByteSize.format(4_200L))
    }

    @Test
    fun `exactly 1000000 bytes is the first value to format in MB, not KB`() {
        assertEquals("1.0 MB", ByteSize.format(1_000_000L))
    }

    @Test
    fun `999999 bytes is the last value to format in KB, not MB`() {
        assertEquals("1000.0 KB", ByteSize.format(999_999L))
    }

    @Test
    fun `a mature rescued corpus formats in MB with one decimal place`() {
        assertEquals("52.0 MB", ByteSize.format(52_000_000L))
    }

    @Test
    fun `a corpus tens of megabytes large still formats sensibly`() {
        assertEquals("120.0 MB", ByteSize.format(120_000_000L))
    }
}
