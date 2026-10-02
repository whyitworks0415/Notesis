package com.notesis

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

// Kotlin port of liquid_glass_easy 4.3.2's animated nav-bar ticker,
// liquidGlassSpringStep and LiquidGlassLensMotion. Copyright (c) 2025 Ahmed
// Gamil, MIT. Full license and source provenance: assets/spotiglass/upstream.
internal class SpotiGlassMotion(initial: Float, private val control: Boolean = false) {
    private class Spring(var x: Double = 0.0, var velocity: Double = 0.0) {
        fun step(target: Double, dt: Double, stiffness: Double, damping: Double) {
            var remaining = dt
            while (remaining > 0) {
                val step = minOf(remaining, 1.0 / 240)
                velocity += (-stiffness * (x - target) - damping * velocity) * step
                x += velocity * step
                remaining -= step
            }
        }
        fun settle(target: Double, positionEpsilon: Double, velocityEpsilon: Double): Boolean {
            if (abs(x - target) >= positionEpsilon || abs(velocity) >= velocityEpsilon) return false
            x = target; velocity = 0.0
            return true
        }
    }
    private val travel = Spring(initial.toDouble())
    private val liftX = Spring()
    private val liftY = Spring()
    private val material = Spring()
    private val samples = MotionSampleWindow()
    private var from = initial.toDouble()
    private var target = from
    private var followTarget = from
    private var lifted = false
    private var handover = 1.0
    private var rawDeviation = 0.0
    private var travelSign = 0.0
    private var easedSign = 0.0
    var running = false; private set
    var dragging = false; private set
    val position get() = travel.x.toFloat()
    val growX get() = liftX.x.toFloat()
    val growY get() = liftY.x.toFloat()
    val progress get() = material.x.coerceIn(0.0, 1.0).toFloat()
    val presence get() = (1 - handover).coerceIn(0.0, 1.0).toFloat()
    var deviation = 0f; private set

    fun select(index: Float) {
        if (target == index.toDouble() && !dragging) return
        dragging = false
        from = travel.x; target = index.toDouble()
        travelSign = sign(target - from)
        lifted = true; running = true
    }
    fun grab(index: Float) {
        dragging = true; followTarget = index.toDouble()
        lifted = true; running = true
        travel.velocity = 0.0; travelSign = 0.0
    }
    fun follow(index: Float) { followTarget = index.toDouble() }
    fun release(index: Float) {
        dragging = false; from = travel.x; target = index.toDouble()
        travel.velocity = 0.0; travelSign = sign(target - from)
        running = true
    }
    fun snap(index: Float) {
        travel.x = index.toDouble(); travel.velocity = 0.0; target = travel.x
        liftX.x = 0.0; liftY.x = 0.0; material.x = 0.0
        liftX.velocity = 0.0; liftY.velocity = 0.0; material.velocity = 0.0
        handover = 1.0; rawDeviation = 0.0; deviation = 0f
        lifted = false; dragging = false; running = false; samples.clear()
    }
    fun tick(now: Double, dt: Double, cellWidth: Float) {
        if (!running || dt <= 0) return
        // Pause/resume cannot integrate an unbounded duration into the spring.
        val step = dt.coerceAtMost(0.05)
        val landed: Boolean
        if (dragging) {
            if (control) travel.x = followTarget
            else travel.x += (followTarget - travel.x) * (1 - exp(-step / 0.05))
            landed = false
        } else {
            travel.step(target, step, 280.0, 31.4)
            landed = travel.settle(target, 0.003, 0.05)
            val span = target - from
            val fraction = if (abs(span) < 1e-6) 1.0 else (travel.x - from) / span
            if (landed || (!control && fraction >= 0.92)) lifted = false
        }
        val liftTarget = if (lifted) 1.0 else 0.0
        liftX.step(liftTarget, step, 250.0, 19.0)
        liftY.step(liftTarget, step, 250.0, 22.1)
        material.step(liftTarget, step, 1000.0, 63.3)
        liftX.settle(liftTarget, 0.001, 0.01)
        liftY.settle(liftTarget, 0.001, 0.01)
        material.settle(liftTarget, 0.001, 0.01)
        samples.add(travel.x * cellWidth, now)
        val acceleration = samples.acceleration()
        val raw = (acceleration * 0.00007).coerceIn(-0.12, 0.12)
        rawDeviation += (raw - rawDeviation) * (step / 0.18).coerceIn(0.0, 1.0)
        if (easedSign == 0.0) easedSign = travelSign
        else if (easedSign != travelSign) {
            easedSign += (travelSign - easedSign) * (1 - exp(-step / 0.25))
            if (abs(easedSign - travelSign) < 0.01) easedSign = travelSign
        }
        deviation = (rawDeviation * (1 - abs(easedSign)) - easedSign * abs(rawDeviation)).toFloat()
        val handoverTarget = if (lifted) 0.0 else 1.0
        val tau = if (handoverTarget > handover) 0.09 else 0.05
        handover += (handoverTarget - handover) * (1 - exp(-step / tau))
        if (abs(handoverTarget - handover) < 0.002) handover = handoverTarget
        if (landed && !dragging && liftX.x == 0.0 && liftY.x == 0.0 && material.x == 0.0 &&
            abs(deviation) < 0.0005 && handover == 1.0) {
            running = false; samples.clear(); rawDeviation = 0.0; deviation = 0f
            travelSign = 0.0; easedSign = 0.0
        }
    }
}

/** Same 300ms derivative window, without Pair/list allocation on every frame. */
internal class MotionSampleWindow {
    private var positions = DoubleArray(64)
    private var times = DoubleArray(64)
    private var start = 0
    private var size = 0

    fun clear() { start = 0; size = 0 }

    fun add(position: Double, time: Double) {
        while (size > 0 && times[start] < time - 0.3) {
            start = (start + 1) % times.size
            size--
        }
        if (size == times.size) {
            val newPositions = DoubleArray(size * 2)
            val newTimes = DoubleArray(size * 2)
            for (i in 0 until size) {
                val index = (start + i) % times.size
                newPositions[i] = positions[index]
                newTimes[i] = times[index]
            }
            positions = newPositions; times = newTimes; start = 0
        }
        val index = (start + size) % times.size
        positions[index] = position; times[index] = time; size++
    }

    fun acceleration(): Double {
        var previousVelocity = 0.0
        var previousTime = 0.0
        var hasVelocity = false
        var total = 0.0
        var count = 0
        for (i in 1 until size) {
            val a = (start + i - 1) % times.size
            val b = (start + i) % times.size
            val delta = times[b] - times[a]
            if (delta <= 0) continue
            val velocity = (positions[b] - positions[a]) / delta
            val time = (times[a] + times[b]) / 2
            if (hasVelocity && time > previousTime) {
                total += (velocity - previousVelocity) / (time - previousTime)
                count++
            }
            previousVelocity = velocity; previousTime = time; hasVelocity = true
        }
        return if (count == 0) 0.0 else total / count
    }
}
