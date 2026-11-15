package app.ankermonitor.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

/** 同じ Preview を iOS (Kotlin/Native + Skia) で描いて画像にする (./gradlew :sharedUi:recordRoborazziIosSimulatorArm64) */
class PreviewScreenshotTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun previews() {
        screenshotPreviews.forEach { (name, content) ->
            // 既定は 1024x768 等倍なので、iPhone 15 相当 (393x852pt, @3x) で描く
            runSkikoComposeUiTest(size = Size(393f * 3, 852f * 3), density = Density(3f)) {
                setContent { content() }
                onRoot().captureRoboImage(this, filePath = "$name.png")
            }
        }
    }
}
