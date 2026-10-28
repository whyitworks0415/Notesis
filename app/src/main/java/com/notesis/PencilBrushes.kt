@file:Suppress("RestrictedApi")
@file:OptIn(ExperimentalInkCustomBrushApi::class)

package com.notesis

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.ImmutableVec
import kotlin.math.PI

/**
 * The pencil's brushes: graphite, and three looks made of round particles
 * rather than one outline, each particle given its own size, place and opacity.
 *
 * The scatter is a fixed pseudo-random curve over distance travelled, not
 * Ink's noise. Ink seeds noise per stroke - at random while the pen is down,
 * at zero when a stroke is rebuilt from its saved inputs - so a noise-driven
 * stroke reshuffled itself the moment it was re-meshed after the pen lifted.
 * A curve over distance draws the same dabs every time the same path is
 * modelled. Every size and offset is in multiples of the brush size, so the
 * look holds at any thickness: a thin stamp line is the same brush as a band.
 */
internal object PencilBrushes {

    /** The paper-tooth texture the graphite pencil is drawn through. */
    const val GRAPHITE_TEXTURE = "notesis.graphite.tooth"

    /**
     * Graphite on paper: light pressure leaves a pale, thin, grainy line and
     * bearing down fills the tooth in and widens it a little; laid on its side
     * the lead shades broad and soft. The grain is fixed to the page, as paper
     * is, so crossing strokes share it instead of each carrying its own.
     */
    val graphite: BrushFamily by lazy {
        BrushFamily(
            tip = BrushTip(
                behaviors = listOf(
                    fromInput(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 0.75f,
                        BrushBehavior.Target.SIZE_MULTIPLIER, 0.6f, 1f),
                    fromInput(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 0.8f,
                        BrushBehavior.Target.OPACITY_MULTIPLIER, 0.3f, 1f),
                    // Tilt is radians from upright; the S Pen on some tablets
                    // reports none, and then these simply do not apply. Damped
                    // over a little distance: tilt arrives noisy, and taken raw
                    // its jitter showed as a shading stroke that kept swelling
                    // and fading. Over distance rather than time, so a stroke
                    // rebuilt from its inputs comes out exactly as it went on.
                    fromDampedInput(BrushBehavior.Source.TILT_IN_RADIANS, 0.45f, 1.2f,
                        BrushBehavior.Target.SIZE_MULTIPLIER, 1f, 1.9f, TILT_DAMPING),
                    fromDampedInput(BrushBehavior.Source.TILT_IN_RADIANS, 0.45f, 1.2f,
                        BrushBehavior.Target.OPACITY_MULTIPLIER, 1f, 0.55f, TILT_DAMPING),
                ),
            ),
            paint = BrushPaint(
                textureLayers = listOf(
                    BrushPaint.TextureLayer(
                        clientTextureId = GRAPHITE_TEXTURE,
                        sizeX = GRAPHITE_TILE_UNITS,
                        sizeY = GRAPHITE_TILE_UNITS,
                        sizeUnit = BrushPaint.TextureSizeUnit.STROKE_COORDINATES,
                        mapping = BrushPaint.TextureMapping.TILING,
                    ),
                ),
            ),
            clientBrushFamilyId = GRAPHITE_ID,
        )
    }

    /** Overlapping dabs build up where they cross, which is the whole look. */
    private val dabPaint by lazy { BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE) }

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
                            scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.7f, 1.25f, seed = 101, period = 3.1f),
                            scatter(LATERAL, -0.27f, 0.27f, seed = 102, period = 3.7f),
                            scatter(FORWARD, -0.08f, 0.08f, seed = 103, period = 2.9f),
                            scatter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.4f, 1f, seed = 104, period = 4.3f),
                            scatter(BrushBehavior.Target.LUMINOSITY, -0.06f, 0.06f, seed = 105, period = 5.3f),
                            fromInput(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 0.6f,
                                BrushBehavior.Target.SIZE_MULTIPLIER, 0.55f, 1f),
                        ),
                    ),
                    paint = dabPaint,
                ),
            ),
            clientBrushFamilyId = STAMP_ID,
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
                            scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.25f, 2f, seed = 201, period = 9.1f),
                            scatter(LATERAL, -0.55f, 0.55f, seed = 202, period = 10.3f),
                            scatter(FORWARD, -0.25f, 0.25f, seed = 203, period = 8.3f),
                            scatter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.5f, 1f, seed = 204, period = 11.9f),
                        ),
                    ),
                    paint = dabPaint,
                ),
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.1f,
                        scaleY = 0.1f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.12f,
                        behaviors = listOf(
                            scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.4f, 2f, seed = 211, period = 5.3f),
                            scatter(LATERAL, -0.62f, 0.62f, seed = 212, period = 6.1f),
                            scatter(FORWARD, -0.2f, 0.2f, seed = 213, period = 4.7f),
                            scatter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.4f, 0.9f, seed = 214, period = 7.1f),
                        ),
                    ),
                    paint = dabPaint,
                ),
            ),
            clientBrushFamilyId = BUBBLE_ID,
        )
    }

    /**
     * Fine dots thrown in every direction around the pen, thickest along the
     * middle of the line and thinning out towards its edge, like an airbrush.
     * Three coats, so the dots are dense without each coat being a long chain.
     */
    val spray: BrushFamily by lazy {
        BrushFamily(
            coats = listOf(sprayCoat(301, 1.43f), sprayCoat(311, 1.61f), sprayCoat(321, 1.87f)),
            clientBrushFamilyId = SPRAY_ID,
        )
    }

    private fun sprayCoat(seed: Int, period: Float) = BrushCoat(
        tip = BrushTip(
            scaleX = 0.045f,
            scaleY = 0.045f,
            cornerRounding = 1f,
            particleGapDistanceScale = 0.035f,
            behaviors = listOf(
                scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.35f, 1.8f, seed, period),
                // An even spread of distances from the centre puts more dots
                // near it than at the rim, which is what a spray can does.
                BrushBehavior(
                    listOf(
                        BrushBehavior.PolarTargetNode(
                            BrushBehavior.PolarTarget
                                .POSITION_OFFSET_ABSOLUTE_IN_RADIANS_AND_MULTIPLES_OF_BRUSH_SIZE,
                            0f, (2 * PI).toFloat(), wander(seed + 1, period * 1.13f),
                            0f, 0.62f, wander(seed + 2, period * 0.89f),
                        ),
                    ),
                ),
                scatter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.5f, 1f, seed + 3, period * 1.29f),
            ),
        ),
        paint = dabPaint,
    )

    /**
     * A soft lead laid down grain by grain: pressure decides how dark it goes
     * - from a whisper to most of the colour - while the width stays the
     * lead's own, the way a pencil behaves and a pen does not. Small dabs,
     * slightly uneven in size and place, build up where they overlap, which
     * is the grain; the second coat is the scattered tooth at the edges.
     */
    val soft: BrushFamily by lazy {
        BrushFamily(
            coats = listOf(
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.5f,
                        scaleY = 0.5f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.12f,
                        behaviors = listOf(
                            scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.8f, 1f, seed = 401, period = 3.3f),
                            scatter(LATERAL, -0.22f, 0.22f, seed = 402, period = 2.7f),
                            scatter(FORWARD, -0.06f, 0.06f, seed = 403, period = 3.9f),
                            scatter(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.75f, 1f, seed = 404, period = 4.1f),
                            // Each dab is faint; about four overlap at any
                            // point, so the line runs from ~0.1 to ~0.75 of
                            // the colour across the pressure range.
                            fromInput(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f,
                                BrushBehavior.Target.OPACITY_MULTIPLIER, 0.035f, 0.32f),
                        ),
                    ),
                    paint = dabPaint,
                ),
                BrushCoat(
                    tip = BrushTip(
                        scaleX = 0.09f,
                        scaleY = 0.09f,
                        cornerRounding = 1f,
                        particleGapDistanceScale = 0.09f,
                        behaviors = listOf(
                            scatter(BrushBehavior.Target.SIZE_MULTIPLIER, 0.5f, 1.4f, seed = 411, period = 2.3f),
                            scatter(LATERAL, -0.42f, 0.42f, seed = 412, period = 1.9f),
                            fromInput(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f,
                                BrushBehavior.Target.OPACITY_MULTIPLIER, 0.06f, 0.4f),
                        ),
                    ),
                    paint = dabPaint,
                ),
            ),
            clientBrushFamilyId = SOFT_ID,
        )
    }

    /**
     * Writing faster draws a finer line, as ink runs thinner from a nib that
     * is moving: down to about 60% of the width at a brisk stroke. Damped
     * over distance, so the width eases between speeds instead of following
     * every jolt of the hand, and a rebuilt stroke matches the one written.
     */
    fun speedThinning(): BrushBehavior = BrushBehavior(
        listOf(
            BrushBehavior.TargetNode(
                BrushBehavior.Target.SIZE_MULTIPLIER, 1f, 0.6f,
                BrushBehavior.DampingNode(
                    BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    SPEED_DAMPING,
                    BrushBehavior.SourceNode(
                        BrushBehavior.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 4f, 90f,
                    ),
                ),
            ),
        ),
    )

    private const val SOFT_ID = "notesis.pencil.soft"
    private const val STAMP_ID = "notesis.pencil.stamp"
    private const val BUBBLE_ID = "notesis.pencil.bubble"
    private const val SPRAY_ID = "notesis.pencil.spray"

    /**
     * Whether [family] is one of the dab looks, by the id it carries - cheap,
     * and true of copies Ink hands back as much as of the originals here.
     */
    fun isDab(family: BrushFamily): Boolean = family.clientBrushFamilyId.let {
        it == STAMP_ID || it == BUBBLE_ID || it == SPRAY_ID || it == SOFT_ID
    }

    /** Whether [family] is the graphite pencil, by its id. */
    fun isGraphite(family: BrushFamily): Boolean = family.clientBrushFamilyId == GRAPHITE_ID

    private const val GRAPHITE_ID = "notesis.pencil.graphite"

    /** Graphite tooth tile size on the page, in page units. */
    private const val GRAPHITE_TILE_UNITS = 48f

    /** Points per curve: about one per dab over a period, so neighbours differ. */
    private const val WANDER_POINTS = 44

    private val LATERAL = BrushBehavior.Target.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE
    private val FORWARD = BrushBehavior.Target.POSITION_OFFSET_FORWARD_IN_MULTIPLES_OF_BRUSH_SIZE

    /**
     * A value in [0, 1] that jumps about with distance travelled and repeats
     * every [period] brush sizes. Periods differ from target to target and
     * coat to coat, so the combined pattern does not visibly repeat.
     * [java.util.Random] with a fixed seed is specified to give the same
     * sequence everywhere, so a saved stroke looks the same on any device.
     */
    private fun wander(seed: Int, period: Float): BrushBehavior.ValueNode {
        val random = java.util.Random(seed.toLong())
        val points = List(WANDER_POINTS) { i ->
            ImmutableVec((i + random.nextFloat()) / WANDER_POINTS, random.nextFloat())
        }
        return BrushBehavior.ResponseNode(
            EasingFunction.Linear(points),
            BrushBehavior.SourceNode(
                BrushBehavior.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE,
                0f, period, BrushBehavior.OutOfRange.REPEAT,
            ),
        )
    }

    private fun scatter(target: BrushBehavior.Target, from: Float, to: Float, seed: Int, period: Float) =
        BrushBehavior(listOf(BrushBehavior.TargetNode(target, from, to, wander(seed, period))))

    /** Brush sizes over which tilt is smoothed. */
    private const val TILT_DAMPING = 1.5f

    /** Brush sizes over which speed is smoothed for [speedThinning]. */
    private const val SPEED_DAMPING = 3f

    /** As [fromInput], with the source smoothed over [damping] brush sizes of travel. */
    private fun fromDampedInput(
        source: BrushBehavior.Source, sourceFrom: Float, sourceTo: Float,
        target: BrushBehavior.Target, from: Float, to: Float, damping: Float,
    ) = BrushBehavior(
        listOf(
            BrushBehavior.TargetNode(
                target, from, to,
                BrushBehavior.DampingNode(
                    BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    damping,
                    BrushBehavior.SourceNode(source, sourceFrom, sourceTo),
                ),
            ),
        ),
    )

    /** [source] across [sourceFrom]..[sourceTo] mapped onto [target] across [from]..[to]. */
    private fun fromInput(
        source: BrushBehavior.Source, sourceFrom: Float, sourceTo: Float,
        target: BrushBehavior.Target, from: Float, to: Float,
    ) = BrushBehavior(
        listOf(
            BrushBehavior.TargetNode(
                target, from, to,
                BrushBehavior.SourceNode(source, sourceFrom, sourceTo),
            ),
        ),
    )
}

/**
 * Dabs are laid by distance along the modelled path, and the path Ink models
 * depends on the brush's epsilon. Rebuilding a dab stroke at a new zoom's
 * epsilon would move every dab, so these keep one epsilon, set by their size.
 */
internal fun dabEpsilon(width: Float): Float = (width * 0.004f).coerceAtLeast(0.0001f)

/** The epsilon a stroke of [family] should be built at, for a view needing [viewportEpsilon]. */
internal fun inkEpsilon(family: BrushFamily, width: Float, viewportEpsilon: Float): Float =
    if (PencilBrushes.isDab(family)) dabEpsilon(width) else strokeEpsilon(width, viewportEpsilon)
