package app.ankermonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ankermonitor.ui.screenshotPreviews
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** sharedUi の Preview を Android で描いて画像にする (./gradlew :androidApp:recordRoborazziDebug) */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Preview は AnkerApplication (WorkManager の登録) を必要としないので、素の Application で起動する
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PreviewScreenshotTest {
    @Test
    fun previews() {
        screenshotPreviews.forEach { (name, content) ->
            captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/$name.png") { content() }
        }
    }
}
