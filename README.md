# AudioText

A lightweight Android text-to-speech app using the voices installed on the device.

## Features in version 1.0

- Light, minimal Compose interface
- Select an installed Android TTS voice
- Adjust speech rate
- Listen to text and stop playback
- Export supported text to a WAV file in Music/AudioText
- Keep a local history of recent text snippets
- No paid API key or network permission required

## Build an APK on GitHub

Every push to the default `main` or `master` branch runs the **Build AudioText APK** workflow. When it succeeds:

1. Open the repository's **Actions** tab.
2. Select the latest **Build AudioText APK** run.
3. Under **Artifacts**, download `AudioText-debug-apk`.
4. Extract the ZIP and install `app-debug.apk` on your Android phone.

You can also start a build manually from Actions using **Build AudioText APK** → **Run workflow**.

## Notes

- Available languages and voice quality depend on the TTS engine and voice data installed on the phone.
- The first version exports WAV rather than MP3 because Android's built-in TTS API does not guarantee MP3 output.
- Audio export is limited to the installed engine's supported input length in this initial version.
