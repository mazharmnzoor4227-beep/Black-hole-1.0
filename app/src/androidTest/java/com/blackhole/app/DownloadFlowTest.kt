package com.blackhole.app

import android.content.*
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadFlowTest {
    @After fun saveFailureEvidence() {
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val device=UiDevice.getInstance(i)
        device.takeScreenshot(File(dir,"last-screen.png"))
        device.dumpWindowHierarchy(File(dir,"window.xml"))
        File(dir,"transfer.txt").writeText(Transfer.state.value.toString())
    }
    @Test fun launcherAndRealDownload() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        if(Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assertEquals(28,context.applicationInfo.minSdkVersion)
        assertEquals(36,context.applicationInfo.targetSdkVersion)
        instrumentation.runOnMainSync { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "")) }
        val launcher=context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
        context.startActivity(launcher!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val hole=device.wait(Until.findObject(By.desc("Black hole. Tap to download copied video link")),10000)
        assertNotNull(hole)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        val directory=File(context.getExternalFilesDir(null),"evidence").apply { mkdirs() }
        val screenshot=File(directory,"home.png")
        device.takeScreenshot(screenshot)
        val bitmap=BitmapFactory.decodeFile(screenshot.absolutePath)
        assertEquals(Color.BLACK,bitmap.getPixel(bitmap.width/2,bitmap.height/8))
        assertEquals(Color.BLACK,bitmap.getPixel(2,bitmap.height/2))
        bitmap.recycle()
        hole.click()
        assertTrue(device.wait(Until.hasObject(By.text("COPY A VIDEO LINK FIRST")),5000))
        // HTTP is allowed only for this emulator fixture host in debug builds.
        context.startActivity(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"http://10.0.2.2:8765/fixture.mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        device.wait(Until.findObject(By.desc("Black hole. Tap to download copied video link")),5000).click()
        // First launch extracts the embedded Python/FFmpeg runtime before yt-dlp starts.
        assertTrue("Download failed: ${Transfer.state.value}", device.wait(Until.hasObject(By.text("DOWNLOAD COMPLETE")),180000))
        assertEquals(Phase.COMPLETE,Transfer.state.value.phase)
        assertEquals(100,Transfer.state.value.percent)
        val uri=android.net.Uri.parse(Transfer.state.value.uri)
        context.contentResolver.openInputStream(uri).use { assertNotNull(it); assertTrue(it!!.read() >= 0) }
        val rows=HistoryStore(context).use { it.list() }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.first().bytes > 0)
        assertTrue(rows.first().quality.contains("320×240"))
        val completedShot=File(directory,"complete.png")
        device.takeScreenshot(completedShot)
        val completedBitmap=BitmapFactory.decodeFile(completedShot.absolutePath)
        var brightPixels=0
        for(y in completedBitmap.height/3 until completedBitmap.height*2/3) {
            for(x in 0 until completedBitmap.width) if(Color.red(completedBitmap.getPixel(x,y)) > 100) brightPixels++
        }
        assertTrue("Black hole must remain visible after completion", brightPixels > 100)
        completedBitmap.recycle()
        device.findObject(By.text("HISTORY")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("320×240")),5000))
        device.takeScreenshot(File(directory,"history.png"))
        device.findObject(By.textContains("320×240")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Delete")),5000))
        device.findObject(By.text("Delete")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Delete")),5000))
        device.findObject(By.text("Delete")).click()
        assertTrue(device.wait(Until.hasObject(By.text("NO DOWNLOADS YET")),5000))
        assertTrue(HistoryStore(context).use { it.list() }.isEmpty())
        assertTrue(runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() }; false }.getOrDefault(true))
    }
}
