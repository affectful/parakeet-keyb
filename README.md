# Parakeet Keys

An Android input method with command keys and local speech-to-text. It intentionally has no alphabetic key layer.

## Keyboard

- Ctrl, Alt, Shift, and Meta
- Escape, Tab, Page Up, and Page Down
- Arrow keys, Backspace, Delete, Period, Question Mark, Space, and Enter
- Push-to-talk dictation with NVIDIA Parakeet TDT 0.6B v2 or v3

Tap a modifier to arm it for one key. Long-press a modifier to lock it; locked keys show `*`. Tap it again to release it.

In landscape, use the `Drag` handle to move the compact keyboard horizontally. Its position is remembered.

## Build

Requirements: JDK 17 or newer and Android SDK 36.

```bash
./scripts/bootstrap.sh
./gradlew assembleDebug
```

The bootstrap script downloads the sherpa-onnx 1.13.6 Android AAR and verifies its SHA-256 checksum. Speech models are downloaded after installation and are not placed in the APK.

## Install

1. Install `app/build/outputs/apk/debug/app-debug.apk` on an ARM64 Android phone.
2. Open Parakeet Keys.
3. Grant microphone access.
4. Choose and download a speech model: Parakeet v3 multilingual INT8 (about 670 MB) or Parakeet v2 English-only INT8 (about 661 MB).
5. Open keyboard settings and enable Parakeet Keys.
6. Choose Parakeet Keys from the system input-method picker.

The first dictation loads the model and can take several seconds. Later dictation reuses the loaded recognizer. Recording is capped at 30 seconds; transcription is committed only if the original text field is still active.

Dictation is joined to the text before the cursor: sentence starts preserve capitalization, unfinished text ending in whitespace continues with a lowercase initial word, and unpunctuated text without trailing whitespace receives a period before the new transcription.

Parakeet v3 automatically detects and transcribes its 25 supported European languages; there is no language setting to switch. The supported set is Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, Slovenian, Spanish, Swedish, and Ukrainian. Parakeet v2 is English-only. Installed versions are kept separately, so switching back does not require another download.

## Runtime choice

The app currently uses the sherpa-onnx CPU backend with four threads. This is deliberate:

- The available sherpa-onnx Parakeet v3 export is an offline INT8 ONNX transducer.
- Qualcomm HTP requires fixed input shapes and a QNN context compiled for the exact SoC generation.
- Published sherpa-onnx QNN Parakeet artifacts currently target another Parakeet CTC checkpoint, not TDT 0.6B v3.

The speech runtime is isolated in `ParakeetRecognizer.kt`, so a compatible v3 QNN model can be added without changing the IME, recorder, or text insertion behavior. Benchmark CPU and QNN on the target device before making NPU the default; fixed-shape padding can erase the NPU advantage for short dictation.

## Models and licenses

- [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), CC BY 4.0
- [sherpa-onnx Parakeet v3 INT8 export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache 2.0
