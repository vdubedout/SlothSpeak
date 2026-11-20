package com.slothspeak.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * On-device streaming STT model used by the FUTO provider.
 *
 * FUTO's own asr4all streaming models are English-only today. For the
 * English + French streaming path FUTO recommends NVIDIA's Nemotron 3.5
 * multilingual model, exported for sherpa-onnx. It runs entirely on the
 * phone (no API key, no network at transcription time).
 *
 * Files come from the official sherpa-onnx model export. The total download
 * is ~650 MB, so it is fetched on demand from Settings. An existing
 * sherpa-onnx model folder can be imported instead of downloading.
 */
object FutoSttModel {
    const val ID = "nemotron-3.5-asr-streaming-0.6b-560ms-int8"
    const val DISPLAY_NAME = "Nemotron 3.5 streaming"
    const val DETAIL = "560 ms chunks · English + French · 100% on-device"

    const val REPO =
        "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11"
    const val DOWNLOAD_BASE = "https://huggingface.co/$REPO/resolve/main/"

    const val ENCODER_FILE = "encoder.int8.onnx"
    const val DECODER_FILE = "decoder.int8.onnx"
    const val JOINER_FILE = "joiner.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    const val IMPORT_HINT =
        "Choose the folder that contains encoder.int8.onnx (or encoder.onnx), " +
            "decoder.int8.onnx, joiner.int8.onnx and tokens.txt."

    data class ModelFile(
        val name: String,
        val sizeBytes: Long,
        // Imported files may be fp32 or a different quantization, so only
        // require a sane minimum instead of the exact download size.
        val minBytes: Long
    )

    // Exact sizes reported by Hugging Face. Used to detect truncated downloads.
    val files = listOf(
        ModelFile(ENCODER_FILE, 657_601_403L, 10L * 1024 * 1024),
        ModelFile(DECODER_FILE, 14_978_075L, 1L * 1024 * 1024),
        ModelFile(JOINER_FILE, 9_504_438L, 1L * 1024 * 1024),
        ModelFile(TOKENS_FILE, 131_440L, 1_000L)
    )

    val totalBytes: Long = files.sumOf { it.sizeBytes }

    val totalMegabytes: Long get() = totalBytes / (1024L * 1024L)

    /**
     * Pick the four expected model files out of a folder listing. Prefers the
     * int8 names used by the downloader, then fp32 / renamed exports.
     * Returns null if any role is missing.
     */
    fun selectModelFiles(names: List<String>): Selection? {
        val used = mutableSetOf<String>()

        fun pick(
            token: String,
            ext: String,
            preferred: List<String>
        ): String? {
            for (name in preferred) {
                val match = names.firstOrNull {
                    it.equals(name, ignoreCase = true) && it !in used
                }
                if (match != null) {
                    used += match
                    return match
                }
            }
            val fallback = names.firstOrNull {
                it !in used &&
                    it.endsWith(ext, ignoreCase = true) &&
                    it.contains(token, ignoreCase = true)
            }
            if (fallback != null) used += fallback
            return fallback
        }

        val encoder = pick(
            token = "encoder",
            ext = ".onnx",
            preferred = listOf(ENCODER_FILE, "encoder.onnx")
        ) ?: return null
        val decoder = pick(
            token = "decoder",
            ext = ".onnx",
            preferred = listOf(DECODER_FILE, "decoder.onnx")
        ) ?: return null
        val joiner = pick(
            token = "joiner",
            ext = ".onnx",
            preferred = listOf(JOINER_FILE, "joiner.onnx")
        ) ?: return null
        val tokens = pick(
            token = "token",
            ext = ".txt",
            preferred = listOf(TOKENS_FILE)
        ) ?: return null

        return Selection(encoder, decoder, joiner, tokens)
    }

    data class Selection(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String
    )
}

/**
 * Language selection for the Nemotron streaming model. The value is passed
 * per stream (`setOption("language", …)`), so every utterance can use a
 * different language without reloading the model.
 */
object FutoSttLanguage {
    const val AUTO = "auto"
    const val ENGLISH = "en"
    const val FRENCH = "fr"

    val options: List<Pair<String, String>> = listOf(
        AUTO to "Auto",
        ENGLISH to "English",
        FRENCH to "Français"
    )

    fun label(id: String): String =
        options.firstOrNull { it.first == id }?.second ?: "Auto"
}

/**
 * Downloads, verifies and deletes the on-device model.
 *
 * Files are written to `<filesDir>/models/stt/<id>/`. Partial downloads keep
 * a `.part` file and resume with a Range request, which matters for a 650 MB
 * model on mobile networks. Imported folders are copied into the same
 * directory using canonical file names.
 */
class FutoSttModelManager(private val context: Context) {

    data class Status(
        val installed: Boolean,
        val bytesOnDisk: Long,
        val totalBytes: Long
    ) {
        val progress: Float
            get() = if (totalBytes <= 0) 0f
            else (bytesOnDisk.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    private val mutex = Mutex()

    val modelDir: File
        get() = File(context.filesDir, "models/stt/${FutoSttModel.ID}")

    fun file(name: String): File = File(modelDir, name)

    fun status(): Status {
        var bytes = 0L
        var complete = 0
        for (spec in FutoSttModel.files) {
            val finalFile = File(modelDir, spec.name)
            val partFile = File(modelDir, "${spec.name}.part")
            val finalBytes = finalFile.length()
            val usable = finalFile.isFile && finalBytes >= spec.minBytes
            if (usable) complete++
            bytes += if (usable) finalBytes else maxOf(finalBytes, partFile.length())
        }
        return Status(
            installed = complete == FutoSttModel.files.size,
            bytesOnDisk = bytes.coerceAtLeast(0L),
            totalBytes = FutoSttModel.totalBytes
        )
    }

    fun isInstalled(): Boolean = status().installed

    /**
     * Import the four model files from a folder chosen with the system file
     * picker. Files are copied into app storage (the native runtime needs
     * filesystem paths, not content:// URIs) and renamed to the canonical
     * names used by the downloader.
     */
    suspend fun importFromFolder(
        treeUri: Uri,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Could not open the selected folder.")
        val documents = root.listFiles().filter { it.isFile }
        val byName = documents.associateBy { it.name ?: "" }
        val selection = FutoSttModel.selectModelFiles(byName.keys.toList())
            ?: throw IOException(missingFilesMessage(byName.keys.toList()))

        val sources = listOf(
            FutoSttModel.ENCODER_FILE to sourceOf(byName[selection.encoder]!!),
            FutoSttModel.DECODER_FILE to sourceOf(byName[selection.decoder]!!),
            FutoSttModel.JOINER_FILE to sourceOf(byName[selection.joiner]!!),
            FutoSttModel.TOKENS_FILE to sourceOf(byName[selection.tokens]!!)
        )
        importSources(sources, onProgress)
    }

    /**
     * Import from a filesystem directory. Used by tests and by callers that
     * already have a readable path.
     */
    suspend fun importFromDirectory(
        sourceDir: File,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val byName = (sourceDir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .associateBy { it.name }
        val selection = FutoSttModel.selectModelFiles(byName.keys.toList())
            ?: throw IOException(missingFilesMessage(byName.keys.toList()))

        val sources = listOf(
            FutoSttModel.ENCODER_FILE to sourceOf(byName[selection.encoder]!!),
            FutoSttModel.DECODER_FILE to sourceOf(byName[selection.decoder]!!),
            FutoSttModel.JOINER_FILE to sourceOf(byName[selection.joiner]!!),
            FutoSttModel.TOKENS_FILE to sourceOf(byName[selection.tokens]!!)
        )
        importSources(sources, onProgress)
    }

    private class ImportSource(
        val length: Long,
        val open: () -> InputStream?
    )

    private fun sourceOf(file: File) = ImportSource(file.length()) { file.inputStream() }

    private fun sourceOf(document: DocumentFile) =
        ImportSource(document.length()) { context.contentResolver.openInputStream(document.uri) }

    private suspend fun importSources(
        sources: List<Pair<String, ImportSource>>,
        onProgress: (Long, Long) -> Unit
    ) = mutex.withLock {
        modelDir.mkdirs()
        if (!modelDir.isDirectory) {
            throw IOException("Could not create model directory: ${modelDir.absolutePath}")
        }

        val total = sources.sumOf { it.second.length }.coerceAtLeast(1L)
        val headroom = 32L * 1024L * 1024L
        if (modelDir.usableSpace < total + headroom) {
            throw IOException(
                "Not enough free storage to import the model. Need about " +
                    "${(total + headroom) / (1024L * 1024L)} MB."
            )
        }

        var done = 0L
        for ((destName, source) in sources) {
            val dest = File(modelDir, destName)
            val part = File(modelDir, "$destName.part")
            part.delete()

            source.open().use { input ->
                if (input == null) throw IOException("Could not read $destName.")
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    output.flush()
                }
            }

            if (part.length() <= 0L) {
                part.delete()
                throw IOException("$destName is empty.")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.delete()
                throw IOException("Could not finalize $destName.")
            }
        }

        if (!status().installed) {
            throw IOException(missingFilesMessage())
        }
    }

    private fun missingFilesMessage(names: List<String> = emptyList()): String {
        val lower = names.map { it.lowercase() }
        if (lower.any { it.endsWith(".gguf") }) {
            return "That looks like a FUTO .gguf model. FUTO Voice Input uses GGUF " +
                "weights, which sherpa-onnx cannot load. Choose the ONNX export " +
                "(encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt)."
        }
        if (lower.any { it.endsWith(".bin") }) {
            return "That looks like FUTO Keyboard's .bin model, which uses a private " +
                "FUTO format. Choose the sherpa-onnx ONNX export instead."
        }
        return "The selected folder does not contain a complete model. Expected: " +
            FutoSttModel.ENCODER_FILE + ", " + FutoSttModel.DECODER_FILE + ", " +
            FutoSttModel.JOINER_FILE + " and " + FutoSttModel.TOKENS_FILE + "."
    }

    /**
     * Download any missing files. [onProgress] receives (bytes done, total bytes)
     * and may be invoked from a background thread.
     */
    suspend fun download(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelDir.mkdirs()
            if (!modelDir.isDirectory) {
                throw IOException("Could not create model directory: ${modelDir.absolutePath}")
            }

            val status = status()
            if (status.installed) {
                onProgress(FutoSttModel.totalBytes, FutoSttModel.totalBytes)
                return@withLock
            }

            val remaining = FutoSttModel.totalBytes - status.bytesOnDisk
            val headroom = 64L * 1024L * 1024L
            if (modelDir.usableSpace < remaining + headroom) {
                throw IOException(
                    "Not enough free storage. Need about " +
                        "${(remaining + headroom) / (1024L * 1024L)} MB."
                )
            }

            var completedBytes = 0L
            for (spec in FutoSttModel.files) {
                val dest = File(modelDir, spec.name)
                if (dest.length() == spec.sizeBytes) {
                    completedBytes += spec.sizeBytes
                    onProgress(completedBytes, FutoSttModel.totalBytes)
                    continue
                }
                if (dest.exists()) dest.delete()

                val part = File(modelDir, "${spec.name}.part")
                downloadWithRetry(spec, part) { fileBytes ->
                    onProgress(
                        (completedBytes + fileBytes).coerceAtMost(FutoSttModel.totalBytes),
                        FutoSttModel.totalBytes
                    )
                }

                if (part.length() != spec.sizeBytes) {
                    part.delete()
                    throw IOException(
                        "Downloaded ${spec.name} is incomplete " +
                            "(${part.length()} of ${spec.sizeBytes} bytes)."
                    )
                }
                if (!part.renameTo(dest)) {
                    part.delete()
                    throw IOException("Could not finalize ${spec.name}.")
                }
                completedBytes += spec.sizeBytes
                onProgress(completedBytes, FutoSttModel.totalBytes)
            }
        }
    }

    private suspend fun downloadWithRetry(
        spec: FutoSttModel.ModelFile,
        part: File,
        onBytes: (Long) -> Unit
    ) {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                downloadFile(spec, part, onBytes)
                return
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Download attempt ${attempt + 1} failed for ${spec.name}", e)
                if (attempt < MAX_ATTEMPTS - 1) delay(1_500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Download failed for ${spec.name}")
    }

    private suspend fun downloadFile(
        spec: FutoSttModel.ModelFile,
        part: File,
        onBytes: (Long) -> Unit
    ) {
        var start = if (part.exists()) part.length() else 0L
        if (start > spec.sizeBytes) {
            part.delete()
            start = 0L
        }
        if (start == spec.sizeBytes) {
            onBytes(start)
            return
        }

        val requestBuilder = Request.Builder()
            .url(FutoSttModel.DOWNLOAD_BASE + spec.name)
        if (start > 0) {
            requestBuilder.header("Range", "bytes=$start-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed (${response.code}) for ${spec.name}")
            }
            val body = response.body ?: throw IOException("Empty response for ${spec.name}")

            // If the server ignored Range, restart from zero.
            val append = response.code == 206 && start > 0
            if (!append) {
                part.delete()
                start = 0L
            }

            var written = start
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
                output.flush()
            }
        }
    }

    fun delete() {
        modelDir.deleteRecursively()
    }
    private companion object {
        private const val TAG = "FutoSttModel"
        private const val MAX_ATTEMPTS = 3
        private const val BUFFER_SIZE = 64 * 1024

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .build()
        }
    }
}
