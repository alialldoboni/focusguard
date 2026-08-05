package com.focusguard.ai

/**
 * Abstraction over the on-device text classifier so [OnDeviceClassifier] can use
 * local AI scoring without depending on any Android/TensorFlow types. This keeps
 * the classifier pure-JVM and unit-testable, while the real TensorFlow Lite
 * implementation lives in [LocalTfliteProductivityClassifier].
 */
interface ProductivityClassifier {
    fun classify(text: String): ClassificationResult?
    fun isReady(): Boolean
}

/** Outcome of a local AI classification run. */
data class ClassificationResult(
    val label: String,          // "productive" or "slop"
    val slopScore: Float,
    val productiveScore: Float
)
