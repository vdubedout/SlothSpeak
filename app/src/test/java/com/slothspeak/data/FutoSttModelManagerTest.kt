package com.slothspeak.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric test for importing an already-downloaded model folder.
 * Uses dummy bytes — this verifies copying/renaming/validation, not inference
 * (inference is covered by the on-device FutoSttOnDeviceTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FutoSttModelManagerTest {

    @Test
    fun importFromDirectory_renamesFilesToCanonicalNames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FutoSttModelManager(context)
        manager.delete()

        val source = File(context.cacheDir, "futo-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            writeDummy(source, "encoder.onnx", 10L * 1024 * 1024 + 1)
            writeDummy(source, "decoder.onnx", 1L * 1024 * 1024 + 1)
            writeDummy(source, "joiner.onnx", 1L * 1024 * 1024 + 1)
            writeDummy(source, "tokens.txt", 2_000)

            manager.importFromDirectory(source) { _, _ -> }

            assertTrue(manager.isInstalled())
            listOf(
                FutoSttModel.ENCODER_FILE,
                FutoSttModel.DECODER_FILE,
                FutoSttModel.JOINER_FILE,
                FutoSttModel.TOKENS_FILE
            ).forEach { name ->
                val file = File(manager.modelDir, name)
                assertTrue("$name missing", file.isFile)
                assertTrue("$name empty", file.length() > 0)
            }
            assertEquals(
                1L * 1024 * 1024 + 1,
                File(manager.modelDir, FutoSttModel.DECODER_FILE).length()
            )
        } finally {
            manager.delete()
            source.deleteRecursively()
        }
    }

    private fun writeDummy(dir: File, name: String, size: Int) {
        File(dir, name).outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val n = minOf(buffer.size, remaining)
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
    }

    private fun writeDummy(dir: File, name: String, size: Long) {
        writeDummy(dir, name, size.toInt())
    }
}
