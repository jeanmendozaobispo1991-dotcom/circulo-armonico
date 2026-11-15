# Harmony Lab — Android prototype

Starter project in Kotlin for an Android chord and MIDI editor.

## Build
1. Open this folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync.
3. Install Android SDK 35 if Android Studio requests it.
4. Run the `app` configuration on an Android device or emulator.

## GitHub Actions
The workflow at `.github/workflows/android.yml` builds a debug APK on pushes and pull requests.
Download the `harmony-lab-debug-apk` artifact from the Actions run.

## Current prototype scope
- Basic circle-of-fifths display and key selection.
- Basic Piano Roll grid and note creation / move / pitch adjustment.
- Long-press gesture to resize a note (visual ↔ handle).
- Undo.
- MIDI file generation and Android share sheet.
- Simple synthesized test tone.

## Important limitations
This is an early prototype, not a finished DAW. The sound generator is a synthetic tone,
not an acoustic piano sample. The chord drag/drop and touch gestures need device testing
and refinement. There is no full sequencer playback, multi-touch zoom, scroll system,
or complete Latin note-name setting yet. Validate the exported MIDI in FL Studio before
using it in a production workflow.
