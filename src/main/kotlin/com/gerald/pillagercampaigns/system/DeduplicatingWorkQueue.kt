package com.gerald.pillagercampaigns.system

import java.util.ArrayDeque

internal class DeduplicatingWorkQueue<T> {
    private val queue = ArrayDeque<T>()
    private val queued = mutableSetOf<T>()

    val size: Int
        get() = queue.size

    fun clear() {
        queue.clear()
        queued.clear()
    }

    fun add(item: T, front: Boolean = false): Boolean {
        if (!queued.add(item)) return false
        if (front) queue.addFirst(item) else queue.addLast(item)
        return true
    }

    fun poll(): T? {
        val item = queue.pollFirst() ?: return null
        queued.remove(item)
        return item
    }

    fun remove(item: T): Boolean {
        if (!queued.remove(item)) return false
        queue.remove(item)
        return true
    }

    fun contains(item: T): Boolean = item in queued

    fun snapshot(): List<T> = queue.toList()
}
