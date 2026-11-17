package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {
    private data class Item(val id: String, val ts: Long, val value: String, val deleted: Boolean = false)

    private fun merge(local: List<Item>, remote: List<Item>) = mergeRecords(local, remote, { it.id }, { it.ts })

    @Test
    fun newerWinsPerRecord() {
        val local = listOf(Item("a", 1, "local-a"), Item("b", 5, "local-b"))
        val remote = listOf(Item("a", 2, "remote-a"), Item("b", 3, "remote-b"), Item("c", 1, "remote-c"))
        val (merged, changed) = merge(local, remote)
        val byId = merged.associateBy { it.id }
        assertEquals("remote-a", byId["a"]?.value)
        assertEquals("local-b", byId["b"]?.value)
        assertEquals("remote-c", byId["c"]?.value)
        assertEquals(listOf("a", "c"), changed.map { it.id })
    }

    @Test
    fun deletionsPropagate() {
        val (merged, _) = merge(listOf(Item("a", 1, "x")), listOf(Item("a", 2, "x", deleted = true)))
        assertEquals(true, merged.single().deleted)
    }

    @Test
    fun localOnlyRecordsAreKept() {
        val (merged, changed) = merge(listOf(Item("only-local", 1, "x")), emptyList())
        assertEquals(1, merged.size)
        assertEquals(0, changed.size)
    }

    @Test
    fun equalTimestampKeepsLocal() {
        val (merged, changed) = merge(listOf(Item("a", 5, "local")), listOf(Item("a", 5, "remote")))
        assertEquals("local", merged.single().value)
        assertEquals(0, changed.size)
    }

    @Test
    fun mergingTwiceIsStable() {
        val local = listOf(Item("a", 1, "l"), Item("b", 4, "l"))
        val remote = listOf(Item("a", 3, "r"), Item("c", 2, "r"))
        val first = merge(local, remote).first
        val (second, changed) = merge(first, remote)
        assertEquals(first.toSet(), second.toSet())
        assertEquals(0, changed.size)
    }
}
