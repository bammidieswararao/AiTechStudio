# AiTech Studio

An offline motion-design and print studio for Android, inspired by HyperFrames. Design animated titles
and logo reveals, add sound and a voice-over, then export an **MP4** or a **print-size PNG** (flex
banners, posters, cards). Everything runs on the phone. The app has **no INTERNET permission**.

## What you get

**Design**
- 7 scenes: Neon Pulse, Cyber Glitch, Cinematic, Pop Burst, Aurora Flow, Retro Wave, Flex Banner
- Title, subtitle, tagline, contact line and logo (PNG/JPG from your gallery)
- 7 font styles, 11 colour palettes (or your own colours), 7 text animations
- Telugu and other Indic text is split by grapheme cluster, so animations never cut a letter in half
  (it uses the phone's own fonts; see "Status" below)

**Effects (VFX)**
- Floating sparks, burst (sparks or confetti), shockwave rings, light sweep, neon glow, RGB split, bloom,
  camera shake, impact flash, scanlines, film grain, vignette. Each one is a switch.

**Video**
- 1080x1920, 1080x1350, 1080x1080, 1920x1080, 720x1280, 1280x720
- 24, 30 or 60 fps, 3 to 30 seconds (longer is allowed up to 60 s when a voice-over needs it), H.264 MP4 with AAC audio
- Frame-exact: the picture is a pure function of time, and each frame gets an exact timestamp, so the
  video never drifts or stutters even when a frame is slow to draw
- Saved to `Movies/AiTechStudio` in your gallery

**Print and flexi**
- Size calculator: px, in, ft, cm, mm with a DPI setting, shown as pixels, megapixels and inches
- Presets: social sizes, flex banners (2x3 ft up to 20x10 ft, roll-up standee), A1 to A4, Letter, cards
- Up to 150 megapixels or 30,000 px on the long side. If a size is too big, DPI is lowered and the
  screen says so
- Transparent background option (RGBA PNG)
- The picture is drawn in strips and streamed to disk, so a banner does not need all its pixels in
  memory at once. The PNG records its DPI (pHYs), so print shops see the right physical size
- Saved to `Pictures/AiTechStudio`

**Sound**
- Sound packs: Cinematic, Tech, Pop, or none. The effects are synthesised on the phone from maths, so
  there are no sample files, and the cues come from the same timeline as the animation (the hit lands
  on the exact frame the title lands)
- Quality: Standard (44.1 kHz, 128 kbps), High (48 kHz, 192 kbps), Studio (48 kHz, 256 kbps)
- Mastering: bass and air EQ, reverb amount, stereo width, punch compressor, loudness normalised to
  about -16 dBFS RMS with a -1 dBFS peak ceiling
- Your own music (MP3, M4A, WAV, OGG) with start offset, volume, looping, fades, and automatic
  ducking under the voice

**Voice and offline models**
- Voice-over from the phone's own text-to-speech. Only voices that are installed and need no network
  are listed. Effects: Clean, Studio, Radio, Robot, Hall, Echo, Warm
- Or import a voice recording you made
- Captions: "Script timing" needs nothing extra. For real word timing, import a **Vosk** speech model
  (a .zip made on a computer or another phone) once; after that it works offline

**AI video background (LongCat-Video)**
- LongCat-Video is a 13.6-billion-parameter model that needs a CUDA GPU, so it cannot run on a phone.
  What the app does instead: you generate a clip with it on a GPU computer or cloud notebook, copy the
  MP4 to the phone, and the studio puts your text, effects and sound on top of it, offline.
  The page has a "Prompt for LongCat-Video" box that writes a good prompt for the scene you picked.

## Get the APK (direct download link)

I could not build the APK inside the sandbox where this was written (it had no access to Maven Central,
Google Maven or the Gradle servers). The project is set up so GitHub builds it and gives you a plain
`.apk` link:

1. Create a new GitHub repository and put the contents of this folder in it (keep the hidden
   `.github/workflows/build-apk.yml` file).
   - On a computer: drag the folder contents into "Add file > Upload files".
   - On a phone with Termux: `unzip AiTechStudio.zip && cd AiTechStudio && git init && git add -A && git commit -m init && git branch -M main && git remote add origin <your repo url> && git push -u origin main`
   - On a phone browser, the file picker cannot select folders. Use "Add file > Create new file" and type
     the path `.github/workflows/build-apk.yml` to paste the workflow, then upload the rest by zip
     on a computer or with Termux.
2. Open the **Actions** tab, choose **Build APK**, and tap **Run workflow** (it also runs on every push).
3. When it finishes (about 5 to 10 minutes) the APKs are published as a **Release** in the repository.
   Open **Releases** and tap the `.apk` file. These direct links always point to the newest build:

   ```
   https://github.com/<you>/<repo>/releases/latest/download/AiTech-Studio-debug.apk
   https://github.com/<you>/<repo>/releases/latest/download/AiTech-Studio-release.apk
   ```

   The finished run also prints these two links on its summary page. A zipped copy is kept under the
   run's **Artifacts** as a backup.
4. Install the APK (allow "install unknown apps" for your browser or files app).

**Two APKs are built every time**
- `AiTech-Studio-debug.apk` is for testing. It installs as "AiTech Studio (debug)" next to the normal app,
  and if something fails it logs more detail.
- `AiTech-Studio-release.apk` is the normal build.

If the repository is private, the links only work while you are signed in to GitHub in the browser; make
the repository public if you want to share the link with other people.

The workflow first tries to build with the offline speech engine (Vosk). If that library cannot be
fetched it builds again without it, and the app still works; only the "offline model" captions option
will say the engine is missing.

By default both APKs are signed with a debug key, which is fine for your own phone. To update the release
app in place later, add your own key as repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`; the workflow will use it for the release APK.

### Build on your computer instead
Open this folder in Android Studio (Ladybug or newer, JDK 17) and run the `app` configuration, or run
`gradle :app:assembleDebug` (or `:app:assembleRelease`) with Gradle 8.9. Add `-PwithVosk=true` to include the speech engine.

## Using it

1. **Design** tab: pick a scene, type your text, choose font, colours and animation.
2. **Effects** tab: switch effects on and off.
3. **Sound** tab: pick a pack and quality, add music, tap **Preview sound**.
4. **Voice** tab: write a script, pick a voice and effect, tap **Create voice-over** (or **Use my recording**), then **Build captions**.
5. **Export** tab: choose Video or Image, pick a size, and tap **Render MP4** or **Export PNG**. Keep the app open on screen while
   it renders; the screen stays awake and a Cancel button is shown.

Tip for flex banners: pick the preset, check the pixel size shown, and send the PNG to your printer with
the physical size in the file name. A 12x4 ft banner at 100 DPI is 14,400 x 4,800 px (69 MP).

## Offline speech models (captions)

1. On a computer or another device, download a model zip from https://alphacephei.com/vosk/models
   (the "small" models are about 40 to 50 MB). There are small models for English, Hindi, Telugu and more.
2. Copy the zip to the phone, open the **Voice** tab, tap **Import model (.zip)**, and pick it.
3. Choose "Offline AI model" under Caption timing and tap **Build captions**.

Models are unpacked into the app's private storage. Nothing is downloaded by the app.

## Status: what was and was not tested

Tested while building this (on a Linux sandbox, not on a phone):
- The effects engine rendered every scene with a real canvas implementation; strip-by-strip rendering
  matches full-image rendering
- The studio page ran in a headless DOM with a mock phone bridge (sizes, calculator, export payloads)
- All Kotlin compiles against the Android API 34 stubs without errors
- The audio mixer, reverb, effects, ducking and loudness ran on a JVM: peak -1 dBFS, RMS about -16 dBFS where
  the material allows, no clipping, music ducks about 9 dB under voice, loop seams are click-free
- The PNG writer output was decoded with an independent library: pixels match exactly, DPI and colour
  chunks are correct, RGB and RGBA both work

**Not tested** (needs a real phone):
- Building the APK itself
- The hardware pieces: MediaCodec H.264/AAC encoding, OpenGL frame upload, MediaMuxer, MediaStore saving
- Text-to-speech voices, Vosk captions, importing clips and music, previewing the imported background clip
- How Telugu text looks: the sandbox had no Telugu font, so only the letter-splitting logic could be
  checked. On the phone it uses the system font (Noto Sans Telugu on most devices)

If something fails on the phone, the app shows the reason on screen. A useful first report is the phone
model, Android version, the size and fps you chose, and the message shown.

## Limits worth knowing

- Very large video sizes depend on the phone's encoder. If it cannot make a size the app tells you and
  suggests a smaller one.
- Bloom (glow) is not applied to print exports, because it cannot be drawn strip by strip without seams.
- The editor and encoder share the phone's memory. If the editor restarts during a huge export, choose a
  smaller size or lower DPI.
- On Android 8 and 9, exports are saved in the app's own folder (shared through Open and Share) instead of
  the gallery, because the gallery needs a storage permission there. Android 10 and newer use the gallery.
- Backgrounding the app during an export can pause drawing. Keep it on screen.

## Project layout

```
app/src/main/assets/studio/index.html   the studio page (UI, preview, timeline)
app/src/main/assets/studio/engine.js    effects engine: render(ctx, cfg, t, W, H) is a pure function of time
app/src/main/java/com/aitechapps/studio/
  MainActivity.kt, StudioBridge.kt      WebView host and the JavaScript-to-phone bridge
  VideoExporter.kt, GlVideoEncoder.kt   frames -> OpenGL -> H.264, with Mp4Mux interleaving audio
  PngExporter.kt, PngWriter.kt          strip rendering and the streaming PNG encoder
  AudioEngine.kt, Synth.kt, Dsp.kt      offline mixer, synthesised effects, filters/reverb/compressor
  AudioIO.kt, AacEncoder.kt             decoding (WAV by hand, others via MediaCodec) and AAC encoding
  VoiceTts.kt, Captioner.kt             offline voices and Vosk captions (loaded by reflection)
  BgVideo.kt, MediaSaver.kt             imported background clip frames; saving to the gallery
.github/workflows/build-apk.yml         builds the APK on GitHub
```

## Credits and licences

- Inspired by the HyperFrames idea of video as a pure function of time (https://github.com/heygen-com/hyperframes).
  No HyperFrames code is included.
- LongCat-Video (https://github.com/meituan-longcat/LongCat-Video) is a separate project under the MIT
  licence. Only the clips you generate with it are used; no model code or weights are included.
- Vosk (Apache 2.0) is an optional dependency.
