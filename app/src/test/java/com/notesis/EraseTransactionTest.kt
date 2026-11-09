package com.notesis
import org.junit.Assert.*
import org.junit.Test

class EraseTransactionTest {
    @Test fun `many frame replacements retain one original and only final pieces`() {
        val a=Any();val original=Any();val z=Any();val left=Any()
        val transaction=EraseTransaction<Any>();val page=mutableListOf(a,original,z)
        var right=Any()
        transaction.record(page,original,listOf(left,right));page.removeAt(1);page.addAll(1,listOf(left,right))
        repeat(120) {
            val next=Any();transaction.record(page,right,listOf(next));page[2]=next;right=next
        }
        val patch=transaction.finish(page)!!
        assertEquals(listOf(original),patch.removed);assertEquals(listOf(left,right),patch.added)
        assertArrayEquals(intArrayOf(1),patch.removedAt);assertArrayEquals(intArrayOf(1,2),patch.addedAt)
        page.removeAll(patch.added.toSet());restoreEraseOrder(page,patch.removed,patch.removedAt)
        assertEquals(listOf(a,original,z),page)
        page.removeAll(patch.removed.toSet());patch.added.forEachIndexed{i,item->redoInkAt(page,item,patch.addedAt[i])}
        assertEquals(listOf(a,left,right,z),page)
        assertNull(transaction.finish(page))
    }
    @Test fun `several originals keep their overlap order after all their descendants are erased`() {
        val originals=List(5){Any()};val page=originals.toMutableList();val transaction=EraseTransaction<Any>()
        val piece=Any()
        transaction.record(page,page[1],listOf(piece));page[1]=piece
        transaction.record(page,page[3],emptyList());page.removeAt(3)
        transaction.record(page,piece,emptyList());page.removeAt(1)
        val patch=transaction.finish(page)!!
        assertTrue(patch.added.isEmpty());assertArrayEquals(intArrayOf(1,3),patch.removedAt)
        restoreEraseOrder(page,patch.removed,patch.removedAt);assertEquals(originals,page)
    }
    @Test fun `a new gesture cannot inherit the previous transaction`() {
        val transaction=EraseTransaction<Any>();val before=Any();val after=Any()
        transaction.record(listOf(before),before,listOf(after));transaction.reset()
        assertNull(transaction.finish(listOf(before)))
        transaction.record(listOf(after),after,emptyList());val patch=transaction.finish(emptyList())!!
        assertEquals(listOf(after),patch.removed);assertTrue(patch.added.isEmpty())
    }
    @Test fun `background refinement preserves the gesture roots and untouched baseline slots`() {
        val original=Any();val untouched=Any();val piece=Any();val refined=Any();val refinedUntouched=Any();val final=Any()
        val transaction=EraseTransaction<Any>()
        transaction.record(listOf(original,untouched),original,listOf(piece))
        transaction.remap(java.util.IdentityHashMap<Any,Any>().apply {
            put(piece,refined);put(untouched,refinedUntouched)
        })
        transaction.record(listOf(refined,refinedUntouched),refined,listOf(final))
        transaction.record(listOf(final,refinedUntouched),refinedUntouched,emptyList())
        val patch=transaction.finish(listOf(final))!!
        assertEquals(listOf(original,refinedUntouched),patch.removed)
        assertArrayEquals(intArrayOf(0,1),patch.removedAt)
        assertEquals(listOf(final),patch.added)
        assertArrayEquals(intArrayOf(0),patch.addedAt)
    }
}
