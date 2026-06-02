# Android Frontend Expansion Plan

Status: planning. Branch: `main`.

## Goal

Move the Android app from a single debug-oriented control panel to a product-facing conversation
interface while preserving the current offline speech loop, measurement hooks, and runtime controls.

This is a frontend expansion plan, not a model-runtime rewrite. ASR, LLM, TTS, VAD, barge-in, and
asset provisioning should remain behind the existing controller/runtime boundaries.

## Assumptions

1. Android remains the primary implementation track until the iOS runtime path is measurable.
2. Jetpack Compose + Material 3 stays as the frontend stack.
3. The first usable screen should be the conversation experience, not a landing page.
4. Debug controls remain available, but they should move out of the primary user flow.
5. The app must clearly communicate offline/runtime readiness without implying network fallback.

## Current Frontend Smells

1. `ConversationScreen` is a flat debug panel: many equal-weight buttons compete with the main task.
2. User-facing flow and engineering diagnostics share the same visual hierarchy.
3. Runtime readiness is represented by scattered booleans instead of a single UI model.
4. Text output areas are unstructured; user turns, assistant turns, latency, and status compete.
5. Hands-free, push-to-talk, typed turns, and one-off diagnostics are presented as peers.
6. No preview fixtures or stable frontend state contract exists for Compose-only iteration.

## Target UX

The first screen should behave like a quiet conversation console:

1. Primary area: transcript timeline with user turns, assistant streaming text, and current listening state.
2. Primary control: one large mic/talk control that reflects idle, listening, generating, speaking, and stopped states.
3. Secondary controls: typed turn entry, barge-in toggle, language selector, and stop/cancel.
4. Runtime strip: compact readiness indicators for ASR, LLM, TTS, VAD, and model assets.
5. Diagnostics drawer/screen: ASR wav test, raw TTS synth, LLM-only prompt test, capture dump status, and latency details.

## Proposed UI Structure

```text
ui/
  ConversationRoute.kt        // bridges Activity/controller callbacks to UI state
  ConversationUiState.kt      // immutable state consumed by composables
  ConversationAction.kt       // user intents emitted by composables
  ConversationScreen.kt       // product-facing conversation screen
  components/
    RuntimeReadinessStrip.kt
    TranscriptTimeline.kt
    MicControl.kt
    LatencySummary.kt
    DebugRuntimePanel.kt
```

`MainActivity` should own platform permission and runtime initialization. `ConversationRoute` should
own UI state mapping and action dispatch. `ConversationScreen` should stay render-only.

## Implementation Order

### Step 1: Create a UI state/action boundary

Add `ConversationUiState` and `ConversationAction`. Keep current behavior and button callbacks, but
make `ConversationScreen` consume one state object and one action handler.

Verification:

1. Python contract test ensures `ConversationScreen` no longer accepts a long list of primitive flags.
2. Android JVM/Gradle build still passes.

### Step 2: Separate primary conversation from diagnostics

Split the existing debug controls into `DebugRuntimePanel`. Keep it reachable from the main screen,
but make transcript + mic the primary visual path.

Verification:

1. Current debug actions remain wired.
2. Contract test ensures ASR wav test, raw TTS speak, and LLM-only prompt are not removed.

### Step 3: Add transcript timeline model

Represent user/assistant/status rows explicitly instead of mixing `convLine`, `convReply`, `llmOut`,
and `msg` as loose strings.

Verification:

1. Hands-free and typed turns both append timeline rows.
2. Barge-in/cancel shows an interrupted turn without corrupting the next turn.

### Step 4: Add runtime readiness strip

Convert `initOk`, `llmOk`, `asrOk`, `vadOk`, and model-path diagnostics into a compact readiness
model that distinguishes ready, missing asset, initializing, and failed states.

Verification:

1. Missing ASR/LLM/TTS/VAD assets are visible before the user starts a turn.
2. Offline runtime state never suggests network fallback.

### Step 5: Add Compose previews and frontend fixtures

Add preview states for ready, missing assets, listening, generating, speaking, barge-in enabled, and
error states. Use fixtures instead of real runtime objects.

Verification:

1. Compose previews compile in Android Studio.
2. Python contract test keeps runtime classes out of preview fixtures.

### Step 6: Add device UI smoke tests

Use Android emulator/device automation for simple UI checks once the screen has stable identifiers.

Verification:

1. Launch app.
2. Confirm primary mic/talk control exists.
3. Confirm diagnostics panel is reachable.
4. Confirm runtime readiness indicators are visible.

## Non-Goals For This Pass

1. Do not redesign the model runtime.
2. Do not add network features.
3. Do not implement account/login/onboarding.
4. Do not hide diagnostics permanently; move them to a secondary surface.
5. Do not start iOS parity UI until the Android screen contract stabilizes.

## First Concrete Refactor

Start with Step 1:

1. Add `ConversationUiState`.
2. Add `ConversationAction`.
3. Change `ConversationScreen` signature to `state: ConversationUiState, onAction: (ConversationAction) -> Unit`.
4. Keep `MainActivity.Screen()` as the state owner for now.
5. Add a contract test that prevents `ConversationScreen` from regressing to a long primitive parameter list.
