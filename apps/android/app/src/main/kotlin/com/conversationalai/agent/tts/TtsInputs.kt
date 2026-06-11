package com.conversationalai.agent.tts

/**
 * One clause's static-shape inputs for the short-chunk DLC chain, already in DLC layout.
 * Shapes (DLC layout = last two axes transposed vs ONNX):
 *   textIds     int32 [1,64]            (layout-invariant)
 *   textMask    f32   [1,64,1]
 *   styleTtl    f32   [1,256,50]
 *   styleDp     f32   [1,8,16]
 *   noisyLatent f32   [1,128,144]
 *   latentMask  f32   [1,128,1]
 */
data class TtsInputs(
    val textIds: IntArray,
    val textMask: FloatArray,
    val styleTtl: FloatArray,
    val styleDp: FloatArray,
    val noisyLatent: FloatArray,
    val latentMask: FloatArray,
    /** Speech-rate divisor for the duration predictor (1.05 = Supertonic's original fixed value;
     *  higher = faster speech). Carried with the inputs so settings apply per clause. */
    val speed: Float = 1.05f,
)
