@file:Suppress("RestrictedApi")
@file:OptIn(ExperimentalInkCustomBrushApi::class)

package com.notesis

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import kotlin.math.PI

/**
 * The pencil's dab brushes: strokes made of round particles rather than one
 * outline, each particle given its own size, place and opacity.
 *
 * The scatter comes from noise over distance travelled, not from a random
 * number generator, so a stroke is the same every time it is rebuilt from its
 * saved inputs - on reload, in the wet ink and in the committed page alike.
 * Every size and offset is in multiples of the brush size, so the look holds
 * at any thickness: a thin stamp line is the same brush as a broad band.
 */
internal object PencilBrushes {

    /** Overlapping dabs build up where they cross, which is the whole look. */
    private val paint by lazy { BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE) }

    /**
     * Soft round dabs packed into a band: heavy where they pile up, lumpy at
     * the edge, a little lighter or darker from dab to dab.
     */
    val stamp: BrushFamily by lazy {
        BrushFamily(
            coats = listOf(
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.46f,
                        scaleY = 0.46f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.07f,
                        behaviors = listOf(
                            jitter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.7f, 1.25f, seed = 101),
                            jitter(LATERAL, -0.27f, 0.27f, seed = 102),
                            jitter(FORWARD, -0.08f, 0.08f, seed = 103),
                            jitter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.4f, 1f, seed = 104),
                            jitter(BrushBehavior.Target.LUMINOSITY, -0.06f, 0.06f, seed = 105),
                            pressure(0.55f),
                        ),
                    ),
                    paint = paint,
                ),
            ),
            clientBrushFamilyId = "notesis.pencil.stamp",
        )
    }

    /**
     * Loose bubbles of very different sizes, with a sprinkling of small ones
     * between them, spread wider than the stroke itself.
     */
    val bubble: BrushFamily by lazy {
        BrushFamily(
            coats = listOf(
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.34f,
                        scaleY = 0.34f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.2f,
                        behaviors = listOf(
                            jitter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.25f, 2f, seed = 201),
                            jitter(LATERAL, -0.55f, 0.55f, seed = 202),
                            jitter(FORWARD, -0.25f, 0.25f, seed = 203),
                            jitter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.5f, 1f, seed = 204),
                        ),
                    ),
                    paint = paint,
                ),
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.1f,
                        scaleY = 0.1f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.12f,
                        behaviors = listOf(
                            jitter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.4f, 2f, seed = 211),
                            jitter(LATERAL, -0.62f, 0.62f, seed = 212),
                            jitter(FORWARD, -0.2f, 0.2f, seed = 213),
                            jitter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.4f, 0.9f, seed = 214),
                        ),
                    ),
                    paint = paint,
                ),
            ),
            clientBrushFamilyId = "notesis.pencil.bubble",
        )
    }

    /**
     * Fine dots thrown in every direction around the pen, thickest along the
     * middle of the line and thinning out towards its edge, like an airbrush.
     * Three coats, so the dots are dense without each coat being a long chain.
     */
    val spray: BrushFamily by lazy {
        BrushFamily(
            coats = listOf(sprayCoat(301), sprayCoat(311), sprayCoat(321)),
            clientBrushFamilyId = "notesis.pencil.spray",
        )
    }

    private fun sprayCoat(seed: Int) = BrushCoat(
        tip = BrushTip(
            scaleX = 0.045f,
            scaleY = 0.045f,
            cornerRounding = 1f,
            particleGapDistanceScale = 0.035f,
            behaviors = listOf(
                jitter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.35f, 1.8f, seed),
                // An even spread of distances from the centre puts more dots
                // near it than at the rim, which is what a spray can does.
                BrushBehavior(
                    listOf(
                        BrushBehavior.PolarTargetNode(
                            BrushBehavior.PolarTarget
                                .POSITION_OFFSET_ABSOLUTE_IN_RADIANS_AND_MULTIPLES_OF_BRUSH_SIZE,
                            0f, (2 * PI).toFloat(), noise(seed + 1),
                            0f, 0.62f, noise(seed + 2),
                        ),
                    ),
                ),
                jitter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.5f, 1f, seed + 3),
            ),
        ),
        paint = paint,
    )

    private val LATERAL = BrushBehavior.Target.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE
    private val FORWARD = BrushBehavior.Target.POSITION_OFFSET_FORWARD_IN_MULTIPLES_OF_BRUSH_SIZE

    /**
     * A period shorter than the gap between particles, so neighbouring dabs
     * draw unrelated values rather than drifting together.
     */
    private const val NOISE_PERIOD = 0.03f

    private fun noise(seed: Int) = BrushBehavior.NoiseNode(
        seed,
        BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
        NOISE_PERIOD,
    )

    private fun jitter(target: BrushBehavior.Target, from: Float, to: Float, seed: Int) =
        BrushBehavior(listOf(BrushBehavior.TargetNode(target, from, to, noise(seed))))

    /** Lighter pressure, smaller dabs. A finger reports none and draws full size. */
    private fun pressure(lightest: Float) = BrushBehavior(
        listOf(
            BrushBehavior.TargetNode(
                BrushBehavior.Target.SIZE_MULTIPLIER, lightest, 1f,
                BrushBehavior.SourceNode(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 0.6f),
            ),
        ),
    )
}
