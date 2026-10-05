# Nothing hybrid fork

This fork keeps Nboard's Android keyboard engine and Nothing themes, and embeds a
Jetpack Compose prediction strip above the native key grid. Fresh installations
start with the English Gboard-style QWERTY layout. Existing settings are preserved.

## Predictions and motion

- Three stationary slots, with the strongest suggestion in the middle.
- Each word reshapes inside its own slot. Suggestions never sweep across the
  stationary dividers when their ranking changes. Predictions use neutral text
  with no persistent highlight; pressing a suggestion adds a faint neutral wash
  without changing its size or tap area.
- Individual graphemes retain identity through edits using sequence correspondence.
  Matching letters glide to their new horizontal positions; new letters enter with
  2 dp of movement and a restrained 110 ms dissolve. Removed letters fade away.
  Accents and joined emoji remain intact. Separators and tap targets stay still,
  and keyboard height does not change.
- Taps always insert the latest target word, even while an older word is fading out.
- Android's animator duration scale is respected. Text correction settings also
  include a **Fluid prediction animation** toggle.
- Word, bigram and trigram usage is stored locally. Learned candidates can move
  ahead of dictionary candidates; short sessions are saved when the keyboard hides.
- Password fields and editors requesting no personalized learning do not contribute
  to the learned dictionary.

See [visual research](ios-prediction-motion-research.md) for the reference and evidence limits.

These are iOS-inspired transitions, not a claim of matching Apple's private keyboard
implementation. Use **Try your keyboard** in settings to judge motion on your phone.

## Clipboard

Open the tools arrow, then the clipboard icon. Tap a card to paste; hold to pin,
unpin, delete, or use its text in AI assistance. All saved cards are scrollable.

History contains at most **50 text clips**, including pins. There is no hourly
expiry. Adding the 51st clip removes the oldest unpinned clip. Pins are protected;
if all 50 are pinned, a new clip is not saved. Copying an existing clip moves it
forward without duplicating it or losing its pin. Whitespace is preserved.

History and pins survive process restarts and phone reboots. The recent-paste chip
still disappears after 45 seconds; that timer does not delete saved history.
Android clipboard access restrictions still apply: Nboard captures clips when the
system permits its IME to read the clipboard. Image previews are transient; the
persistent history stores text. Sensitive clips marked by the source app are skipped.

The clipboard settings page can pause capture or clear unpinned clips. Saved clips
are excluded from Android cloud backup and device transfer.

## Number shortcuts and settings

With **Number row** disabled, small 1–0 hints appear on Q–P. Hold the letter and
release on the number; accented alternatives remain accessible in the hold popup.
Enabling Number row displays a separate number row instead.

Light haptics use an 8 ms pulse at amplitude 80 and are the new default;
System, Medium, Strong, and Off remain available in Preferences.
Selecting a strength plays a preview. Devices without amplitude control use their
default amplitude with the selected pulse duration.

The smiley key opens emojis directly on fresh installations. Hold it to choose
another bottom-key action. Letter holds include accented alternatives, with Q–P
number shortcuts first; longer lists wrap into rows that fit the screen. On the
123 page, the second row begins `! @ # $`. Euro remains on the extended symbols
page and in the dollar key's currency hold menu.

The keyboard toolbar provides clipboard, settings, AI assistance, and a return-to-
typing button. The launcher opens category settings inspired by Gboard, including
Preferences, Text correction, Clipboard, Theme, and AI assistance.

## ChatGPT subscription

Choose **AI assistance → Choose provider → ChatGPT plan**, then open **Provider
configuration → Continue with ChatGPT**. Authorize Nboard and ChatGPT plan usage
in the system browser, return to Nboard, and choose an available account model.
Eligibility and usage limits are controlled by OpenAI and your selected workspace.
Nboard does not fall back to a paid API key when plan access fails.

The integration uses the documented open-source public-client OAuth flow: a stable
host ID, PKCE, state and nonce validation, signature-verified ID tokens, a loopback
callback, encrypted Android Keystore credentials, renewable tokens, and the public
Responses endpoint. Credentials are stored in the app's no-backup directory.
Predictions run locally. Text is sent to OpenAI only when you invoke AI assistance.
Sign-out attempts remote revocation and clears the local credentials.

Official sources:

- [Open-source ChatGPT plan usage](https://developers.openai.com/siwc/token-sharing-open-source)
- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

## Build and verification

Use Java 17 or newer with Android SDK 35. Set `sdk.dir` in an untracked
`local.properties`, or set `ANDROID_HOME` to your SDK installation.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it, enable Nboard
under Android's input method settings, and choose it as the active keyboard.

Unit tests cover clipboard eviction, pin protection, persistence, legacy records,
whitespace, and cache invalidation, plus OAuth/PKCE, identity verification and
subscription response streams. Compose instrumentation tests cover interrupted
prediction updates and insertion of the latest word during motion.

Account sign-in and real subscription inference require a user's own authorization
and must be tested on their device; unit tests cannot establish account eligibility.
