package net.typeblog.shelter.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.typeblog.shelter.R

/** The finite setup state machine, mirroring the original wizard steps. */
enum class Step {
    WELCOME,
    PERMISSIONS,
    COMPATIBILITY,
    READY,
    PROVISIONING,
    ACTION_REQUIRED,
    RECOVERY,
    FAILED;
    fun onBack(): Step = when (this) {
        Step.PERMISSIONS -> Step.WELCOME
        Step.COMPATIBILITY -> Step.PERMISSIONS
        Step.READY -> Step.COMPATIBILITY
        Step.PROVISIONING -> Step.READY
        Step.RECOVERY -> Step.READY
        else -> this
    }

    fun onNext(): Step = when (this) {
        Step.WELCOME -> Step.PERMISSIONS
        Step.PERMISSIONS -> Step.COMPATIBILITY
        Step.COMPATIBILITY -> Step.READY
        Step.READY -> Step.PROVISIONING
        else -> this
    }

    fun hasBack(): Boolean = onBack() != this

    /** Whether a primary action button is shown (and what it does). */
    val buttonAction: ButtonAction?
        get() = when (this) {
            Step.WELCOME, Step.PERMISSIONS, Step.COMPATIBILITY -> ButtonAction.NEXT
            Step.READY -> ButtonAction.START
            Step.ACTION_REQUIRED -> ButtonAction.FINISH
            Step.RECOVERY -> ButtonAction.OPEN_SETTINGS
            Step.PROVISIONING, Step.FAILED -> ButtonAction.RETRY
        }

    companion object {
        fun byName(name: String): Step? = entries.firstOrNull { it.name == name }
    }
}

enum class ButtonAction {
    NEXT, START, RETRY, FINISH, OPEN_SETTINGS
}

private data class StepContent(
    val title: Int,
    val body: Int,
)

private val Step.content: StepContent
    get() = when (this) {
        Step.WELCOME -> StepContent(R.string.setup_welcome_title, R.string.setup_welcome_desc)
        Step.PERMISSIONS -> StepContent(R.string.setup_permissions_title, R.string.setup_permissions_desc)
        Step.COMPATIBILITY -> StepContent(R.string.setup_compatibility_title, R.string.setup_compatibility_desc)
        Step.READY -> StepContent(R.string.setup_ready_title, R.string.setup_ready_desc)
        Step.PROVISIONING -> StepContent(R.string.setup_provisioning, R.string.loading)
        Step.ACTION_REQUIRED -> StepContent(
            R.string.finish_provision_title, R.string.finish_provision_desc)
        Step.RECOVERY -> StepContent(R.string.setup_recovery_title, R.string.setup_recovery_desc)
        Step.FAILED -> StepContent(R.string.setup_failed, R.string.setup_failed)
    }

/**
 * Simple stateful wizard. Renders the current [step] with a cross-fade and
 * delegates navigation to the activity, which owns the provisioning launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    step: Step,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    onOpenSettings: () -> Unit,
)
{
    // default (which would finish the activity mid-wizard).
    BackHandler(enabled = step.hasBack()) { onBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        topBar = {
            if (step.hasBack()) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "setup_step",
            ) { s ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(s.content.title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(s.content.body),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            when (val action = step.buttonAction) {
                ButtonAction.NEXT -> Button(
                    onClick = onNext, modifier = Modifier.widthIn(min = 200.dp)) {
                    Text(stringResource(R.string.setup_button_next))
                }
                ButtonAction.START -> Button(
                    onClick = onNext, modifier = Modifier.widthIn(min = 200.dp)) {
                    Text(stringResource(R.string.setup_button_start))
                }
                ButtonAction.RETRY -> Button(
                    onClick = onRetry, modifier = Modifier.widthIn(min = 200.dp)) {
                    Text(stringResource(R.string.setup_button_retry))
                }
                ButtonAction.FINISH -> Button(
                    onClick = onFinish, modifier = Modifier.widthIn(min = 200.dp)) {
                    Text(stringResource(R.string.setup_button_finish))
                }
                ButtonAction.OPEN_SETTINGS -> Button(
                    onClick = onOpenSettings, modifier = Modifier.widthIn(min = 200.dp)) {
                    Text(stringResource(R.string.setup_button_open_settings))
                }
                null -> Unit
            }
        }
    }
}
