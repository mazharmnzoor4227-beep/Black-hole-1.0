package com.blackhole.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live test. A local fixture is not evidence that a social extractor works. */
@RunWith(AndroidJUnit4::class)
class SocialDownloadTest {
    @Test fun downloadsLivePublicVideo() {
        val link = InstrumentationRegistry.getArguments().getString("liveUrl")
        assumeTrue("Live link not supplied", !link.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        runBlocking(Dispatchers.IO) {
            try {
                val result = OnDeviceExtractor.download(context, link!!) { _, _ -> }
                assertTrue("Empty download", result.file.length() > 0)
                val quality = MediaFiles.quality(result.file)
                val uri = MediaFiles.publish(context, result.file, result.metadata.title)
                try {
                    context.contentResolver.openInputStream(uri).use {
                        assertTrue("Saved file cannot be read", it != null && it.read() >= 0)
                    }
                    File(evidence, "live-result.txt").writeText("PASS\n$quality\nbytes=${result.file.length()}")
                } finally { MediaFiles.delete(context, uri) }
            } catch (error: Exception) {
                val detail = (error as? UserFailure)?.diagnostic ?: error.message.orEmpty()
                File(evidence, "live-result.txt").writeText("FAIL\n${error.message}\n$detail")
                throw AssertionError("Live download failed: ${error.message}\n$detail", error)
            } finally {
                OnDeviceExtractor.cleanup(context)
                Transfer.update(TransferState())
            }
        }
    }
}
