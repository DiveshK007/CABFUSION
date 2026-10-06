package com.cabfusion.app

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.cabfusion.app.data.ServiceLocator
import com.cabfusion.app.ui.LoginActivity
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end walk through the app on the demo backend: register, plan a trip, get matched,
 * follow the ride to completion, then accept a pooled ride in driver mode. A screenshot is saved
 * at every step to /data/local/tmp/cf (pulled by CI for the project report).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotFlowTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = "com.cabfusion.app"
    private var shot = 0

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("cf_local", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("cf_driver", Context.MODE_PRIVATE).edit().clear().commit()
        ServiceLocator.useDemoBackend()
        device.executeShellCommand("mkdir -p /data/local/tmp/cf")
        dismissSystemDialogs()
        // Turn the soft keyboard off so it never covers fields or buttons; text is entered through
        // accessibility (UiObject2.text) and does not need an IME.
        device.executeShellCommand("ime list -s").lines().filter { it.isNotBlank() }
            .forEach { device.executeShellCommand("ime disable ${it.trim()}") }
    }

    /** A slow emulator boot can leave an "isn't responding" dialog (e.g. for the launcher) on top of the app. */
    private fun dismissSystemDialogs() {
        repeat(3) {
            val wait = device.findObject(By.text("Wait")) ?: device.findObject(By.textContains("Close app"))
            if (wait == null) return
            wait.click(); Thread.sleep(800)
        }
    }

    /** Closes the soft keyboard if it is showing (pressing back would otherwise leave the screen). */
    private fun hideKeyboard() {
        val dump = device.executeShellCommand("dumpsys input_method")
        if (dump.contains("mInputShown=true") || dump.contains("isInputViewShown=true")) { device.pressBack(); Thread.sleep(500) }
    }

    private fun snap(name: String, settleMs: Long = 1200) {
        Thread.sleep(settleMs)
        shot++
        device.executeShellCommand("screencap -p /data/local/tmp/cf/%02d_%s.png".format(shot, name))
    }

    /** Finds a view by id; if it is below the fold, scrolls the screen up a few times to reach it. */
    private fun id(res: String, timeout: Long = 15_000): UiObject2 {
        device.wait(Until.findObject(By.res(pkg, res)), timeout)?.let { return it }
        val w = device.displayWidth / 2; val h = device.displayHeight
        repeat(5) {
            device.swipe(w, (h * 0.70).toInt(), w, (h * 0.35).toInt(), 15)
            device.wait(Until.findObject(By.res(pkg, res)), 1_500)?.let { return it }
        }
        error("view $res not found")
    }

    private fun text(t: String, timeout: Long = 20_000): UiObject2? = device.wait(Until.findObject(By.text(t)), timeout)
    private fun textStarts(t: String, timeout: Long = 20_000): UiObject2? =
        device.wait(Until.findObject(By.textStartsWith(t)), timeout)

    private fun type(res: String, value: String) { id(res).apply { click(); text = value } }

    @Test fun fullRideFlow() {
        ActivityScenario.launch(LoginActivity::class.java)
        Thread.sleep(1500); dismissSystemDialogs()
        id("btnLogin")
        snap("login")

        // invalid login shows validation
        type("etEmail", "divesh@"); id("btnLogin").click()
        snap("login_validation", 600)

        id("tvRegister").click()
        type("etName", "Divesh Kumar")
        type("etEmail", "divesh.cit@gmail.com")
        type("etPhone", "9876543210")
        type("etPassword", "cabfusion123")
        type("etConfirm", "cabfusion12")
        id("btnRegister").click()
        textStarts("Passwords do not match", 5_000)
        snap("register_mismatch", 600)
        type("etConfirm", "cabfusion123")
        hideKeyboard()
        snap("register")
        id("btnRegister").click()

        id("tvNearby")
        Thread.sleep(6000)                      // let demo riders load
        snap("dashboard")

        id("cardRoute").click()
        type("etPickup", "Koyambedu")
        text("Koyambedu, Chennai", 15_000)?.click()
        type("etDrop", "Guindy")
        snap("place_search", 2500)
        text("Guindy, Chennai", 15_000)?.click()
        hideKeyboard()
        device.wait(Until.findObject(By.res(pkg, "btnShared").enabled(true)), 30_000)
        snap("route_and_fare", 5000)

        id("btnShared").click()
        snap("searching", 1500)
        assertNotNull("no match found", textStarts("Confirm shared ride", 40_000))
        snap("match_found", 4000)
        val w = device.displayWidth / 2; val h = device.displayHeight
        device.swipe(w, (h * 0.85).toInt(), w, (h * 0.55).toInt(), 20)
        snap("match_details", 1000)
        device.swipe(w, (h * 0.55).toInt(), w, (h * 0.85).toInt(), 20)
        textStarts("Confirm shared ride")!!.click()

        snap("pooled", 1500)
        text("Driver assigned", 30_000); snap("driver_assigned", 1500)
        text("Driver is on the way", 30_000); snap("driver_arriving", 5000)
        text("Enjoy the ride", 60_000); snap("on_trip", 6000)
        assertNotNull("trip did not complete", text("You've arrived", 90_000))
        snap("completed", 2000)
        id("btnPrimary").click()

        id("bottomNav")
        device.findObject(By.text("My Rides"))?.click()
        snap("my_rides", 2500)
        device.pressBack()
        device.findObject(By.text("Profile"))?.click()
        snap("profile", 2000)

        id("btnSwitchRole").click()
        type("etVehicle", "TN 09 AB 4521 · Swift Dzire")
        hideKeyboard()
        id("swOnline").click()
        snap("driver_requests", 3000)
        textStarts("Accept ride")?.click()
        snap("driver_trip", 6000)
        id("btnTripAction").click()
        snap("driver_navigating", 6000)
    }
}
