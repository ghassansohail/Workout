# Form Coach: plan to fix coaching, rep counting and voice

**For:** the coding agent working in this repo.
**Baseline:** commit `9c5f6d1`. Line numbers below refer to that commit.

Read the whole document before you change any code. Work through the phases in order. Finish each phase with a green build, passing unit tests and its own commit. Do not add exercises or features that this plan does not describe.

---

## 1. What the user reports

1. The coach talks too much, at the wrong moments, and often says the wrong thing.
2. The rep count is wrong, and the app never says it out loud.
3. Movements that are not reps get counted.
4. It does not behave like a coach who watches posture and corrects it.

## 2. Root causes in the current code

| # | Problem | Location | Effect for the user |
|---|---|---|---|
| 1 | If a required landmark's likelihood drops below 0.55 for a single frame, the state machine resets to SETUP. That reset also clears the rep in progress and the last prompt. | `PoseCameraController.kt:132-141` | Half-finished reps are lost. When tracking comes back, the app repeats the setup prompt and then the five-second "Ready. Keep your knees over your toes..." line. This happens mid-set, most often at the bottom of a squat, where one leg hides the other. |
| 2 | The UI calls `voiceCoach.stop()` whenever `personDetected` turns false. | `MainActivity.kt:91-93` | Speech is cut off mid-sentence every time tracking flickers. |
| 3 | Voice cues reach the speaker through Compose state (`voiceCue`) and a `LaunchedEffect` keyed on that state. | `MainActivity.kt:84, 91-93, 156` | Cues can be dropped or repeated. There is no queue and no priority. |
| 4 | `VoiceCoach` speaks straight away whenever 800 ms have passed since the last utterance *started*. It uses `QUEUE_FLUSH` and has no `UtteranceProgressListener`. | `VoiceCoach.kt:31-48` | Each new cue cuts off the previous one, so long sentences never finish. |
| 5 | Repeat suppression only compares a cue with the previous one (`lastSetupPrompt`, `activeFault`). `activeFault` is also cleared on every normal frame. | `PoseCameraController.kt:255, 296-306` | A check that hovers around its threshold repeats the same cue every two seconds or so. Two checks that alternate make the voice ping-pong between their cues. |
| 6 | The setup checks keep running during reps: `if (setupError != null) return activeWarning(...)`. | `PoseCameraController.kt:254` | In a squat, the shoulder-hip-ankle angle naturally drops below 105° at or below parallel. The app then says "Stand tall and keep your chest lifted" at the bottom of a *good* squat, and the early return skips rep bookkeeping for those frames. In a push-up, the shoulders move ahead of the wrists at the bottom, which triggers "Place your hands under your shoulders". |
| 7 | "Rising too soon" compares two consecutive raw frames with a 4° margin. | `PoseCameraController.kt:267` | Normal frame-to-frame jitter of 2-5° during the descent triggers "Lower a little more before standing" in the middle of a rep. |
| 8 | The tracked side is picked again on every frame, from summed `inFrameLikelihood`. | `PoseCameraController.kt:131, 374-382` | `inFrameLikelihood` only says whether a point is inside the image. It does not say whether the point is visible, so it cannot tell the near limb from the hidden one, and the chosen side flips between frames. In front-view curls with one arm bent and the other straight, as in alternating curls, every left-to-right flip completes a "rep". That causes huge over-counts. |
| 9 | A rep is just one raw frame below the depth angle followed by one raw frame above the start angle. There is no smoothing, no minimum time in each zone, no duration limit, no check of the movement pattern and no explicit start to the set. | `PoseCameraController.kt:256-285` | Picking up weights, adjusting the phone, scratching your head and kneeling down after push-ups all count as reps. |
| 10 | The app nags every 10 s while waiting, uses long "Ready..." sentences, and treats "Feet look set. Turn sideways..." as an *error*. | `PoseCameraController.kt:223-228, 246-250, 359` | This adds chatter. Squat setup can never pass while facing the camera, and at an angled stance it flip-flops. |
| 11 | There is a head check: ear-shoulder-hip angle below 145°. | `PoseCameraController.kt:335-341` | People look at their phone, and the ear landmark is noisy, so this cue fires falsely and often. |
| 12 | The rep count is never spoken. | n/a | The most useful thing a coach says is missing. |
| 13 | The screen is not kept awake. Rotating the device recreates the activity. Flipping the camera resets reps. The preview crops with `FILL_CENTER`. | `MainActivity.kt:63-66`, `PoseCameraController.kt:44, 57` | The camera stops when the screen sleeps mid-set. Push-ups filmed in landscape reset all state. The user does not see the same frame the model sees, so they place themselves badly. |
| 14 | There are no unit tests and no Gradle wrapper, and CI only assembles the APK. | build files | Rep counting cannot be checked without a person exercising in front of the phone. |

Delete these behaviours outright; do not try to tune them: the head check, the setup checks that run during a set, the frame-to-frame "rising too soon" check, the waiting nag, the stop-speech-on-flicker effect, the per-frame side choice, and the UI-string filter in `VoiceCoach.speak` at line 32.

---

## 3. Target behaviour (the acceptance reference)

### 3.1 Session flow

1. **IDLE.** The user picks an exercise and presses **Start set**. Reps are counted only after this point, which removes false counts while the user is walking into position.
2. **POSITIONING.** The app gives one setup instruction at a time. It speaks an instruction only after the issue has stayed the same for 1 s. It then waits for the user to comply and says "Good" once when the issue is fixed. This phase ends when the user holds the start posture still for 1.5 s, and the app calibrates at that moment.
3. **COUNTDOWN.** The app says "Ready. Go." and nothing more.
4. **IN_SET.**
   - Count every valid rep aloud as soon as it completes: "1", "2", ...
   - Give at most one short correction per rep, right after the count.
   - Speak a correction only if the fault appeared in 2 of the last 3 reps. Partial reps are the exception and get a cue straight away.
   - Stay silent otherwise.
   - Praise rarely, as described in 3.3.
5. **PAUSED.** The app enters this state when tracking has been lost for more than 1.5 s.
   - Keep the count.
   - Say "I can't see you. Step back into view." once, after 2 s of lost tracking.
   - Resume silently when tracking returns and the user is in the start posture. Do not repeat the setup.
6. **SET_COMPLETE.** The set ends when the user presses Stop. Once at least one rep has been counted, it also ends when the user rests in the start posture for 8 s, or, for push-ups, leaves the plank for 3 s. With zero reps the set never ends on its own.
   - Speak one summary sentence, for example "Set done. 10 reps. Next set, keep your chest up."
   - Show a summary card on screen.

### 3.2 Speech rules (hard limits, enforced in tests)

- Counts are never interrupted. A count is dropped only when a newer count replaces it.
- Nothing interrupts a count. A count may cut off any lower-priority utterance.
- Allow at most one form cue per rep. Each fault has a cooldown of 3 reps *and* 15 s.
- Leave at least 2.5 s between non-count utterances, measured from the *end* of the previous one.
- Drop a queued utterance as stale once it is older than 1.5 s. Setup utterances may wait up to 2 s.
- Cues during a set are at most 4 words long. Setup cues are at most 10 words long.
- A single changed frame never causes speech. Every spoken condition must stay stable for its debounce time first.

### 3.3 Script (use these exact lines)

| Situation | Line |
|---|---|
| Start posture reached | "Ready. Go." |
| Rep counted | the number, e.g. "7" |
| First partial rep in the set | "Not counted. Go lower." (curl: "Not counted. Curl higher.") |
| Later partial reps | "Lower." (curl: "Higher.") |
| Fault cue | see the fault tables in Phase 5 |
| A fault that was cued is absent in the next 2 reps | "Better." (at most once per 5 reps) |
| Tracking lost for 2 s | "I can't see you. Step back into view." |
| Set complete | "Set done. {n} reps." plus, if a fault happened in at least 30% of reps, " Next set, {fault advice}." |

---

## 4. Phases

### Phase 0: Tooling and safety net

1. Add a Gradle 8.9 wrapper. Gradle is not installed on this machine, so either run `brew install gradle` or download the 8.9 distribution to a temporary folder. Then run `gradle wrapper --gradle-version 8.9` in the repo root. Commit `gradlew`, `gradlew.bat` and the `gradle/wrapper/` folder.
2. Add these dependencies to `app/build.gradle.kts`:
   - `testImplementation("junit:junit:4.13.2")`
   - `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")`
   - `implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")`
3. In `.github/workflows/build-apk.yml`, switch to `./gradlew` and run `:app:testDebugUnitTest` before `:app:assembleDebug`.
4. Update the "Open and run" section of `README.md`.

**Done when** `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes with one trivial test.

### Phase 1: Separate the pure logic from Android

Target layout:

```
app/src/main/java/com/formcoach/app/
  MainActivity.kt                  UI only
  WorkoutViewModel.kt              owns camera controller, session, voice; exposes StateFlow<WorkoutUiState>
  camera/PoseCameraController.kt   CameraX + ML Kit, produces PoseFrame
  camera/PoseFrameMapper.kt        ML Kit Pose -> PoseFrame
  coach/PoseFrame.kt
  coach/OneEuroFilter.kt
  coach/BodyGeometry.kt            angles, lean, ratios, view detection, near-side selection
  coach/RepCounter.kt              generic rep phase machine over one signal
  coach/ExerciseConfig.kt          ALL thresholds, in one place
  coach/exercise/SquatAnalyzer.kt, PushUpAnalyzer.kt, CurlAnalyzer.kt
  coach/WorkoutSession.kt          session states, emits CoachEvent
  coach/CoachEvent.kt
  coach/SpeechPolicy.kt            CoachEvent -> Utterance decisions (pure)
  voice/VoiceCoach.kt              TextToSpeech playback only
  debug/PoseRecorder.kt            landmark CSV recording (debug builds only)
app/src/test/java/com/formcoach/app/coach/...
```

Rules:

- Nothing in `coach/` may import `android.*` or `com.google.mlkit.*`, so that plain JVM unit tests can run it.
- Take time from frame timestamps. In the camera code, convert `ImageProxy.imageInfo.timestamp` from nanoseconds to milliseconds; in tests, use the synthetic clock. Never call `SystemClock` inside `coach/`.

```kotlin
data class Landmark(val x: Float, val y: Float, val z: Float, val inFrame: Float)
class PoseFrame(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: Array<Landmark?>, // index = ML Kit PoseLandmark type, 0..32; null = missing
)
```

Steps:

1. **Threading.** In the analyzer, call `Tasks.await(detector.process(image))` on the camera executor. It is a background thread, so this is allowed. Map the pose to a `PoseFrame`, feed it to the session on that same thread, and publish UI state through a `StateFlow`. Close the `ImageProxy` in a `finally` block. Give each camera binding an id and drop results that belong to an older binding.
2. **Interim logic.** Do not port the old `RepCoach`. Until the new engine lands, the app runs a placeholder engine that only reports tracking. This work happens on the `coach-rework` branch, so `master` keeps the old behaviour meanwhile.
3. **Debug recorder.** In debug builds, add a "Record landmarks" toggle. It writes a CSV file to `getExternalFilesDir("recordings")` named `<exercise>_<yyyyMMdd-HHmmss>.csv`. Write one header row, then one row per frame: `t_ms,w,h` followed by 33 groups of `x,y,z,p`. Record landmarks only, never images. Add a pure-Kotlin CSV reader in test sources; do not use `org.json`, because it is stubbed in local unit tests.
4. **Synthetic pose generator** (test sources). Build a 2D stick figure from segment lengths and joint angles, for both side and front views.
   - Write scripts as joint-angle keyframes over time, with interpolation between them.
   - Add Gaussian noise with σ equal to 1% of body height on each coordinate.
   - Allow injecting dropouts and left/right swaps.
   - Provide distractor scripts: walking in, bending over to touch the phone, raising an arm to scratch the head, kneeling down, standing still.

**Done when** the app behaves as before, and a test can feed generated frames through the session.

### Phase 2: Signal quality

1. **Smoothing.** Normalise coordinates by image height, then apply a One Euro filter to each coordinate. Start with `minCutoff = 1.5 Hz`, `beta = 0.3` and `dCutoff = 1.0 Hz`, and mark the values `// TUNE`. If counts lag on fast reps, raise `beta`. If the angle wobbles by more than 2° while the user stands still, lower `minCutoff`.
2. **Landmark gating.** Treat a landmark as missing when `inFrame < 0.5`. The body counts as tracked when every landmark the exercise needs is present.
3. **Near-side selection** for side views. Use `PoseLandmark.getPosition3D().z`, where a smaller z means closer to the camera. At calibration, pick the side whose relevant joints have the lower mean z, and **lock that side for the whole set**. In front views, use both sides: average symmetric metrics, and track the arms separately for curls.
4. **Outlier rejection.** Drop a frame when the primary signal moves faster than 600°/s, or the equivalent for ratio signals; for comparison, a fast squat moves about 200°/s. Keep the last good value and treat the dropped frame as a dropout.
5. **Dropout grace.** If tracking is missing or rejected for up to 1.5 s, hold every state and say nothing. After 1.5 s, switch to PAUSED and abandon the rep in progress without counting it, but keep the count.
6. **Model choice.** Try `com.google.mlkit:pose-detection-accurate:18.0.0-beta5` with `AccuratePoseDetectorOptions` in `STREAM_MODE`, and log the frame rate. Keep it if it reaches 15 fps or more on the user's phone; otherwise keep the base model. Make the choice a constant.

**Done when** these tests pass:

- After filtering, the primary-angle standard deviation is below 2° for noisy standing.
- A 1 s dropout in the middle of a rep keeps the rep and stays silent.
- 5% injected left/right swaps do not change the count.

### Phase 3: Session state machine

1. Implement `WorkoutSession` with the states in section 3.1.
   - Inputs: frames and user commands (start, stop, select exercise).
   - Outputs: `WorkoutUiState`, exposed as a state, and `CoachEvent`s, sent through a `Channel` or a `SharedFlow`. Never send events as Compose state.
2. Run setup checks **only** in POSITIONING. During IN_SET, check only tracking and the rep logic.
3. Calibrate when POSITIONING ends: take the median of the primary signal over the 1.5 s hold, plus the body-scale lengths each exercise needs (leg length, arm length, torso length).
4. Disable exercise switching and camera flipping during IN_SET, or make them end the set.
5. Move the session and the camera controller into `WorkoutViewModel` so that rotation keeps all state.

### Phase 4: Rep detection

Build one generic `RepCounter` that tracks a single signal through these phases:

```
TOP -> DOWN (left the top zone) -> BOTTOM (depth reached, held >= 2 frames and >= 60 ms) -> UP -> TOP (count)
```

- In DOWN, track the extreme value reached. If the signal turns back by at least 12° (or 0.08 for ratio signals) without reaching depth, mark the attempt as **partial**. When the signal returns to the top zone, emit `PartialRep`, which is not counted.
- If the signal never passes the **attempt** threshold, ignore the movement silently. It was not a rep attempt.
- Use only a valid rep's own frames when you evaluate it. Count it only if its duration is within limits and every **movement gate** passes. If a gate fails, discard the movement silently, because it was not this exercise.
- The top zone is relative to the calibrated value: `calibratedTop - 15°`, but never easier than the floor value in the table.
- Count as soon as the top zone has been confirmed for 2 frames, so the spoken count feels immediate.

Starting thresholds. Put all of them in `ExerciseConfig.kt` and mark each one `// TUNE`.

| | Squat, side view | Squat, front view | Push-up (side view only) | Curl (any view, per arm) |
|---|---|---|---|---|
| Primary signal | knee angle (hip-knee-ankle) on the locked side | hip drop ratio = (hipY - calibrated hipY) / calibrated hip-to-ankle length | elbow angle (shoulder-elbow-wrist) on the locked side | wrist lift ratio = (elbowY - wristY) / calibrated forearm length |
| Top zone | ≥ max(150°, calibrated - 15°) | ≤ 0.08 | ≥ max(145°, calibrated - 15°) | ≤ -0.6 (arm hanging) |
| Depth for a counted rep | ≤ 100° | ≥ 0.28 | ≤ 95° | ≥ 0.5 (wrist well above elbow) |
| Attempt threshold (partial if below depth) | ≤ 135° | ≥ 0.12 | ≤ 130° | ≥ 0.0 |
| Duration | 0.6 - 8 s | 0.6 - 8 s | 0.5 - 8 s | 0.5 - 8 s |
| Movement gates (all must pass) | ankles move < 0.10 leg length; hip drops ≥ 0.15 leg length; torso lean < 65° from vertical | ankles move < 0.10 leg length; torso lean < 65° | shoulder-to-ankle line within 35° of horizontal throughout; wrists move < 0.15 arm length; shoulder drops ≥ 0.2 arm length | upper arm within 40° of vertical throughout; elbow moves < 0.2 torso length relative to the shoulder |

Notes:

- The knee angle in a 2D image barely changes in a front view, because the knee bends toward the camera. That is why front-view squats use the hip drop ratio instead.
- In a front-view curl, the 2D elbow angle passes through an undefined point when the forearm points at the camera. That is why curls use the wrist lift ratio, which works in both views.
- **Curls, front view.** Run one counter per arm. If both arms complete within 400 ms of each other, count one rep, because the user is curling both arms together. Otherwise count each arm separately, as in alternating curls. Show the total with a left/right breakdown.
- **Re-calibration.** If the ankles drift, say because the user stepped closer, re-calibrate the next time the user holds still in the top zone.

**Done when** the rep scenarios in Phase 8 pass.

### Phase 5: Coach-quality form feedback

Collect `RepMetrics` during each rep. Evaluate faults when the rep ends and report every fault seen on that rep in `CoachEvent.RepCounted.faults`. The engine does not decide what gets spoken. `SpeechPolicy` keeps the rolling history of the last 3 reps per fault, applies the 2-of-3 rule and the cooldown from section 3.2, and says "Better." Partial reps are reported as `PartialRep` events and are spoken straight away.

Turning back before the top zone is a **partial rep** of kind `NO_LOCKOUT`. It is not counted. Its cue comes from the "No lockout" or "No full extension" row below.

**View detection** runs during POSITIONING and is then locked for the set.

- Compute `ratio = shoulder width / torso length`. The view is FRONT when the ratio is above 0.5, SIDE when it is below 0.3, and ANGLED in between.
- Apply ±0.05 hysteresis and require the view to stay the same for 1 s.
- Allowed views: squat in SIDE or FRONT, push-up in SIDE only, curl in SIDE or FRONT.

**Squat faults**

| Fault | View | Metric | Start threshold | Cue | End-of-set advice |
|---|---|---|---|---|---|
| Forward lean | side | torso angle from vertical at the bottom | > 50° and > shin angle + 15° | "Chest up." | keep your chest up |
| Heels lift | side | heel rise relative to rep start | > 0.04 leg length | "Heels down." | keep your heels down |
| Knees cave | front | knee gap / ankle gap at the bottom | < 0.85 | "Knees out." | push your knees out |
| Weight shift | front | sideways offset of the hip midpoint from the ankle midpoint at the bottom | > 0.15 hip width | "Stay centred." | keep your weight even |
| Fast descent | any | duration of the down phase | < 0.5 s | "Slow down." | control the way down |

**Push-up faults**

| Fault | Metric | Start threshold | Cue | End-of-set advice |
|---|---|---|---|---|
| Hips sag | signed hip distance below the shoulder-ankle line | > 0.06 body length | "Hips up." | squeeze your glutes |
| Hips pike | signed hip distance above that line | > 0.08 body length | "Hips down." | keep a straight line |
| No lockout | turns back down before reaching the top zone | any | "Lock out." | straighten your arms at the top |
| Fast descent | duration of the down phase | < 0.4 s | "Slow down." | control the way down |

**Curl faults**

| Fault | Metric | Start threshold | Cue | End-of-set advice |
|---|---|---|---|---|
| Elbow drift | largest angle of the upper arm from vertical | > 25° | "Elbows pinned." | keep your elbows at your sides |
| Swinging | change in torso lean during the rep | > 10° | "No swinging." | keep your body still |
| No full extension | turns back up before reaching the top zone | any | "Straighten fully." | straighten your arm at the bottom |
| Fast lowering | duration of the lowering phase | < 0.5 s | "Lower slowly." | lower the weight slowly |

**Setup checks** (POSITIONING only). The engine emits `SetupIssueChanged` whenever the highest-priority failing check changes. `SpeechPolicy` speaks a check only after it has stayed the same for 1 s, repeats it no sooner than 10 s later, and stops speaking it after 3 repeats. The text stays on screen.

1. Visibility: "Step back so I can see your whole body." For curls: "Step back so I can see you to your hips."
2. View: "Turn fully sideways, or face the camera." For push-ups: "Turn sideways to the camera."
3. Start posture: "Stand tall." / "Get into a high plank." / "Arms straight by your sides."
4. Squat in front view: if ankle gap / shoulder width < 0.7, say "Feet a little wider." If it is > 1.9, say "Feet a little closer."
5. Push-up, held at the top: if the sideways gap between shoulder and wrist exceeds 0.35 arm length, say "Hands under your shoulders."
6. Stillness: the start posture is held for 1.5 s with the primary signal's standard deviation below 3°. Then calibrate and say "Ready. Go."

### Phase 6: Voice

1. **`SpeechPolicy`** (pure, in `coach/`) turns `CoachEvent`s into `Utterance(text, priority, createdAtMs, key)` and enforces every rule in section 3.2. Priorities, highest first: COUNT, STATE ("Go", paused, set done), FORM, SETUP, PRAISE. Muting is a flag inside the policy.
2. **`VoiceCoach`** (TextToSpeech playback only):
   - Register an `UtteranceProgressListener` and track `isSpeaking` from `onStart`, `onDone` and `onError`.
   - Keep a priority queue. When the speaker is idle, play the highest-priority utterance that is not stale.
   - Use `QUEUE_FLUSH` only when a count preempts a lower-priority utterance. Otherwise use `QUEUE_ADD`.
   - Call `setSpeechRate(1.1f)` and set `AudioAttributes` with `USAGE_ASSISTANCE_SONIFICATION` and `CONTENT_TYPE_SPEECH`.
   - Request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while speaking, so the user's music ducks instead of stopping. Use the pre-API-26 audio focus call when needed, because minSdk is 23.
   - Check the result of `setLanguage` and fall back to `Locale.US`. If TTS fails to initialise, show "Voice unavailable" in the UI.
3. Remove the speech `LaunchedEffect` from `MainActivity.kt`. Wire voice in the ViewModel instead: `session.events` goes to `SpeechPolicy`, which feeds `VoiceCoach`. The UI never calls `stop()`, except when leaving the screen.

**Done when** the speech tests in Phase 8 pass, run with a fake TTS that has a controllable clock and fixed utterance durations.

### Phase 7: UI

1. Keep the screen on while the camera screen is visible, with `FLAG_KEEP_SCREEN_ON`.
2. Give Preview and ImageAnalysis the same 4:3 aspect ratio through a `ResolutionSelector`, and switch the preview to `FIT_CENTER`. The user then sees exactly the frame the model analyses.
3. Draw a skeleton overlay on a Canvas. Map image coordinates to view coordinates with the same scaling as `FIT_CENTER`, and mirror x for the front camera. Highlight the locked side, and draw missing or low-confidence joints in grey.
4. Add a large rep counter, with the partial-rep count shown smaller. Add a state banner showing Positioning, In set, Paused or Set complete.
5. Add a Start/Stop button and a summary card after each set showing reps, partial reps and the top fault.
6. Show a placement hint for each exercise during POSITIONING:
   - Squat: "Phone at hip height, 2-3 m away, side-on or facing you."
   - Push-up: "Phone on the floor, landscape, side-on, 1.5-2 m away."
   - Curl: "Phone at chest height, 1.5-2 m away."
7. Keep the last cue text on screen for at least 2 s so it does not flicker. Remove the default "Form looks good".
8. Support landscape without losing state; this is needed for push-ups. Disable the exercise chips and the camera-flip button during a set.

### Phase 8: Tests and tuning (acceptance)

All of these must pass in `./gradlew :app:testDebugUnitTest`.

| # | Scenario (synthetic unless stated) | Expected |
|---|---|---|
| 1 | Squat, side view, 10 clean reps of 2 s each, with noise | 10 reps; 10 counts spoken; 0 form cues |
| 2 | Squat, 10 reps; reps 4 and 7 only reach a knee angle of 125° | 8 reps, 2 partials, "Not counted. Go lower." after rep 4 and "Lower." after rep 7 |
| 3 | Standing still for 30 s with 3° jitter after "Go" | 0 reps; no speech; the set stays open |
| 4 | Walk into frame, bend over to touch the phone, walk back | 0 reps; 0 form cues |
| 5 | 500 ms dropout at the bottom of rep 5 | rep 5 counted; nothing about tracking spoken |
| 6 | 4 s dropout between reps 5 and 6 | one "I can't see you..." line; PAUSED; count stays at 5; final count 10 |
| 7 | 5% injected left/right swaps | count unchanged |
| 8 | Forward lean of 60° on reps 3-10 | "Chest up." after rep 4; next repeat no earlier than 3 reps and 15 s later; at most 3 in total |
| 9 | Lean is fixed after the first cue | "Better." once |
| 10 | Squat, front view, 10 reps with knees caving | 10 reps; "Knees out." spoken |
| 11 | Push-up, 10 reps with sagging hips, then knees down and standing up | 10 reps; "Hips up." at most 3 times; no extra rep; auto set end |
| 12 | Curl, front view, 10 reps with both arms | 10 reps (not 20) |
| 13 | Alternating curls, 5 per arm, front view | 10 reps (left 5, right 5) |
| 14 | Arm raised overhead to scratch the head, phone adjusted | 0 reps |
| 15 | Setup check alternating between two faults every 200 ms for 10 s | at most 2 setup utterances |
| 16 | Any scenario | no two utterances overlap, except a count cutting off a lower-priority line |
| 17 | Fast squats of 0.7 s each | spoken count never lags more than one rep behind; stale counts dropped |
| 18 | Recorded CSV fixtures from the user (below) | exact true counts |

**Device validation protocol (the user records, the agent turns recordings into fixtures):**

1. For each exercise, record three sets with the debug recorder:
   - a clean set of 10 reps
   - a set with deliberate faults, such as shallow reps or forward lean
   - a set with distractions: walking in and out, adjusting the phone, a 5 s pause
2. Name each file with the true count, for example `squat_side_10reps_faults.csv`. Put the files in `app/src/test/resources/recordings/`.
3. Add a replay test that asserts the exact count and the fault cues expected for each file.
4. Tune thresholds only against the whole set of recordings, never against a single file. Next to each tuned value, note which recordings it was checked against.

### Phase 9 (optional): MediaPipe Pose Landmarker

Do this only if recorded sets still miscount by more than 1 in 10 after tuning. Migrate to the MediaPipe Tasks Pose Landmarker with the full model. It reports occlusion-aware visibility and world landmarks in metres, which make 3D joint angles reliable in front views. `PoseFrameMapper` keeps the change inside `camera/`.

---

## 5. Ground rules for the agent (all tracks)

- Follow the phases in order and commit after each phase. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` before every commit.
- Keep every threshold in `ExerciseConfig.kt`. Mark guesses with `// TUNE`.
- Never save camera images. Recordings contain landmarks only, are debug-only and are opt-in. Update the privacy line in `README.md` to say so.
- After each phase, report what changed, the test results, and any threshold you could not validate.
- If a rule in this plan turns out to be wrong on real recordings, change the rule and the test together, and explain why in the commit message.

---

## 6. Work split across agents

Phase 0 is done on branch `coach-rework`. The shared contracts are in `app/src/main/java/com/formcoach/app/coach/`:

- `PoseFrame.kt` holds the frame data and landmark numbering.
- `WorkoutEngine.kt` holds the engine interface, commands, UI state and the `Exercise` enum.
- `CoachEvent.kt` holds the events the engine reports.
- `Speech.kt` holds the `Speaker` and `SpeechScheduler` interfaces.

Do not change these files inside a track. If a track needs a change, describe it in its final report and the integrator applies it.

Each track works in its own git worktree, branched from `coach-rework`, and touches only the files it owns. A worktree does not contain `local.properties`. Create it with `sdk.dir=/Users/dev/Library/Android/sdk` before building. Run `./gradlew --stop` when finished so idle Gradle daemons free their memory.

### Wave 1 (parallel)

| Track | Model | Plan scope | Owns |
|---|---|---|---|
| A. Engine | Opus | Phase 2 steps 1-5, Phases 3, 4, 5 | `coach/OneEuroFilter.kt`, `coach/BodyGeometry.kt`, `coach/RepCounter.kt`, `coach/ExerciseConfig.kt`, `coach/exercise/*`, `coach/WorkoutSession.kt`, and their tests under `app/src/test/.../coach/` (not `synthetic/`) |
| B. Synthetic poses | Sonnet | Phase 1 step 4 | `app/src/test/java/com/formcoach/app/coach/synthetic/*` |
| C. Voice | Sonnet | Phase 6, speech rows 15-17 of Phase 8 | `coach/SpeechPolicy.kt`, `voice/VoiceCoach.kt`, `app/src/test/.../coach/SpeechPolicyTest.kt` |
| D. Camera and UI | Sonnet | Phase 1 step 1, Phase 2 step 6, Phase 7 | `MainActivity.kt`, `WorkoutViewModel.kt`, `camera/*`, `ui/*`, `AndroidManifest.xml`, removal of the old `PoseCameraController.kt` and `VoiceCoach.kt` usage in the UI |
| E. Recorder | Haiku | Phase 1 step 3 | `coach/PoseFrameCsv.kt`, `debug/PoseRecorder.kt`, `app/src/test/.../coach/PoseFrameCsvTest.kt` |

Shared rules:

- Track A tests with small hand-built frames. The synthetic generator from track B arrives in wave 2.
- Track D uses placeholder implementations of `WorkoutEngine`, `SpeechScheduler` and `Speaker`. It keeps them in `WorkoutViewModel.kt` behind one factory function each, so the integrator can swap one line per piece.
- CSV format for track E. The header row is `t_ms,w,h` followed by `x{i},y{i},z{i},p{i}` for i = 0..32. Each frame is one row in the same order. A missing landmark writes four empty fields.

### Wave 2 (after merging wave 1)

1. **Integration** (the integrator). Merge the tracks and wire the real `WorkoutSession`, `SpeechPolicy`, `VoiceCoach` and `PoseRecorder` into `WorkoutViewModel`. Then build, test and commit.
2. **Acceptance** (Opus). Implement Phase 8 rows 1-14 with the synthetic generator, and tune `ExerciseConfig.kt` until they pass.
3. **Device validation** (the user, then the agent). Record sets with the debug recorder and add them as fixtures for row 18.
