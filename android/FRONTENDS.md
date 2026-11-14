# Emulator frontends

Anka Native starts a game for an emulator frontend (ES-DE, Daijisho, iiSU, Pegasus, LaunchBox, Beacon, Cocoon, or anything that runs `am start`). The frontend lists your `.nup` files ([NUP.md](NUP.md)) and sends the one you pick to the app. The app's component for this, its play intent, is:

```
dev.ankanative.android/dev.ankanative.android.Launch
```

`Launch` is an alias of `FrontendLaunch`, and its name follows the application ID: the play app's is `dev.ankanative.android.play/dev.ankanative.android.play.Launch`, the test app's `dev.ankanative.android.test/dev.ankanative.android.test.Launch`. In a frontend that takes the short form, that is `dev.ankanative.android/.Launch`. The alias, and `Open` for file managers, are the only exported parts of the app that start games. Both are always on, in every build; they need no setting.

- [What the app accepts](#what-the-app-accepts)
- [What happens](#what-happens)
- [What another app can do](#what-another-app-can-do)
- Frontends: [ES-DE](#es-de), [Daijisho](#daijisho), [iiSU](#iisu), [Pegasus](#pegasus), [LaunchBox](#launchbox), [Beacon and Cocoon](#beacon-and-cocoon), [anything else](#anything-else)
- [Status](#status)

## What the app accepts

Any action (`VIEW`, Eden's `android.nfc.action.TECH_DISCOVERED`, none at all) and any MIME type; the game is one of:

| Form | Example |
|---|---|
| The extra `game`: the file's path | `-e game /storage/emulated/0/ROMs/switch/game.nup` |
| The extra `game` with a URI as a string (`file://` or `content://`, as below) | `-e game file:///storage/emulated/0/ROMs/switch/game.nup` |
| The data: a storage (SAF) document URI, from a folder picked in the frontend (ES-DE's `%ROMSAF%`, Pegasus's `{file.documenturi}`) | `content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fswitch%2Fgame.nup` |
| The data: Pegasus's own provider URI (`{file.uri}`) | `content://org.pegasus_frontend.android.files/ext/ROMs/switch/game.nup` |
| The data: a provider URI whose path carries the file's path (a FileProvider "root" path) | `content://<frontend>.fileprovider/root/storage/emulated/0/ROMs/switch/game.nup` |
| The data: a `file://` URI | `file:///storage/emulated/0/ROMs/switch/game.nup` |
| The extra `title_id`: a game that is on Home | `-e title_id 0400486024DEC000` |

The extra `game` with the file's path is the most reliable form. The app opens games by path, so a URI is only turned back into a path (`PathUtil.documentToPath`, `Frontend.providerPath`); the app never reads the file through the frontend's URI. A provider URI that carries no path (Recent, a MediaStore number, a cloud file, a frontend's own provider the app does not know) is refused and the app says why: switch the frontend to the path form. The path must be on the phone's shared storage (`/storage/…`, `/sdcard/…`, an SD card), not in `Android/data` or `Android/obb`, and have no `..` in it (`Frontend.sharedPath`). The `/sdcard` and `/storage/self/primary` aliases are the same files as `/storage/emulated/0` (`Frontend.normal`). A game folder works too, given by its path, but frontends list files, so `.nup` is the format for them; `.nsp` and `.xci` are not games the app opens. The file's name must end in `.nup` in lower case.

All files access must be on (the app asks for it on Home).

## What happens

1. **The game is on Home and ready**: it starts at once and nothing of the launcher is shown. "On Home" means a card with this title ID (the extra `title_id`), or the card that holds this very file (by any of its paths, or through a symbolic link), or else the card of the same title and game build as this file (another copy of the card's `.nup`, or the folder it was made from). "Ready" means what Play checks without asking: a game this APK has, a pack that reads and fits (this game build, finished code, this app's shader library), no save backup or restore and no pack export or import working. The game starts with the card's own game and pack, so the frontend picks only which of your games plays. When you stop the game (the in-game panel › Stop the game, or Back on a failed start), the frontend comes back.
2. **The game is on Home but needs something** (a pack, Prepare, a shader update, a pack that does not fit): Home opens and asks "Start …?". Play then takes its usual steps, which say what is missing and offer the fix.
3. **The file is not on Home**: Home opens and asks "Add this game?", with the file's path. When your list has another dump of the same game (another build), the question names it with its pack, and Add game then asks whether the new file replaces it or both stay. Add then takes Add game's own steps: the game is checked, its pack is looked for as Add game looks for it, and it is added (or Prepare is offered). Nothing is added if you cancel. Once it is on Home and ready, the frontend starts it at once from then on. The file must be one game: a folder that holds several is refused (Add game in the app adds those). A file of the same title and build as one of your cards is that card (step 1 or 2), so it is never added in its place: a frontend's request cannot change which file or pack a card uses.
4. **A game is already running**: the running game comes to the front. When the frontend asked for another game, it says so: one game runs at a time; stop it first.

A frontend never chooses the pack, the save folder, the GPU driver or any setting: the extras that do that are adb's control commands ([TESTING.md](TESTING.md)), which play and release builds take only while Settings › ADB control (debug) is on (issue #7), and only on Home's own component (`MainActivity`), never on `Launch`.

## What another app can do

`Launch` and `Open` are exported without a permission, so every app on the phone can send them anything, which is what lets every frontend use them. What any app can do with them, and why that is acceptable:

- **Start a game that is on Home and ready**, as a home-screen shortcut can. It runs the code of a pack you added, from the paths on its card; the request cannot name another pack, game folder, save or setting. At worst an app starts your game when you did not ask (annoying, not harmful); if a game is running, it only brings it to the front.
- **Open Home with a question** ("Start …?", "Add this game?"). Nothing happens unless you tap Play or Add, and those buttons ignore taps that land through another app's overlay. Read the path in "Add this game?": it is the file the other app named.
- **Nothing else.** The play intent never adds, replaces or removes a card, never picks or writes a pack, never starts Prepare or a shader update, never installs or picks a driver, never changes a setting, `env.txt` or a save, and never returns anything to the caller (no result, no URI permission passed on). Home takes a frontend's game only from `FrontendLaunch` itself, by a one-time token kept in the app's process; an extra another app sends Home cannot name one. A path out of shared storage (`..`, `/data/…`, a link to such a place) is refused before anything reads it.

So a crafted `.nup` with a pack beside it, which an app dropped into `Download/`, does not run by itself: it would have to be added by you, after the question.

## ES-DE

ES-DE reads custom emulators from `ES-DE/custom_systems/es_find_rules.xml` and custom systems from `ES-DE/custom_systems/es_systems.xml`, in its application data folder. Add the emulator (for the play app, use `dev.ankanative.android.play/dev.ankanative.android.play.Launch`):

```xml
<?xml version="1.0"?>
<ruleList>
    <emulator name="ANKA-NATIVE">
        <!-- Nintendo Switch 2 games on Anka Native (its play intent, android/FRONTENDS.md) -->
        <rule type="androidpackage">
            <entry>dev.ankanative.android/dev.ankanative.android.Launch</entry>
        </rule>
    </emulator>
</ruleList>
```

Then make Anka Native one of the Switch system's emulators. A system in `custom_systems/es_systems.xml` with the same `<name>` as a bundled one replaces it, so copy the bundled `switch` entry from ES-DE's own `es_systems.xml` (its Eden, Kenji-NX and Skyline lines), add `.nup .NUP` to its `<extension>` and add the two Anka Native lines. With ES-DE's bundled lines as of 2026-11-14:

```xml
<?xml version="1.0"?>
<systemList>
    <system>
        <name>switch</name>
        <fullname>Nintendo Switch</fullname>
        <path>%ROMPATH%/switch</path>
        <extension>.nca .NCA .nro .NRO .nso .NSO .nsp .NSP .xci .XCI .nup .NUP</extension>
        <command label="Eden (Standalone)">%EMULATOR_EDEN% %ACTION%=android.nfc.action.TECH_DISCOVERED %DATA%=%ROMPROVIDER%</command>
        <command label="Eden Nightly (Standalone)">%EMULATOR_EDEN-NIGHTLY% %ACTION%=android.nfc.action.TECH_DISCOVERED %DATA%=%ROMPROVIDER%</command>
        <command label="Kenji-NX (Standalone)">%EMULATOR_KENJI-NX% %ACTION%=org.kenjinx.android.LAUNCH_GAME %EXTRA_bootPath%=%ROMSAF%</command>
        <command label="Skyline (Standalone)">%EMULATOR_SKYLINE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>
        <command label="Anka Native (Standalone)">%EMULATOR_ANKA-NATIVE% %EXTRA_game%=%ROM%</command>
        <command label="Anka Native SAF (Standalone)">%EMULATOR_ANKA-NATIVE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMSAF%</command>
        <platform>switch</platform>
        <theme>switch</theme>
    </system>
</systemList>
```

Then pick Anka Native for the `.nup` games (Other settings › Alternative emulators for the whole system, or the game's metadata editor for one game). `%ROMSAF%` needs ES-DE's ROM folder picked through its storage access. Use `%ROM%` (the path) first. What `%ROMPROVIDER%`, ES-DE's own provider URI, looks like was not checked: if it carries no path the app can read, the app refuses it and says so.

## Daijisho

Daijisho has no screen for adding a player by hand. Its platforms are JSON files: the bundled ones come from its repository (`TapiocaFox/Daijishou`, `platforms/NintendoSwitch.json`, revision 26 on 2026-11-14), and it imports a platform file you give it. A Switch platform with Anka Native in its player list is that file with these two entries added at the top of its `playerList` array and `revisionNumber` raised by one:

```json
{
  "name": "switch - Anka Native",
  "uniqueId": "switch.dev.ankanative.android",
  "description": "Supported extensions: nup.",
  "acceptedFilenameRegex": "^(.*)\\.(?:nup)$",
  "amStartArguments": "-n dev.ankanative.android/dev.ankanative.android.Launch\n -e game {file.path}",
  "killPackageProcesses": false,
  "killPackageProcessesWarning": false,
  "extra": ""
},
{
  "name": "switch - Anka Native (URI)",
  "uniqueId": "switch.dev.ankanative.android.uri",
  "description": "Supported extensions: nup.",
  "acceptedFilenameRegex": "^(.*)\\.(?:nup)$",
  "amStartArguments": "-n dev.ankanative.android/dev.ankanative.android.Launch\n -a android.intent.action.VIEW\n -d {file.uri}",
  "killPackageProcesses": false,
  "killPackageProcessesWarning": false,
  "extra": ""
}
```

For the play app, use `dev.ankanative.android.play` in both places of each `-n`. Import the file in Daijisho (Settings › Library › import a platform), then pick the player on the Switch platform. Once Daijisho's repository has the entry, every Daijisho user gets it with no import.

## iiSU

iiSU keeps its emulators in a JSON file, `emuladores.json`, in `iiSULauncher/Emuladores/` of its storage folder; each console has an `"emulators"` list, and each emulator has commands in the form `<package>/<activity> <am start arguments>`, with `%ROM_PATH%` (the file's path) and `%ROM_URI%` (a URI). Copy the file somewhere you can edit it, add this entry to the end of the Switch console's `"emulators"` list (a comma after the entry before it), and import it in iiSU (Settings › iiSU Settings › Updates › Import emuladores.json):

```json
{
  "id": "ANKA-NATIVE",
  "name": "Anka Native (Standalone)",
  "routeType": "uri",
  "commands": [
    {
      "description": "Anka Native Path",
      "command": "dev.ankanative.android/.Launch -e game %ROM_PATH%"
    },
    {
      "description": "Anka Native",
      "command": "dev.ankanative.android/.Launch -a android.intent.action.VIEW -d %ROM_URI%"
    }
  ],
  "packages": ["dev.ankanative.android"]
}
```

Optionally add `{"name": "Anka Native", "packages": ["dev.ankanative.android"]}` to the end of `supported_emulators.json` in the same folder. Then, for each `.nup` game, open its details (−) › Settings › Launch options › Override emulator › Anka Native (Standalone), or pick it for the whole Switch console. Use the Path command first: what `%ROM_URI%` holds was not checked, and a URI that carries no path is refused with a message. iiSU replaces its list when it downloads an update; add the entry again then. The file format comes from a third-party guide for iiSU 0.0.7.3, not from iiSU's own documentation.

## Pegasus

In a collection's `metadata.pegasus.txt`:

```
collection: Switch (Anka Native)
extensions: nup
launch: am start -n dev.ankanative.android/.Launch -e game "{file.path}"
```

`-a android.intent.action.VIEW -d "{file.uri}"` (Pegasus's own provider, `content://org.pegasus_frontend.android.files/ext/…`, read as the path under `/storage/emulated/0`) and `-d "{file.documenturi}"` (a SAF URI, with Pegasus's storage access) work too.

## LaunchBox

LaunchBox for Android has a "Custom Emulator (With Code)" entry whose launch code is `am start` arguments, with `{file.path}` and `{file.uri}`:

```
-n dev.ankanative.android/.Launch -e game {file.path}
```

## Beacon and Cocoon

Neither documents a way to add an emulator by its component or by `am start` arguments: both pick a "player app" from a list of emulators they know. If your version offers a custom player or custom launch arguments, use the component and the extra `game` with the file's path as above. Otherwise Anka Native can be used from them only once they add it to their lists; the component and the forms above are what they need.

## Anything else

Any frontend that runs `am start`, or builds an intent with a component and an extra or data, works with the same arguments. From a computer, with the phone on adb:

```bash
adb shell am start -n dev.ankanative.android/.Launch -e game /storage/emulated/0/ROMs/switch/game.nup
adb shell am start -n dev.ankanative.android/.Launch -e title_id 0400486024DEC000
```

## Opening a .nup from a file manager

A second alias, `<application ID>.Open`, shows the app in "Open with" for a file manager's `.nup`. It behaves as `Launch`: a game on Home starts, a new file asks first. That works when the file manager sends a path or a URI with one (see above).

## When the frontend says "Unable to find explicit activity class"

On Android 15, an explicit intent reaches an exported component that has intent filters only when the intent matches one of them, and an intent with no action matches none (adb's shell is exempt, so adb tests do not show it). Daijisho met both: its `-d {file.uri}` player sends `VIEW` with no MIME type, and its `-e game {file.path}` player sends no action. So the `Launch` alias has no intent filters at all, and takes every form. The "Open with" filter for file managers is on a second alias, `<application ID>.Open` (2026-11-04). If a frontend still gets this message, the log shows what it sent: `adb logcat | grep -E "AmStart args|START u0|frontend launch"` (the app logs the caller, the action and the extras' names of each request).

## Code

- `FrontendLaunch` (the aliases' activity): reads the intent, finds the card it names (`Frontend.target`), checks it as Play does (`ready`, read only), then starts `GameActivity` with the card's paths and the extra `frontend`, or hands Home the card or the new file under a one-time token (`handOff` / `take`). It draws nothing (a translucent activity in a task of its own, not in Recents).
- `MainActivity.fromFrontend` / `askFrontend`: Home's question, then Play (`launch`) or Add game (`gamePicked`). For a new file it first looks for a card of the same title and build (`GameList.matches`, which Add game would replace with no question) and asks to start that card instead; other dumps of the title are named in the question.
- `Frontend`: the plain-Java part (URIs, paths, the paths another app may name, cards, title IDs), checked on the JVM by `dm/tools/tests/test_android_frontend.py` (`FrontendJvmTest`, and `FrontendPlayOnly`, which checks that `FrontendLaunch` writes nothing and that the adb control extras are gated as before).
- `GameActivity.backToFrontend`: when the game ends, the app's task goes behind the frontend instead of showing Home.

## Status

- 2026-11-04, Honor phone, test app: over adb, the extra `game` (with `/sdcard`), `ACTION_VIEW` of a `file://` URI and of a SAF document URI, and the extra `title_id` each start the game straight away; a missing file shows its dialog. From Daijisho, on its Switch platform imported with the Anka Native players, both players (`-e game {file.path}` and `-a VIEW -d {file.uri}`) start the game.
- 2026-11-14 (play only; new files ask first; Pegasus's provider; URI strings in `game`): built and checked on the JVM, not yet on a phone. Every frontend entry above is untested with it: Daijisho's worked with the earlier version; ES-DE, iiSU, Pegasus, LaunchBox, Beacon and Cocoon were never tried. Review fixes of the same day (a frontend's file of the same title and build as a card is that card and is never added in its place; another dump of the title is named in Home's question; a folder of several games is refused; a path compared through its links; repeated `.` steps and other users' `Android/data` refused; the extra `game` shown at most 200 characters, without control characters, in the error): checked on the JVM and in a build, not on a phone.
