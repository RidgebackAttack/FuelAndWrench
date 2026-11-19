package de.spritbuch.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startet die App wie ein Nutzer und zeichnet auf, welcher Code dabei läuft. Android übersetzt diesen
 * Code nach der Installation vorab, dadurch startet die App schneller und ruckelt weniger.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = "io.github.ridgebackattack.fuelandwrench",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // Einstellungen öffnen und zurück (Emulator ist englisch eingestellt)
        device.wait(Until.hasObject(By.desc("Settings")), 5_000)
        device.findObject(By.desc("Settings"))?.let {
            it.click()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }
}
