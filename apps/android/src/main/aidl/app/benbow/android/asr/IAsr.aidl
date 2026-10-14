package app.benbow.android.asr;

/**
 * The speech recognizer in its sandbox (AsrService, an isolated process): model files and audio come in as
 * descriptors, only text goes out. It has no permissions, no network and no access to the app's files.
 */
interface IAsr {
    /** Loads the model; null when ready, else what went wrong. */
    String load(in ParcelFileDescriptor encoder, in ParcelFileDescriptor decoder, in ParcelFileDescriptor joiner,
            in ParcelFileDescriptor tokens, in ParcelFileDescriptor vad);

    /** Reads 16 kHz mono float32 (little-endian) samples from [pcm] until it closes; returns the text. */
    String transcribe(in ParcelFileDescriptor pcm);

    /** Tries to reach the internet from the sandbox and says what happened (the privacy check). */
    String probeNetwork();
}
