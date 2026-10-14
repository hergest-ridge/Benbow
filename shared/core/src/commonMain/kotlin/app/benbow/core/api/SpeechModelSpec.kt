package app.benbow.core.api

import kotlinx.serialization.Serializable

/**
 * The speech-recognition model, pinned (7.6, ADR-025): GigaAM v3 with punctuation, as sherpa-onnx packages it (MIT),
 * and Silero VAD to cut long recordings into phrases. The server fetches these from the official sherpa-onnx GitHub
 * releases only when the owner asks, and phones take them only from the server; both check every file against
 * these hashes, so a different file is never used, whoever offers it.
 */
object SpeechModelSpec {
    const val ID = "gigaam-v3-punct-2025-12-16"

    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    /** What the server downloads. The archive is unpacked; [Source.file] is taken as it is. */
    val archive = Source(
        url = RELEASES + "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2",
        size = 170_197_019,
        sha256 = "f9620a0099019c6afcee26525ef9ed3297fa50dd5691c1902af0c948fc1a470b",
    )
    val vad = Source(
        url = RELEASES + "silero_vad.onnx",
        size = 643_854,
        sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        file = "silero_vad.onnx",
    )

    /** The files a phone needs (and the license that comes with them). */
    val files = listOf(
        SpeechModelFile("encoder.int8.onnx", 224_570_820, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
        SpeechModelFile("decoder.onnx", 4_600_132, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
        SpeechModelFile("joiner.onnx", 2_712_896, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        SpeechModelFile("tokens.txt", 13_354, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7"),
        SpeechModelFile("LICENSE", 1_070, "f00de6715714c7a63d08639cdbfaa40224eefc407302614bd19f1a8b98c875aa"),
        SpeechModelFile("silero_vad.onnx", vad.size, vad.sha256),
    )

    val totalSize get() = files.sumOf { it.size }

    class Source(val url: String, val size: Long, val sha256: String, val file: String? = null)
}

@Serializable
data class SpeechModelFile(val name: String, val size: Long, val sha256: String)

/** `GET /api/v1/models/speech`: whether the server has the model, and which. */
@Serializable
data class SpeechModelInfo(val id: String, val ready: Boolean, val files: List<SpeechModelFile>)
