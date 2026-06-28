package com.gerald.pillagercampaigns.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeduplicatingWorkQueueTest {
    @Test
    fun `queue deduplicates preserves front insertion and clears membership on poll`() {
        val queue = DeduplicatingWorkQueue<String>()

        assertTrue(queue.add("b"))
        assertTrue(queue.add("a", front = true))
        assertFalse(queue.add("b"))
        assertEquals(listOf("a", "b"), queue.snapshot())
        assertEquals(2, queue.size)
        assertTrue(queue.contains("a"))
        assertEquals("a", queue.poll())
        assertFalse(queue.contains("a"))
        assertEquals("b", queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `queue remove and clear drop membership state`() {
        val queue = DeduplicatingWorkQueue<Int>()

        queue.add(1)
        queue.add(2)
        assertTrue(queue.remove(1))
        assertFalse(queue.remove(1))
        assertEquals(listOf(2), queue.snapshot())
        queue.clear()
        assertEquals(0, queue.size)
        assertFalse(queue.contains(2))
    }
}
