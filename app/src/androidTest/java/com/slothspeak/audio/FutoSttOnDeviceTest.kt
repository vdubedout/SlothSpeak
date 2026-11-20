package com.slothspeak.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slothspeak.data.FutoSttLanguage
import com.slothspeak.data.FutoSttModelManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device smoke test for the FUTO/Nemotron streaming model.
 *
 * This downloads the real ~650 MB model on first run (into the app's files
 * directory), then transcribes the English and French samples shipped with
 * the model. Run with:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FutoSttOnDeviceTest {

    @Test
    fun transcribesEnglishAndFrench() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FutoSttModelManager(context)

        if (!manager.isInstalled()) {
            println("FUTO STT: downloading model (${manager.status().totalBytes / (1024 * 1024)} MB)")
            var lastPercent = -1
            withTimeout(TIMEOUT_MS) {
                manager.download { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent >= lastPercent + 10) {
                        lastPercent = percent
                        println("FUTO STT: download $percent%")
                    }
                }
            }
        }
        assertTrue("model files missing after download", manager.isInstalled())

        val engine = FutoSttEngine.get(context)
        try {
            val english = transcribe(engine, copyAsset(context, "en.wav"), FutoSttLanguage.ENGLISH)
            println("FUTO STT EN: $english")
            assertTrue("empty English transcript", english.isNotBlank())

            val french = transcribe(engine, copyAsset(context, "fr.wav"), FutoSttLanguage.FRENCH)
            println("FUTO STT FR: $french")
            assertTrue("empty French transcript", french.isNotBlank())
        } finally {
            FutoSttEngine.releaseShared()
        }
    }

    private suspend fun transcribe(
        engine: FutoSttEngine,
        wav: File,
        language: String
    ): String = withTimeout(TRANSCRIBE_TIMEOUT_MS) {
        engine.transcribe(wav, language)
    }

    private fun copyAsset(context: Context, name: String): File {
        val output = File(context.cacheDir, name)
        if (!output.exists() || output.length() == 0L) {
            val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
            testAssets.open(name).use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return output
    }

    private companion object {
        private const val TIMEOUT_MS = 30 * 60 * 1000L
        private const val TRANSCRIBE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
