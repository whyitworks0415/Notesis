package com.notesis

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The agent connection's cards as someone sees them: the question, the panel, and the answers they give. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h1000dp-xhdpi")
class AgentUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        context.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().clear().commit()
        ChangeLog.clear()
        AgentPanelState.open = false
    }
    @After fun clean() {
        AgentApprovals.waiting().forEach { AgentApprovals.decide(it, false) }
        AgentPanelState.open = false
        ChangeLog.clear()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalMotionAllowed provides false) { ProvideSkin(SkinSettings()) { content() } } }
        }
    }

    private fun ask(lines: List<ChangeLine>): Pair<Thread, Array<Boolean?>> {
        val result = arrayOfNulls<Boolean>(1)
        val t = Thread { result[0] = AgentApprovals.request("Claude Code", lines, waitSeconds = 20) }
        t.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (AgentApprovals.waiting().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        return t to result
    }

    private val lines = listOf(
        ChangeLine("Add a page to “{0}”", listOf("Maths"), true),
        ChangeLine("Rename “{0}” to “{1}”", listOf("Maths", "Algebra"), true),
    )

    @Test fun aChangeWaitsForAnAllow() {
        show { ChangeApprovalHost() }
        val (thread, result) = ask(lines)
        compose.waitForIdle()
        compose.onNodeWithText("Allow this change?").assertIsDisplayed()
        compose.onNodeWithText("• Add a page to “Maths”").assertIsDisplayed()
        compose.onNodeWithText("• Rename “Maths” to “Algebra”").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agent-approval.png")
        compose.onNodeWithText("Allow").performClick()
        // The card folds away, then the answer is given; the screen has to run for that, not wait on the thread.
        compose.waitForIdle()
        thread.join(5_000)
        assertEquals(true, result[0])
        compose.waitForIdle()
        assertTrue(AgentApprovals.waiting().isEmpty())
    }

    @Test fun aChangeRefusedIsToldNo() {
        show { ChangeApprovalHost() }
        val (thread, result) = ask(lines)
        compose.waitForIdle()
        compose.onNodeWithText("Don't allow").performClick()
        compose.waitForIdle()
        thread.join(5_000)
        assertEquals(false, result[0])
    }

    @Test fun theSetupPanelSwitchesOnAndShowsAMaskedToken() {
        show { AgentPanelHost() }
        compose.runOnIdle { AgentPanelState.open = true }
        compose.waitForIdle()
        compose.onNodeWithText("Agent connection").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Copy token"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agent-off.png")
        // Pressing the switch turns the connection on and starts its service.
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        compose.waitForIdle()
        assertTrue(AgentSettings.enabled(context))
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals("com.notesis.AgentService", started.component?.className)
        compose.onNodeWithText("Copy token").assertIsDisplayed()
        compose.onNodeWithText("Ask first").assertIsDisplayed()
        // Only the beginning of the token is shown until asked.
        val token = AgentSettings.token(context)
        assertEquals(0, compose.onAllNodesWithTextCount(token))
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText(token).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agent-on.png")
        // A new token replaces the old one only after a second press.
        compose.onNodeWithText("New token").performClick()
        assertEquals(token, AgentSettings.token(context))
        compose.onNodeWithText("Stop what is connected").performClick()
        compose.waitForIdle()
        assertFalse(token == AgentSettings.token(context))
        // Making changes at once is saved.
        compose.onNodeWithText("Make them at once").performClick()
        assertEquals(AgentPolicy.AUTO, AgentSettings.policy(context))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text, substring = false).fetchSemanticsNodes().size
}
