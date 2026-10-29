package com.personal.bengaliagent

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.bengaliagent.ui.main.MainActivity
import com.personal.bengaliagent.ui.settings.ProviderSettingsActivity
import com.personal.bengaliagent.ui.setup.SetupWizardActivity
import org.hamcrest.CoreMatchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test
    fun setupWizardCanFinishWithOptionalPermissionsSkipped() {
        ActivityScenario.launch(SetupWizardActivity::class.java).use {
            onView(withId(R.id.btnFinishSetup)).check(matches(isDisplayed()))
            onView(withId(R.id.btnFinishSetup)).perform(click())
        }
    }

    @Test
    fun aiConnectionScreenDisplaysEnglishLabelsAndConnectButton() {
        ActivityScenario.launch(ProviderSettingsActivity::class.java).use {
            onView(withId(R.id.tvAiConnectionTitle)).check(matches(withText("AI Connection")))
            onView(withId(R.id.tvProviderLabel)).check(matches(withText("Google Gemini")))
            onView(withId(R.id.btnConnectGemini)).check(matches(withText("Connect Gemini")))
        }
    }

    @Test
    fun mainScreenDisplaysEnglishControlsAndStopButtonImmediatelyCancelsTasks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tvAgentState)).check(matches(isDisplayed()))
            onView(withId(R.id.btnStopTask)).check(matches(isDisplayed()))
            onView(withId(R.id.btnPauseResume)).perform(click())
            onView(withId(R.id.tvAgentState)).check(matches(withText(containsString("Paused"))))
            onView(withId(R.id.btnStopTask)).perform(click())
            onView(withId(R.id.tvAgentState)).check(matches(withText(containsString("Ready"))))
        }
    }
}
