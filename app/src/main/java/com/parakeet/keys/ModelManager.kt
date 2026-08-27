package com.parakeet.keys

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal object ModelManager {
    enum class Version(
        val id: String,
        val displayName: String,
        val languageDescription: String,
        val directoryName: String,
    ) {
        V3("v3", "Parakeet v3", "25 European languages, automatic detection", "parakeet-v3-int8"),
        V2("v2", "Parakeet v2", "English only", "parakeet-v2-int8"),
    }

    private data class ModelFile(
        val name: String,
        val size: Long,
        val sha256: String,
    )

    private data class ModelSpec(
        val repository: String,
        val files: List<ModelFile>,
    ) {
        val totalBytes: Long = files.sumOf { it.size }
    }

    private val specs = mapOf(
        Version.V3 to ModelSpec(
            repository = "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main",
            files = listOf(
                ModelFile(
                    "encoder.int8.onnx",
                    652_184_281,
                    "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
                ),
                ModelFile(
                    "decoder.int8.onnx",
                    11_845_275,
                    "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
                ),
                ModelFile(
                    "joiner.int8.onnx",
                    6_355_277,
                    "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3",
                ),
                ModelFile(
                    "tokens.txt",
                    93_939,
                    "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
                ),
            ),
        ),
        Version.V2 to ModelSpec(
            repository = "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main",
            files = listOf(
                ModelFile(
                    "encoder.int8.onnx",
                    652_184_296,
                    "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab",
                ),
                ModelFile(
                    "decoder.int8.onnx",
                    7_257_753,
                    "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e",
                ),
                ModelFile(
                    "joiner.int8.onnx",
                    1_739_080,
                    "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2",
                ),
                ModelFile(
                    "tokens.txt",
                    9_384,
                    "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d",
                ),
            ),
        ),
    )

    private val downloading = AtomicBoolean(false)
    @Volatile
    private var downloadState: DownloadState? = null

    data class DownloadState(
        val version: Version,
        val downloaded: Long,
        val total: Long,
        val currentFile: String,
    )

    fun currentDownload(): DownloadState? = downloadState

    fun selectedVersion(context: Context): Version {
        val id = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(SELECTED_VERSION_KEY, Version.V3.id)
        return Version.entries.firstOrNull { it.id == id } ?: Version.V3
    }

    fun selectVersion(context: Context, version: Version) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_VERSION_KEY, version.id)
            .apply()
    }

    fun totalBytes(version: Version): Long = spec(version).totalBytes

    fun directory(context: Context, version: Version = selectedVersion(context)): File =
        File(context.filesDir, "models/${version.directoryName}")

    fun isInstalled(context: Context, version: Version = selectedVersion(context)): Boolean {
        val modelSpec = spec(version)
        val directory = directory(context, version)
        val marker = File(directory, ".verified")
        return marker.isFile &&
            modelSpec.files.all { File(directory, it.name).length() == it.size } &&
            runCatching {
                marker.readText() == verificationManifest(directory, modelSpec.files)
            }.getOrDefault(false)
    }

    fun download(
        context: Context,
        version: Version = selectedVersion(context),
        onProgress: (downloaded: Long, total: Long, currentFile: String) -> Unit,
        onComplete: (error: Throwable?) -> Unit,
    ) {
        if (!downloading.compareAndSet(false, true)) {
            onComplete(IllegalStateException("A model download is already running"))
            return
        }
        val modelSpec = spec(version)
        downloadState = DownloadState(version, 0, modelSpec.totalBytes, "")

        Thread({
            var failure: Throwable? = null
            try {
                val directory = directory(context, version).apply { mkdirs() }
                File(directory, ".verified").delete()
                val reportProgress = { downloaded: Long, total: Long, currentFile: String ->
                    downloadState = DownloadState(version, downloaded, total, currentFile)
                    onProgress(downloaded, total, currentFile)
                }

                for (modelFile in modelSpec.files) {
                    val target = File(directory, modelFile.name)
                    if (target.length() == modelFile.size && sha256(target) == modelFile.sha256) {
                        reportProgress(
                            completedBytes(directory, modelSpec.files),
                            modelSpec.totalBytes,
                            modelFile.name,
                        )
                        continue
                    }
                    if (target.exists()) target.delete()
                    downloadFile(directory, modelSpec, modelFile, reportProgress)
                    check(sha256(target) == modelFile.sha256) {
                        "Checksum failed for ${modelFile.name}"
                    }
                }

                File(directory, ".verified").writeText(
                    verificationManifest(directory, modelSpec.files),
                )
            } catch (error: Throwable) {
                failure = error
            } finally {
                downloading.set(false)
                downloadState = null
                onComplete(failure)
            }
        }, "parakeet-model-download").start()
    }

    private fun downloadFile(
        directory: File,
        modelSpec: ModelSpec,
        modelFile: ModelFile,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val target = File(directory, modelFile.name)
        val partial = File(directory, "${modelFile.name}.part")
        var offset = partial.length().coerceAtMost(modelFile.size)
        if (partial.length() > modelFile.size) {
            partial.delete()
            offset = 0
        }
        if (partial.length() == modelFile.size) {
            check(partial.renameTo(target)) { "Could not install ${modelFile.name}" }
            onProgress(completedBytes(directory, modelSpec.files), modelSpec.totalBytes, modelFile.name)
            return
        }

        val connection = (URL("${modelSpec.repository}/${modelFile.name}?download=true").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ParakeetKeys/0.1")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            val append = offset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) offset = 0
            check(responseCode in 200..299) {
                "Download failed ($responseCode) for ${modelFile.name}"
            }

            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    var fileBytes = offset
                    var lastUpdate = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        fileBytes += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 250) {
                            onProgress(
                                completedBytes(directory, modelSpec.files) + fileBytes,
                                modelSpec.totalBytes,
                                modelFile.name,
                            )
                            lastUpdate = now
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        check(partial.length() == modelFile.size) {
            "Incomplete ${modelFile.name}: ${partial.length()} of ${modelFile.size} bytes"
        }
        check(partial.renameTo(target)) { "Could not install ${modelFile.name}" }
        onProgress(completedBytes(directory, modelSpec.files), modelSpec.totalBytes, modelFile.name)
    }

    private fun completedBytes(directory: File, files: List<ModelFile>): Long = files.sumOf { modelFile ->
        File(directory, modelFile.name).takeIf { it.length() == modelFile.size }?.length() ?: 0
    }

    private fun verificationManifest(directory: File, files: List<ModelFile>): String = files.joinToString(
        separator = "\n",
        postfix = "\n",
    ) { modelFile ->
        val file = File(directory, modelFile.name)
        "${modelFile.name}|${modelFile.size}|${modelFile.sha256}|${file.lastModified()}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 256)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun spec(version: Version): ModelSpec = checkNotNull(specs[version])

    private const val PREFERENCES_NAME = "speech_model"
    private const val SELECTED_VERSION_KEY = "selected_version"
}
