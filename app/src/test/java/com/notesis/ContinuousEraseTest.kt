package com.notesis

import org.junit.Assert.*
import org.junit.Test

class ContinuousEraseTest {
    @Test fun `collinear history merges exactly while turns and reversals remain`() {
        val trail=EraseBatch()
        repeat(100) {trail.append(it.toFloat(),0f,2f,it.toLong())}
        assertEquals(1,trail.count);assertEquals(0f,trail.data[0],0f);assertEquals(99f,trail.data[2],0f)
        trail.append(99f,10f,2f,101);assertEquals(2,trail.count)
        trail.append(99f,5f,2f,102);assertEquals(3,trail.count)
    }

    @Test fun `redo puts clipped pieces back into their original overlap slots`() {
        val current=mutableListOf("a","d","f")
        redoInkAt(current,"b-left",1);redoInkAt(current,"b-right",2);redoInkAt(current,"e-left",4)
        assertEquals(listOf("a","b-left","b-right","d","e-left","f"),current)
    }
    private fun disk(x: Float, y: Float, radius: Float) = floatArrayOf(x,y,x,y,radius)
    @Test fun `two input line retains both sides of a cut between samples`() {
        val spans=continuousKeep(floatArrayOf(0f,1000f),floatArrayOf(0f,0f),2,disk(510f,0f,1f),1,0f)!!
        assertEquals(2,spans.size)
        assertEquals(.509,spans[0].to,1e-10);assertEquals(.511,spans[1].from,1e-10)
        // The previous sampled-input algorithm deleted this entire two-point line.
        assertEquals(emptyList<IntRange>(),keptRuns(floatArrayOf(0f,1000f),floatArrayOf(0f,0f),2,510f,0f,510f,0f,1f))
    }
    @Test fun `contact on ink cuts immediately without requiring motion`() {
        val spans=continuousKeep(floatArrayOf(-100f,100f),FloatArray(2),2,disk(0f,0f,5f),1,0f)!!
        assertEquals(.475,spans[0].to,1e-10);assertEquals(.525,spans[1].from,1e-10)
    }
    @Test fun `small retained island between cuts need not contain an original sample`() {
        val sweeps=disk(200f,0f,50f)+disk(800f,0f,50f)
        val spans=continuousKeep(floatArrayOf(0f,1000f),FloatArray(2),2,sweeps,2,0f)!!
        assertEquals(3,spans.size)
        assertEquals(.25,spans[1].from,1e-10);assertEquals(.75,spans[1].to,1e-10)
    }
    @Test fun `sampling density does not move a straight line cut`() {
        val sweep=disk(510f,0f,1f)
        for(count in listOf(2,3,21,1001)) {
            val spans=continuousKeep(FloatArray(count){it*1000f/(count-1)},FloatArray(count),count,sweep,1,0f)!!
            assertEquals(509.0,spans[0].to*1000/(count-1),1e-4)
            assertEquals(511.0,spans[1].from*1000/(count-1),1e-4)
        }
    }
    @Test fun `a historical corner is not replaced with a diagonal shortcut`() {
        val trail=EraseBatch()
        trail.append(0f,0f,1f,1);trail.append(0f,10f,1f,2);trail.append(10f,10f,1f,3)
        val spans=continuousKeep(floatArrayOf(0f,10f),floatArrayOf(0f,10f),2,trail.data,trail.count,0f)!!
        assertEquals(listOf(InkSpan(.1,.9)),spans)
        trail.drained();trail.append(20f,10f,1f,4)
        assertEquals(10f,trail.data[0],0f);assertEquals(10f,trail.data[1],0f)
        trail.reset();trail.append(50f,20f,1f,10)
        assertEquals(50f,trail.data[0],0f);assertEquals(20f,trail.data[1],0f)
    }
    @Test fun `unchanged contact and stale historical samples do not create extra work`() {
        val trail=EraseBatch();trail.append(0f,0f,1f,10);trail.append(0f,0f,1f,20)
        trail.append(100f,0f,1f,15)
        assertEquals(1,trail.count)
        trail.drained();assertEquals(0,trail.count)
        trail.append(2f,0f,1f,21);assertEquals(1,trail.count)
    }
    @Test fun `tangent contact leaves a line intact and a disk erases a dot`() {
        assertNull(continuousKeep(floatArrayOf(-10f,10f),FloatArray(2),2,disk(0f,1f,1f),1,0f))
        assertEquals(emptyList<InkSpan>(),continuousKeep(floatArrayOf(0f),floatArrayOf(0f),1,disk(0f,0f,1f),1,0f))
        assertNull(continuousKeep(floatArrayOf(5f),floatArrayOf(5f),1,disk(0f,0f,1f),1,0f))
    }
    @Test fun `undo restores erased strokes at their original overlap order`() {
        val current=mutableListOf("a","d","f")
        restoreEraseOrder(current,listOf("b","c","e"),intArrayOf(1,2,4))
        assertEquals(listOf("a","b","c","d","e","f"),current)
    }
    @Test fun `orientation interpolation takes the short path across the angle seam`() {
        val full=(2*Math.PI).toFloat()
        val result=inkOrientationBetween(full-.1f,.1f,.5f)
        assertTrue(result<.00001f || result>full-.00001f)
        assertEquals(-1f,inkOrientationBetween(-1f,-1f,.5f),0f)
    }
    @Test fun `selected and barrel erasers share the same contact policy`() {
        assertTrue(eraseContact(true,false));assertTrue(eraseContact(false,true))
        assertTrue(eraseContact(true,true));assertFalse(eraseContact(false,false))
    }
}
