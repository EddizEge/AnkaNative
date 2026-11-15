# Anka Native changelog

Changes in Anka Native, grouped by topic. Most of the new behaviour is behind `DM_*` switches (every one is listed in
[docs/env_switches.tsv](docs/env_switches.tsv)); the ones Ocarina of Time needs are set by its game profile
([dm/games/0400486024DEC000/game.tsv](dm/games/0400486024DEC000/game.tsv)), so other games keep their old behaviour unless a switch is set.
The credits for the code it is built on are in [NOTICE.md](NOTICE.md).

## Anka Native 0.3.0

- **Mesh-drawn content is drawn** ([#6](https://github.com/EddizEge/AnkaNative/issues/6)). Ocarina of Time draws part of its world with NVN2 task
  and mesh shaders: in Kokiri Forest the tree canopies, grass, ferns, flowers and the arch by Link's door, in the Deku Tree's upper floor the floor
  itself. 0.2.2 skipped those draws on every Turnip driver, and the indirect ones on every driver (Kokiri Forest 23 of 1,784 a frame, Deku Tree 2F 34 of 665), and its library had almost
  none of their shaders. Where the driver has no task shaders (Turnip) they now run through compute (`DM_VK_MESH_EMU`); where it has
  `VK_EXT_mesh_shader` (Qualcomm's driver) the indirect mesh draw is implemented too. The shader split reads the mesh and task programs, and the
  profile gains 491 mesh and 17 task shaders; the library version stays `v-0dd4341ae6ce`, and a pack made on the phone gets them by the automatic
  shader update below. The content costs GPU time: on the Kokiri Forest balcony about 26 ms a frame (42.5 ms without it, 69-70 ms with it). Checked
  on a POCO F8 Pro (Adreno 830, Anka Turnip): no mesh draw skipped in Kokiri Forest or Deku Tree 2F. Not run on an Adreno 7xx, and the indirect
  mesh draw not on any phone.
- **The white patch on the arch beside Link's door and the white specks along mesh-drawn edges are gone:** the compute form's float maths is now exact. Checked on a POCO
  F8 Pro (the arch's white pixels 2,911 -> 0, the trunk's specks 99 / 68 -> 0); the cost was not measurable (balcony 68 against 69.8 ms).
- **A mesh-drawn object is drawn in every pass of a frame or in none**, so no white outlines show while its forms compile; the compute arenas are
  sized to the largest submission, and a short arena is a `[RED]` log line. Checked on a POCO F8 Pro. At a first start on an empty pipeline cache
  a white glow still shows for about 10 s and the mesh content comes after about 45 s.
- **Game settings › 3D resolution** (`DM_RES_LEVEL`), for games whose profile has `res_choice` rows (Ocarina of Time): Dynamic (the default), Max,
  High, Medium, Low, Lowest, and Dynamic never above High or Medium. The upscaler stand-in now covers every resolution level's network, where the
  levels above the floor took 61-75 ms of GPU time and now take 27-32 ms. In Deku Tree 2F on a POCO F8 Pro, on a build without the mesh content,
  every choice ran at full game speed, 20-31 fps, GPU 24.5-31.8 ms (before: the fixed levels above the floor 13-16 fps, 61-75 ms). With the mesh
  content only Dynamic was measured: 21-24 fps, GPU 32.7-35.5 ms (Anka Turnip 1.0.0). Other scenes were not measured.
- **The overlay shows the size the game renders its 3D scene at** ("3D 683×384", and "→ 2048×1152" when the presented picture differs), and the
  presented fps value is right again. Checked on a POCO F8 Pro.
- **A gamepad's Home / guide key opens the in-game panel** before anything else takes it; the panel's **Both stick buttons… open and close this
  panel** (off by default) is for a pad whose Home Android takes for itself; a pad's Back is no longer logged as an unused key. Checked on a POCO
  F8 Pro with a Razer Kishi V2 and a DualShock 4; L3 + R3 not tried with a pad.
- **Adreno 840 GPU hang** ([#5](https://github.com/EddizEge/AnkaNative/issues/5)): a depth / stencil target's first content now goes into the frame's
  own submission at its render pass, not the upload batch (`DM_VK_DS_FIRST_HERE`, on by default; `=0` goes back). Test switches
  `DM_VK_BIGDEPTH_LOG`, `_NOCOMP`, `_CLEAR`, `_BAND`, `_SYNC` and `_MB` are listed in docs/env_switches.tsv. Not verified on an Adreno 840 (we
  have none); on the Adreno 830 no run showed a change.
- **Driver variables in Extra environment (`TU_*`, `FD_*`, `IR3_*`, `MESA_*`) reach the driver:** 0.2.2 set them after the readiness check had
  loaded it, so `TU_DEBUG` never took effect. The host also makes the Vulkan device after the run's environment is set. Checked on a POCO F8 Pro
  (the driver's log shows `TU_DEBUG`, and `TU_DEBUG` changed the GPU time).
- **After a lost device the log has the driver's fault report** (`VK_EXT_device_fault`, on where the device offers it; `DM_VK_DEVICE_FAULT=0`
  leaves it off). Not run: no device loss on the test phone.
- **The movie library's NDVE network pipelines are kept out of the pipeline cache:** a pipeline cache file that held them took about 1.35 GB of
  GPU memory at every start (POCO F8 Pro, Anka Turnip 1.0.0; the file came from a diagnostic run, `DM_VK_DISPATCH_SKIP=0`, in the test app).
  They are now made outside the cache and never prewarmed, so such files are no longer written, and old files holding them are removed.
  The fix was measured on a computer (drm-shim: 1,536 MiB of private memory with that file, 192 MiB without the network); not checked on a phone.
- Small ports: NVN variable-rate-shading calls are no-ops (about 15,000 fewer `[RED]` lines), SDK 22's `IsOunceVibrationDeviceMounted` is answered,
  and the game asks for audio focus again after losing it for good. Default-off switches `DM_VK_PSO_CHEAP`,
  `DM_VK_TEXFB_SET`, `DM_VK_BLEND_ZERO`, `DM_NVN_TS_HZ`; and `DM_VK_PREWARM_OVERLAP=0` (new: one prewarm build at a time; the default stays 1).
- **The launcher speaks Turkish, Brazilian Portuguese and Latin American Spanish** ([#2](https://github.com/EddizEge/AnkaNative/issues/2)),
  chosen by the phone's language or **Settings › Language** (System default, English, Türkçe, Português (Brasil), Español (Latinoamérica)). Turkish
  was used on a POCO F8 Pro throughout; the others were seen there only on the game card and the notification; no native speaker has read them yet.
- **Android 12 and 12L** (minSdk 31): the app installs there, takes a Vulkan 1.1 loader with a 1.3 device, and the readiness check warns. In
  practice a custom Vulkan 1.3 driver such as Anka Turnip is needed. Built and linted only; not run on Android 12.
- **"Don't show this again" remembers the readiness fact, not its text**, so a changed fact shows its warning again. Checked on the JVM.
- **Packer failures are shown as an error code and a text in the user's language**, and Forget names the translated shaders it keeps. Checked on
  the JVM.
- **A pack made on the phone with 0.2.2 gets an automatic shader update** for the new mesh and task shaders: 584 translated, 42,270 kept, a
  targeted split of 35 files, about 2-5 minutes on a POCO F8 Pro, library byte for byte the full build's. The game can be played meanwhile (it
  pauses the update, without the new content until it is done); nobody prepares again. A pack made on a computer with the 0.2.2 library shows
  "Shaders out of date" and starts without the new shaders until `scripts/pack_android.py` is run again or the game is added from its dump. Tried on
  copies of an old pack; a real update of an installed 0.2.2 not yet on a phone.
- **An unfinished Prepare for another pack folder no longer stops a game card's shader update.** A paused Prepare beside the game that writes
  another pack folder (a test, or a pack you prepared elsewhere) kept the card's automatic shader update from ever running, and the card's "Prepare
  again" could only show that other job, which Home then resumed by itself. The card's update and Prepare again now run in a job folder of the card's
  own pack beside it; the other job is left as it is and is still found by Add game, which no longer takes it over a job of the pack it found or
  is stopped by it when you add a new update. A new job beside it is checked for free space again. A job you only ask to see is no longer started
  by itself, and its line says to tap Resume. Checked on a POCO F8 Pro (the card's update ran beside another pack's paused job, which was left
  byte for byte as it was); Add game's Resume question for a paused job and the low-free-space refusal were not tried on a phone.
- **Prepare's notification and card after a Pause, a kill and Prepare again.** After a quick Pause the notification could stay "Pausing" with no
  button though the Prepare was paused: it now says paused with Resume as soon as the work in hand has stopped, and at once when nothing was running.
  A Prepare killed while Home was open was taken for one you ended with Force stop (Resume offered, not resumed) when you had used Force stop on it
  earlier: only how the Prepare's own last process ended counts now. A game card's "Prepare again" checks that card's own pack; it no longer moves the
  card to another pack folder it finds beside the game, and a job you forgot comes back only if you resume it or ask to see it. A Resume tapped
  while a Prepare is still pausing now goes on once the pause has stopped (it did nothing before), and a game started right after a Pause waits until
  the paused work has stopped. The app no longer logs a Prepare's end
  after each clean stop. Checked on a POCO F8 Pro; a Resume tapped while a Prepare is still pausing cannot be reached from the screen there and
  was checked on the JVM only.
- **Pause stops a Prepare within seconds, and the notification says paused.** Pausing while the shaders were being translated did not stop the
  translation: it went on until the app ended its own process 20 s later, and the notification it left said "Waiting" for the battery or the heat, with a
  Pause button and no Resume, for the whole pause (that notification was already wrong in 0.2.2, after Android's daily limit too). For those 20 s the card
  and the notification still said it was running, with the time left. Now the translation stops at the shader in hand and the split at the file in hand
  (on a computer 0.1-4 s, from 4-11 s in the translation and up to 7 s at the start of the split; inside one of the slowest shaders at most one module's
  compression, about 3 s, where it waited up to 11.5 s for the whole shader), every finished shader is kept for the resume, and the library is the same.
  A Pause can no longer leave the split or the translation waiting for good (the split hung in 2 of 15 Pauses early in its scan on a computer), and a
  Pause in the code rewrite no longer writes a red error line in the log. While it stops, the card and the notification say "Pausing" with no time left; then the job is paused with Resume, also when
  the app had to end its process. On a POCO F8 Pro a Pause stopped the work in 0.05-3.3 s, and the notification said paused with Resume within a
  second after that.
- **A game no longer runs beside a Prepare or shader update, and they no longer run the phone out of memory.** Starting a game while a Prepare or the
  automatic shader update ran (from a frontend, or Play during the update) ran a 12 GB phone out of memory: about 30 s after the game was in, Android
  ended the Prepare and the phone's own background apps (WhatsApp, the camera, the home screen), and the Prepare stood still until it was resumed by hand.
  Now the game pauses it first: its screen says so for a few seconds (at most 25), the game starts once the Prepare has stopped and given back its
  memory, and the Prepare goes on by itself when the game ends, however it ends (Stop, Back, a crash). While the game runs, the card and the notification
  say "a game is running: it goes on when the game ends". A Prepare you paused yourself stays paused (also when you tap Pause while it is stopping for the game), one you ended with Force stop
  is not brought back by a game, and the game can always be started. The game card
  says "Ready · updating shaders" only while its update runs (not for an update made by "Prepare again" that waits for Start); otherwise it says that the update is paused, paused while a game runs, or that the shaders
  are older. On a POCO F8 Pro the Prepare stopped 0.6-2.4 s after a game was started and went on after the game.
- **A Prepare that Android ends comes back by itself, or says it stopped.** When the phone ended a Prepare's process (for memory, or a kill), Android
  was to start it again, but HyperOS never did: the notification went and the Prepare stood still until the app was opened and Resume tapped. Now the
  app starts it again itself where Android lets it: while the app's own process lives (Android allows this while the app is on screen or was just
  left), when the app is opened (unless you ended it with Force stop), and when a game that it waited for ends. When Android has ended the
  app's other processes too, a check a few minutes later (an alarm the Prepare keeps moving on while it works) starts it again or posts the notification
  below. A Pause tapped on the card just as the app opens on such a Prepare now pauses it, where it was dropped and the Prepare resumed by itself. Where Android does not let it, a
  notification says "Interrupted: …, Prepare stopped. Tap to go on", and a tap opens the app and goes on. A Prepare you paused stays paused. Allowing
  the app's Autostart in HyperOS's app settings may let the phone restart it too (not tried). Checked on a POCO F8 Pro: a killed Prepare came back, or said it was interrupted with a tap to go on. HyperOS limits how often
  the check's alarm fires (about once an hour for an app in its rare group; there the first came after 4 min 51 s, the second after 23 min 42 s).
- **Prepare's numbers read right.** The collapsed notification shows what it does and the time left ("Preparing · 45 min"), where Android shows a
  notification with a progress bar on one line with no text under it (on HyperOS it showed only "45 min left ·…"); every notification's title starts with
  what it says (Paused, Waiting, Ready to play, …), so a narrow screen cuts the game's name, not that. After a resume the time left comes back at once from the speed measured
  before, instead of nothing for over 2 minutes. A shader update's card no longer says "shader 0 / 42,854" at its start: the shader count shows once
  the update says how many it translates (584 for a 0.2.2 pack). A paused shader scan that resumes no longer drops the bar (it fell from 17 % to 6 % for
  the 39 s the scan's start took again). English writes the percent sign as "32%". On a POCO F8 Pro the collapsed form showed the state and the time left once an estimate existed
  (about 2 minutes in); a short update's collapsed notification on HyperOS still shows its long title cut with "…". The rest checked on the JVM.
- **Preparing a game on a computer has a terminal interface:** `python scripts/prepare.py` (with the Python of the environment in android/README.md, nothing
  else to install) checks what the packer needs and says how to fix what is missing on this system, and how to get Homebrew, micromamba or curl first
  when a fix needs one the computer lacks (it builds and loads the ZBIC decoder below, so a computer without a C compiler is told so before anything
  starts), lets you pick the `.nup` file or dump folder in a file
  browser, shows what an earlier run left and the disk space the run needs, then runs the packer with its steps, bars, the time left and the clock time it
  should be done, and ends with where the pack is and what to do on the phone, or the failure in plain words. `q` or Ctrl+C stops it, and starting again goes
  on from what was finished. Windows, Linux and the Mac; it falls back to plain lines when there is no full terminal. Ocarina of Time took 26 minutes on a
  12-thread Linux PC. The packer prints a line as each step starts, which the interface reads; `scripts/pack_android.py` stays the way to script a run. On
  Windows Ctrl+Break now stops the packer and its tools as Ctrl+C does, and the shader build's temporary files are now kept in the library's result folder,
  where the next build removes what a stopped one left. Tried on Linux only so far.
- **Prepare says how long it has left.** While a game is being prepared on the phone, the Preparing card (under its progress line) and the notification
  (first, before the step) show the time the whole job has left: "About 45 minutes left", "About 1 hour 15 minutes left", "Less than a minute left". It
  follows the last minutes' speed, so it keeps up when a hot phone slows down, counts only the time the job runs (not a pause or a wait for the battery or
  the heat), and stays quiet when it has nothing to go on: during the dump check, the code rewrite and the start of the shader scan, while paused, and when
  progress stops. Replayed on three full Ocarina of Time Prepares measured on a POCO F8 Pro, it was about 7-16 % off on average, at times up to about 30 %
  (more in the last few minutes). On a POCO F8 Pro the card and the notification agreed in every sample, about 11 % off on average and up to
  41 % in the last 2 minutes.
- **A game started from a frontend no longer asks "Start …?"** (found with iiSU 0.1.6.1). The play intent had its own copy of Play's checks, stricter
  than Play: a pack whose shader library was this version's but made from older shader inputs, which Play starts while the automatic update runs, was
  "not ready", so Home asked first and the game started only from Home's Play, without bringing the frontend back when it stopped. Play and the
  frontend's request now take one decision (`PlayRule`): whatever Play starts at once, the frontend starts at once (with the automatic shader update of a
  dump's pack, as Play, when Home is running and no Prepare job is; else when Home next shows the card), and a game that needs a pack, Prepare or shaders gets Play's own
  dialog with no question in front; its buttons ignore taps through another app's overlay, and a game it starts ("Start anyway") also brings the
  frontend back. Play itself now refuses a pack of another title with its pack dialog. A file that is not on Home still asks "Add this game?"
  (android/FRONTENDS.md). Checked on a POCO F8 Pro with iiSU's request sent by adb (no question, a second request ignored, a non-game file
  refused); iiSU itself started the game on an earlier build that still asked, and not yet on this one.
- **The zstd-compressed code of newer games (ZBIC, Ocarina of Time's) is read with the `libzbicdec` decoder** (`dm/third_party/zstd-zbic`:
  Zstandard's decoder, BSD, with an MIT reader of ZBIC's tables), in place of the zbic package (GPLv2), which the on-phone packer carried up to 0.2.2 and
  whose license did not fit the GPL-3.0-or-later translator in the same process. On Ocarina of Time it gives the same bytes: its code modules, the rewrite and
  the shader split's outputs are identical, so finished packs stay valid and are not rewritten. The APK carries `libzbicdec.so` (about 100 KB instead of the
  590 KB extension). Preparing on a computer no longer installs zbic but needs a C compiler for these games: the decoder is built once, the first time one is
  read (android/README.md). A full Prepare on a POCO F8 Pro wrote the same library bytes.
- **Faster shader preparation on the phone.** The shader translation and the shader scan of the dump run their Python on several
  cores (Python 3.14 subinterpreters, 4 workers on an 8 GB phone, 6 on 12 GB and more); on a PC in the phone's form the translation was about 3.4 times
  faster and the scan about twice. The end of the translation needs 1.2 GB instead of 4.1 GB (measured on a computer), where an 8 GB phone
  most likely stopped ([#13](https://github.com/EddizEge/AnkaNative/issues/13)). The libraries are the same bytes (version `v-0dd4341ae6ce`): finished packs stay valid. Where
  subinterpreters do not work, each step says so in its log and runs as before; for tests, `ondevice/env.txt` in the
  work folder with `DMSH_VK_INTERPS=1` and `DM_SPLIT_INTERPS=1` turns them off (a test switch, android/SHADERS.md). A full Ocarina of Time Prepare took 57-77 minutes on a POCO F8 Pro (12 GB, 6 workers; 56 min 45 s at best),
  where 0.2.0's was projected at about 3 hours, and its packer's peak was 1.95 GiB. Not tried on an 8 GB phone (4 workers).
- **Prepare keeps its translated shaders in the app's own storage**, not beside the dump on shared storage, where each of about 42,000 files went
  through Android's FUSE layer (Ocarina of Time: about 2 minutes only to delete them at the end). The shaders step checks that the phone's internal storage has
  room for them (about 1 GB).
- **Less work per shader in the translation step:** each shader's code is read once instead of once per variant and pass; on a PC the
  work after the translator per shader went down by about a fifth. The library stays the same bytes (`v-0dd4341ae6ce`).
- **The shader scan starts its workers sooner:** it no longer opens the dump file again for each of its 186,583 files, walks the game files once
  instead of twice, and reads the files' first bytes on several threads (on a PC about 70 seconds became 36, measured old and new in turn). Its outputs are the same bytes.
- **A Prepare that 0.2.2 left unfinished goes on after this update** and keeps its finished work: shaders already translated are stamped with the
  tools that made them, and every one whose library entry has the manifest's hash is kept (on a computer: 42,270 kept, 584 translated, the library
  byte for byte the same); a job 0.2.2 paused after its split re-splits and re-translates only what the new tables need. A job stopped in the middle
  of the shader scan starts the scan again. Checked on the JVM and on a computer; not on a phone. Finished packs are not affected.

## Anka Native 0.2.2

- **The launcher no longer takes adb control commands from other apps** ([#7](https://github.com/EddizEge/AnkaNative/issues/7)). Its activity is
  exported, as every launcher's is, and the test commands that add a game, start Prepare, install or pick a GPU driver and export or import a pack
  (android/TESTING.md) were taken from any app's intent. A play or release build now ignores them, with a toast and a log line, unless
  **Settings › ADB control (debug)** is on: off by default, and turning it on asks first. Test builds take them as before. Starting a game from a
  frontend (the `.Launch` and `.Open` aliases) is unchanged.

## Anka Native 0.2.1

- **Prepare on the phone stopped at the end for Ocarina of Time** ([#3](https://github.com/EddizEge/AnkaNative/issues/3)): "76 of 42346 shaders did
  not build: no library written". The same 76 do not build on a computer either (74 that SPIRV-Tools refuses, "Vector access is out of bounds"; a
  mesh program without a mesh layout; Ampere instruction 481), and the game plays without them. The phone's job writes a library only when every
  shader that builds on a computer built, and learns which ones do not from the profile's manifest, which Ocarina of Time did not have yet. Its
  manifest is now in the profile (`dm/games/0400486024DEC000/vk_delta.tsv`, `--publish-delta` from the full Linux build of `v-0dd4341ae6ce`). Checked
  on Linux with the phone's rule (`DMSH_VK_REQUIRE_ALL=1`): without the manifest the build stops with the issue's message; with it, the library is
  written, byte for byte the one the computer's build made. Retry on the phone translates only the 76 again and then finishes.
- **Preparing on Windows and Linux.** `scripts/pack_android.py` runs on Windows, Linux and the Mac (android/README.md, The environment):
  - the shader translator starts through a launcher beside `dmsh_xlate.dll` for each system (`dmsh_xlate-<linux|win>-<x64|arm64>`, .NET apphosts of the
    same translator); the committed `dmsh_xlate` is the Mac's. Checked on Linux: 75 Ocarina of Time shaders translate byte for byte as with the
    earlier `dotnet dmsh_xlate.dll` wrapper, and the library version stays `v-0dd4341ae6ce`.
  - Windows: text files are written with LF line ends (`scripts/lf_newlines.py`), the code is rewritten in one process (no `fork`), the RomFS paths
    of a folder dump use `/` (`shader_split.py`), and files are replaced in place where a rename would fail.
  - `.gitattributes` checks every text file out with LF, so the shader versions and the rewrite key are the same on every system.
  - `tool_lock.py` checks the translator the environment's `XLATE` names.
  - CI (`.github/workflows/prepare-pc.yml`) checks this on Windows and Linux.
- Discussions are open, with a [device reports](https://github.com/EddizEge/AnkaNative/discussions/1) thread.

## Anka Native 0.2.0

- **Prepare on the phone.** The APK now carries the real on-phone packer (CPython 3.14 with the repository's tools, the shader translator
  `libdmsh_xlate.so` and SPIRV-Tools), so Add game › Prepare makes a game's pack from the dump on the phone itself. Experimental: on the POCO F8 Pro
  with Ocarina of Time, the code rewrite took about 3 minutes and the shader scan of the game's 186,583 files about 30; the shader translation was
  still being tested when this was released.
- The packer's build (`android/ondevice/build.sh`, `dm/gfx/tools/dmsh_xlate_android/build.sh`, `build_spirv_tools.sh`) runs with bash on Linux as well
  as on the Mac, and adds **zbic**: the zstd-compressed (ZBIC) code segments of system 22.0.0+ games, which Ocarina of Time has, need it to be read.
- The code rewrite and the shader scan report progress from their start (a module count; a stage line before the file count is known), so the
  step no longer sits at 0 for minutes.
- `android/tools/release.sh` builds with the real packer by default (`ANKA_PACK_ENGINE=native`, the stage from `NX_PACK_STAGE`).

## Anka Native 0.1.1

- **Prepare on the phone looked done but made nothing to play.** 0.1.0 was built without the on-phone packer (`-PnxPackEngine=fake`), so its
  Prepare ran the simulated engine: the progress finished, but the pack it made is a placeholder with no game code and no shaders, and Play could
  not start it. A play or release build without the packer now offers no Prepare (`Ondevice.available()`): Home and the game's card say to prepare
  the game on a computer (README "Install") and to pick that pack, and a placeholder pack left by 0.1.0 is shown as "No pack" and Play says why.
  Test builds keep the simulated engine for UI work.

## Anka Native 0.1.0

The first release: Ocarina of Time (the Switch 2 remake, `0400486024DEC000`) brought up on Android. Measured on one phone only, a POCO F8 Pro
(Snapdragon, Adreno 830, 12 GB of RAM) with a Turnip driver. Donkey Kong Bananza and Mario Kart World were not tested again after these changes.

### The name and the app

- The app is now called **Anka Native**: Anka is the Zumruduanka of Turkish myth, the phoenix that rises from its ashes. New app ids: the release app
  `dev.ankanative.android`, a per-game app `dev.ankanative.android.<suffix>` (`-PnxApp=oot` makes `dev.ankanative.android.oot`), the test app
  `dev.ankanative.android.test`. Each has its own data folder (`Android/data/<app id>/files`), so it installs beside older builds and does not touch their
  saves.
- The Anka mark (a phoenix rising, with flame wings) as the launcher icon, the in-app mark and the notification icon; the launcher's colours follow it
  (fire gold and orange on warm ivory, or on a warm night in dark mode). `android/tools/anka_icons.py` writes the icons from `anka_logo.py`.
- The committed translator `dm/gfx/tools/dmsh_xlate_bin/dmsh_xlate.dll` (and its `.pdb`) was rebuilt from the same source with the build folder mapped
  to `/_/` (`-p:PathMap`), so it names no local folder. Only the dll's fingerprint changed, so the chain versions moved (`DMSH_CHAIN` `d4f17b9bdef3`,
  library `v-0dd4341ae6ce`); a library of the previous version has the same `DMSH_VK_ABI` and is still played.
- Game profiles: only Ocarina of Time (`0400486024DEC000`), Donkey Kong Bananza (`040074A01BF12000`) and Mario Kart World (`0400C3F00006E000`) are
  kept; nxnative's 127 other profiles, their rows in the games table and the tests and tool that needed them (`test_astral_fps.py`,
  `check_thread_cuts.py`) were removed. `dm/tools/sdk_patches.py` is unchanged (it is part of the rewrite key, so packs stay current).
- About: the app names its license, GPL-3.0-or-later as a whole, and points to NOTICE.md; logs and the Vulkan application name say "Anka Native".
- Release signing: `-PnxLive=true` reads the key from `ANKA_KEYSTORE`, `ANKA_KEY_ALIAS`, `ANKA_KEYSTORE_PASSWORD` and `ANKA_KEY_PASSWORD` (environment
  or `~/.gradle/gradle.properties`); `android/tools/release.sh` builds, signs, verifies and checksums a release ([docs/RELEASING.md](docs/RELEASING.md)).
  `Signer.OFFICIAL` holds the release certificate's SHA-256 (`4d7443354f53…3bb0`, CN=EddizEge), so only builds signed with that key call
  themselves official.
- APK names `anka-native-<suffix>.apk`; exported packs `anka-pack-<title ID>-<version>.zip`; the in-app updater looks at this project's releases.
- Game settings > **RAM saver (uses storage)**, per game, off by default: the game's large write-once texture pools live in a file in the app's cache
  folder instead of in RAM (`DM_MEM_FILEBACK=1`, `DM_MEM_FILEBACK_KINDS=shader,texture`). For phones short of RAM with free storage: in Ocarina of Time
  it writes about 2 GB to storage per area load.

### Ocarina of Time (`0400486024DEC000`, update v131072)

- New game profile: boots from a `.nup` dump to the title screen, the opening movie and into the game; the code rewrite and the shader library were made
  on Linux.
- **Game speed at a low frame rate.** The game's frame-ratio division is stepped by the host (a frame-step patch, ported from upstream): at 15 fps the
  game ran at about a quarter of its speed, now at about 0.8-1.0.
- **Anti-aliasing shaders restored.** `shader_split.py` steps 8 bytes past a record that is not a control section (ported from upstream), which finds
  the game's SMAA shaders again (library `v-04fb6ae9ea34`). The pass runs, but its blend weights are still all zero, so there is no visible change yet.
- **A stand-in for agl's NDSR upscaler network** (`DM_VK_NDSR_STANDIN=1`): the network is not run; its output image gets its input sampled bilinearly.

### Memory (the game did not survive its first forest load on 12 GB before)

- **GPU texture-use feedback** (`DM_VK_TEXFB`): the shader library's modules set a bit for each texture-pool ID they index, so the host knows which named
  textures the GPU really uses; under memory pressure the ones it has not used are released (`DM_VK_TEXFB=2`). Its GPU cost was about zero (74.5 against
  75.2 ms). With it the forest load survived for the first time (peak 3,007 MiB).
- **Coarse-first textures** (`DM_VK_SCAN_COARSE`): under memory pressure a texture only a pool scan named is made at a coarse mip first (-157 MiB at the
  forest load).
- **Rate-based memory pressure** (`DM_VK_PRESS_RATE`) with hysteresis (`DM_VK_PRESS_HYST_MB`), and mip drop for more texture kinds (`DM_VK_MIPDROP`):
  pressure starts when available memory falls fast, not only when it is already low, and stays on long enough. Textures remade within 30 s fell from 66 %
  to 44 %, then to 6.6 % with the hysteresis; the remake after pressure ends is spread over frames (a 2.5 s stall became 0.36-0.44 s in 8 of 9 cases).
- **Guest-aliased render targets** (`DM_VK_RTALIAS`, `DM_VK_RTALIAS_MB`): a render target that starts at the same guest address as an earlier one shares
  its allocation. One depth target took a new size almost every frame and got new memory each time (+345 MiB in 110 frames); with aliasing, no extra
  render target memory, and the game left Link's house into Kokiri Forest.
- **File-backed memory pools** (`DM_MEM_FILEBACK`): shader-code pools (and, with the RAM saver, texture pools) as clean file pages the kernel can drop
  instead of compressing them into zram (page faults 2.7k/s to 0.9k/s). Shader-code pools are no longer placed (`DM_VK_PLACE_SHADER_POOLS=0`).
- Fewer page index buffers for Ocarina of Time (`DM_VK_GMEM_PIDX_BUFS=2`, -41 MiB) and a per-pool memory footprint log (`DM_MEM_FOOT`).

### Speed

- **Per-page pool index in the shaders** (library `v-fc7819d73925`): a shader that reads guest memory through a pointer found its pool with an unrolled
  search (253 searches, 82k instructions, 1,236 spills in the forest's terrain shaders); it now reads one 8-byte record per 4 KiB page. Vertex shaders
  became 3-4x faster (Link's house: 2,570 to 695 ns per invocation), GPU time at Link's house 121.6 to 59.1 ms, and the frame rate at the start 4.8-5.4 to
  12.7-16.2 fps. The index costs about 83 MiB.
- **Pipeline-cache saver capped** (`DM_VK_PCACHE_MIN_S`, `DM_VK_PCACHE_QUIET_S`): the pipeline cache was written 36.7 GB in 3 minutes; now 0.88 GB in
  8.6 minutes, and frames over 1 s while walking fell from 13 to 0.
- A sampler LOD bias floor (`DM_VK_LOD_BIAS_MIN=0`, off by default): softens texture shimmer by 10-25 % when on.

### Diagnostics

- Render-target, light-list and per-pipeline GPU time logs, pipeline statistics, NaN scans (`DM_VK_NANSCAN`) and memory footprint lines, used to find
  the causes above; all off by default.
