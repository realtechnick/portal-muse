package dev.wobsoriano.museportal.setup

/** The first-run steps, in order. */
enum class SetupStep {
  Token,
  Name,
  Pair,
  HeyMuse,
}

/** How far the user got through the setup screens; kept so a restart resumes where they left off. */
enum class SetupProgress {
  NotStarted,
  Started,
  Finished,
}

sealed interface Screen {
  /** The core has not yet said whether this Portal is paired. */
  data object Loading : Screen

  data class Setup(val step: SetupStep) : Screen

  data object Home : Screen
}

/**
 * What decides which screen shows. [paired] is null until the core first reports. An install that
 * was paired before these screens existed has [SetupProgress.NotStarted] and goes straight home.
 */
data class SetupFacts(
    val hasToken: Boolean,
    val named: Boolean,
    val paired: Boolean?,
    val progress: SetupProgress,
)

val SetupFacts.screen: Screen
  get() =
      when {
        !hasToken -> Screen.Setup(SetupStep.Token)
        paired == null -> Screen.Loading
        !paired -> Screen.Setup(if (named) SetupStep.Pair else SetupStep.Name)
        progress == SetupProgress.Started -> Screen.Setup(SetupStep.HeyMuse)
        else -> Screen.Home
      }
