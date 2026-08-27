package com.parakeet.keys

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

internal class ParakeetRecognizer(
    context: Context,
    val version: ModelManager.Version = ModelManager.selectedVersion(context),
) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        check(ModelManager.isInstalled(context, version)) { "${version.displayName} model is not installed" }
        val directory = ModelManager.directory(context, version)
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = directory.resolve("encoder.int8.onnx").absolutePath,
                decoder = directory.resolve("decoder.int8.onnx").absolutePath,
                joiner = directory.resolve("joiner.int8.onnx").absolutePath,
            ),
            tokens = directory.resolve("tokens.txt").absolutePath,
            numThreads = 4,
            provider = "cpu",
            modelType = "nemo_transducer",
        )
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            ),
        )
    }

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
