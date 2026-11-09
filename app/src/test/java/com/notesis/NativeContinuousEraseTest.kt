package com.notesis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class NativeContinuousEraseTest {
    companion object {
        @JvmStatic @BeforeClass fun backend() {
            SpatialNative.prepare();repeat(200) { if(SpatialNative.ready)return;Thread.sleep(5) }
            assertTrue(SpatialNative.ready)
        }
    }
    @Test fun `continuous JNI spans match reference geometry and an independent point oracle`() {
        val random=Random(8793);val workspace=PartialEraseWorkspace()
        repeat(500) {
            val count=random.nextInt(129,500)
            workspace.prepare(count)
            var x=0f;var y=0f
            repeat(count) { i->x+=random.nextFloat()*20-10;y+=random.nextFloat()*20-10;workspace.put(i,x,y) }
            val sweeps=FloatArray(20)
            for(s in 0..3) {
                val p=s*5;repeat(4){sweeps[p+it]=random.nextFloat()*100-50};sweeps[p+4]=2.431f
            }
            val expected=continuousKeep(workspace.xs,workspace.ys,count,sweeps,4,.3f)
            val actual=workspace.fragments(sweeps,4,.3f)
            assertEquals(expected?.size,actual?.size)
            if(expected!=null) for(i in expected.indices) {
                assertEquals(expected[i].from,actual!![i].from,1e-7)
                assertEquals(expected[i].to,actual[i].to,1e-7)
            }
            repeat(20) {
                val at=random.nextDouble(count-1.0);val edge=floor(at).toInt();val t=at-edge
                val px=(workspace.xs[edge]+(workspace.xs[edge+1]-workspace.xs[edge])*t).toFloat()
                val py=(workspace.ys[edge]+(workspace.ys[edge+1]-workspace.ys[edge])*t).toFloat()
                var margin=Float.POSITIVE_INFINITY
                for(s in 0..3) {val p=s*5;margin=min(margin,pointSegmentDistance(px,py,sweeps[p],sweeps[p+1],sweeps[p+2],sweeps[p+3])-sweeps[p+4]-.3f)}
                if(abs(margin)>.001f) assertEquals(margin>0f,actual==null || actual.any{at>=it.from && at<=it.to})
            }
        }
    }
    @Test fun `native continuous clipper preserves sparse boundaries and guards capacities`() {
        val points=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putFloat(0f).putFloat(0f).putFloat(1000f).putFloat(0f)
        val sweeps=ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder()).putFloat(510f).putFloat(0f).putFloat(510f).putFloat(0f).putFloat(1f)
        val out=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder())
        assertEquals(2,SpatialNative.clipPolyline(points,2,sweeps,1,0f,out))
        assertEquals(.509,out.getDouble(8),1e-10);assertEquals(.511,out.getDouble(16),1e-10)
        assertEquals(-3,SpatialNative.clipPolyline(points,2,sweeps,1,0f,ByteBuffer.allocateDirect(16)))
        assertEquals(-2,SpatialNative.clipPolyline(ByteBuffer.allocate(16),2,sweeps,1,0f,out))
    }
}
