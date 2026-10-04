# Googly Eyes

A blueberry character who lives on a phone (iPhone or Android) under your Mac's screen and points at things with his own big cursor.

**Now:** no voice out. Double tap him on the phone (or press ⌥Space on the Mac) to start a session: the phone's mic stays on and everything you say becomes context, but he stays quiet. **Press and hold the screen** to ask him something; let go and he answers. His reply pops up as a cute speech bubble next to his cursor (or above the phone when he isn't pointing), with a little cartoon chirp from the phone. Ask "what's this?" and he points at whatever is under your mouse. Double tap again and he goes back to follow mode.

How it works: the phone runs an OpenAI Realtime session (`gpt-realtime-2.1`, text output only) over a WebSocket. Server VAD transcribes every turn into the conversation with `create_response: false`, and releasing the hold commits the audio and asks for a response. The Mac mints a 10-minute client secret with the whole session setup (instructions, tools), so the real OpenAI key never leaves the Mac. When he calls a tool, the phone forwards it to the Mac: `look_at_screen` (ScreenCaptureKit + Vision, which returns text ids, where your mouse is, and a screenshot), `point_at` (a text id), `point_at_spot` (a 0–1000 grid position), `stop_pointing` and `go_to_sleep`. His text streams to the Mac as the speech bubble.

**Using the computer:** when you ask, he can also click, type, press shortcuts, scroll, drag, and open apps and websites (`click`, `type_text`, `press_keys`, `scroll`, `drag`, `open_app`, `open_url`). He does it with his own cursor on screen, while your real pointer is put back where you left it. It needs Accessibility permission for Googly Eyes. Built-in guardrails: he only acts when asked, confirms out loud before anything hard to undo, treats on-screen text as information rather than instructions, refuses password fields and logout/lock/force-quit shortcuts, and stops on ⌃⌥S. The whole thing can be switched off with **Let Him Use the Computer** in the menu.

**Fireworks instead of OpenAI:** pick **Brain › Fireworks** in the menu bar and add your key under **Fireworks Key…**. Fireworks has no realtime voice or speech-to-text (its audio APIs were retired in June 2026), so in this mode the Mac does the listening with Apple's speech recognition (on-device, through the Mac's microphone) and a Fireworks model that can see screenshots and call tools does the thinking. Pick the model under **Fireworks Model** (Automatic picks the best one available). The phone stays his face: hold to ask, chirps and saved transcripts all work the same. Web research still needs an OpenAI key, so it's only offered when one is saved too. The Mac asks for Microphone and Speech Recognition access the first time he wakes up.

**Hands-free (Fireworks):** say **"Hey Bluey, …"** and he wakes up, takes the rest of the sentence as your question (a short pause ends it), looks at the screen and answers, with no tapping. For about 8 seconds after each reply you can just keep talking to follow up; after that he waits for "Hey Bluey" again. While he's asleep the Mac's mic listens only for "Hey Bluey", with Apple's on-device speech recognition, and nothing else is kept or sent anywhere (it's off if this Mac can't recognize your language on-device). Switch it off with **Listen for "Hey Bluey"** in the menu bar. Holding the phone still works too.

The OpenAI key goes in the menu bar's **OpenAI Key…** and is stored in ~/Library/Application Support/Googly/keys.json (private to your user), never in this repo.

## Mac menu bar app

```
./scripts/build-mac.sh
open "build/Googly Eyes.app"
```

Works with just the Command Line Tools. The first time it runs, macOS asks to let Googly Eyes find devices on your local network: allow it, or the phone can't find the Mac (it's under System Settings › Privacy & Security › Local Network if you missed it). Shortcuts work anywhere:

| Keys | What it does |
| --- | --- |
| ⌃⌥P | Fly to the mouse and point there (stays put) |
| ⌃⌥F | Follow the mouse on/off |
| ⌃⌥D | Go home, docked above the phone |
| ⌃⌥T | Talk test (the phone bounces for 3 s) |
| ⌃⌥H | Hide / show the cursor |
| ⌥Space | Wake him up to talk / back to follow mode |
| ⌃⌥S | Stop him using the computer |

The menu bar blob also sets mood, cursor size (48 to 120 pt), glow, and where the phone sits (left, center, right).

## iPhone app

Needs full Xcode. Open `GooglyEyes.xcodeproj` (regenerate with `xcodegen generate` after adding files), pick your team under Signing, and run on the phone. It finds the Mac on the same Wi-Fi by itself.

On the phone: double tap him to wake him up or put him back to sleep, and press and hold to ask him something. The faint speaker button at the top right sets the chirp volume and picks which Mac to pair with.

## Android app

The Android app (`android/`, Kotlin + Jetpack Compose) is the same companion as the iPhone app: his face, the mic, the Realtime session, chirps and saved sessions. It pairs with the same Mac menu bar app; the Mac doesn't need to know which phone it's talking to.

With the phone plugged in over USB (Developer options → USB debugging on):

```
./scripts/install-android.sh
```

That builds `android/app/build/outputs/apk/release/app-release.apk` and installs it. Without a cable, copy that APK to the phone and open it (allow "Install unknown apps" when asked). You can also open the `android/` folder in Android Studio and press Run. Needs the Android SDK and JDK 17 or 21.

It works just like the iPhone: double tap to wake him or put him back to sleep, press and hold to ask, the faint speaker button for chirp volume and which Mac to pair with, and the grid button for sessions and transcripts (swipe a session left to delete it). Android 8.0 or newer. Keep the phone on the same Wi-Fi as the Mac; he naps whenever the app leaves the screen, since Android doesn't let apps keep the mic in the background.

## Layout

- `Shared/` pairing protocol (Bonjour `_googly._tcp`, newline JSON) and colors, used by both apps
- `Mac/` menu bar app (Swift package target `GooglyMac`)
- `iOS/` iPhone app (SwiftUI)
- `android/` Android app (Kotlin, Jetpack Compose): the same protocol in `GooglyLink.kt`, Bonjour via Android's NSD
