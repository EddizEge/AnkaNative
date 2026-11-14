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

1. **The game is on Home**: the app does what Play on that card does, as if you tapped it, with no question of its own. "On Home" means a card with this title ID (the extra `title_id`), or the card that holds this very file (by any of its paths, or through a symbolic link), or else the card of the same title and game build as this file (another copy of the card's `.nup`, or the folder it was made from). The game always starts with the card's own game and pack, so the frontend picks only which of your games plays; a file of the same build as a card is never added in its place.
   - **When Play starts the game without a dialog**, it starts at once and nothing of the launcher is shown: a pack that reads and fits (this game build, finished code) with this app's shader library, or with an older library Play plays while the automatic shader update makes this version's, or with a library from before versioned updates (with Play's warning toast). Play and the frontend's request take this decision in one place (`PlayRule.decide`), so they cannot differ.
   - **When Play has to show something first** (no pack or one that does not read, a placeholder pack, a pack of another game build or title, no shaders it can play with, a save backup or restore or a pack export or import working, a game this app has no host for), Home opens and Play's own dialog for it shows, as when you tap Play: it says what is missing and offers the fix. There is no "Start …?" question in front of it. The buttons of these dialogs ignore taps that land through another app's overlay when a frontend opened them.
   - Without All files access nothing is looked up: a message says the app needs it, and Home opens without the game (see above).
   - When the game started this way stops (the in-game panel › Stop the game, or Back on a failed start), the frontend comes back, also when it was started from Play's dialog ("Start anyway").
2. **The file is not on Home**: Home opens and asks "Add this game?", with the file's path. Adding a game changes Home, so this question stays. When your list has another dump of the same game (another build), the question names it with its pack, and Add game then asks whether the new file replaces it or both stay. Add then takes Add game's own steps: the game is checked, its pack is looked for as Add game looks for it, and it is added (or Prepare is offered). Nothing is added if you cancel. Once it is on Home, the frontend starts it as in step 1 from then on. The file must be one game: a folder that holds several is refused (Add game in the app adds those).
3. **A game is already running**: the running game comes to the front. When the frontend asked for another game, it says so: one game runs at a time; stop it first.

A frontend never chooses the pack, the save folder, the GPU driver or any setting: the extras that do that are adb's control commands ([TESTING.md](TESTING.md)), which play and release builds take only while Settings › ADB control (debug) is on (issue #7), and only on Home's own component (`MainActivity`), never on `Launch`.

## What another app can do

`Launch` and `Open` are exported without a permission, so every app on the phone can send them anything, which is what lets every frontend use them. What any app can do with them, and why that is acceptable:

- **Start a game that is on Home**, as tapping its Play button does, and with it what Play itself does without asking: today that is marking the card as played last (its place in the list) and, when the card is a dump whose pack has an older shader library the profile's update manifest knows, the automatic shader update of that card's pack (`Ondevice.autoUpdate`, a background job that writes only that pack's new shader library, as it also starts when Home shows the card). The update starts only while Home is running in the app and no Prepare job is running; else (the frontend's request started the app, or another game is being prepared) it starts when Home next shows the card. Whichever started it, a Prepare or update does not work while the game runs: the game pauses it first and it goes on by itself when the game ends ([A game and Prepare](README.md#the-launcher)). It runs the code of a pack you added, from the paths on its card; the request cannot name another pack, game folder, save or setting. At worst an app starts your game when you did not ask (annoying, not harmful); if a game is running, it only brings it to the front. Anything else Play does (Prepare, Prepare again, picking another pack, "Start anyway" with shaders missing) waits for your tap in Play's dialog, as on Home, and those buttons ignore taps that land through another app's overlay.
- **Open Home with a question** ("Add this game?"). Nothing happens unless you tap Add, and that button ignores taps that land through another app's overlay. Read the path in the question: it is the file the other app named.
- **Nothing else.** The play intent never adds, replaces or removes a card, never picks or writes a pack (beyond the automatic shader update above), never starts Prepare, never installs or picks a driver, never changes a setting, `env.txt` or a save, never passes its own paths to the game, and never returns anything to the caller (no result, no URI permission passed on). Home takes a frontend's game only from `FrontendLaunch` itself, by a one-time token kept in the app's process (`Frontend.handOff` / `take`: only the newest token, once); an extra another app sends Home cannot name one, so no other app can make Home run Play. A path out of shared storage (`..`, `/data/…`, a link to such a place) is refused before anything reads it.

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

iiSU takes its consoles and their emulators from one file, `emuladores.json`, which you import in iiSU's own settings: Settings › Updates › **Import emuladores.json**. You never need to put files into iiSU's folders yourself. Each console has an `"emulators"` list, and each emulator has commands in the form `<package>/<activity> <am start arguments>`, in which iiSU fills in the game's file.

Anka Native gets a console of its own, Nintendo Switch 2, not an entry in iiSU's `"switch"` console. Add this object to the `"consoles"` array of your `emuladores.json`. Its place in the array does not matter (put a comma between it and its neighbours); its `shortName` must be one no other console has, as iiSU keeps only the first console of a `shortName`. Leave the rest of your list as it is: iiSU takes the imported file as its whole list, so it must still hold every console you use.

```json
{
  "shortName": "switch2",
  "longName": "Nintendo Switch 2",
  "alternativeNames": ["Switch 2", "NS2"],
  "releaseYear": "2025",
  "releaseDate": "2025-06-05",
  "manufacturer": "Nintendo",
  "retroAchievementsId": "NA",
  "romExtensions": [".nup", ".NUP"],
  "emulators": [
    {
      "id": "ANKA-NATIVE",
      "name": "Anka Native",
      "routeType": "path",
      "commands": [
        {
          "description": "Anka Native",
          "command": "dev.ankanative.android/.Launch -e game %ROM_PATH%"
        }
      ],
      "packages": ["dev.ankanative.android"]
    }
  ]
}
```

For the play app, write `dev.ankanative.android.play` in both places. Import the file, then add the console: in iiSU's Add console, Nintendo Switch 2 is not on the first page, because that page's quick groups (Nintendo among them) are fixed lists inside iiSU. Find it with the search box (`switch`, `Switch 2`) or under Sort by › Alphabetical, or Sort by › Manufacturer › Nintendo. Pick Anka Native as its emulator and the folder that holds your `.nup` files as its game folder.

iiSU sends the file's path in the extra `game` (`routeType` `"path"` with `%ROM_PATH%`). It splits the command into its arguments before it fills in the path, so a path with spaces and brackets reaches the app whole; `%ROM%` gives the same value under `"path"`. When an iiSU update or iiSU's own online list update brings a newer list, it replaces yours: import your file again then.

The console's icon, title and background are your own choice in iiSU's screens (Console Options › Manage Media › Local, or a Platform Pack); Anka Native ships no artwork. iiSU 0.1.6.1 has no screen that gives a new console a border, so its games show without one.

What iiSU's request then does in Anka Native is the same as for every frontend ([What happens](#what-happens)): a game whose card Play would start straight away starts at once, with no "Start …?" question, and iiSU comes back when it stops; a game that needs a step first opens Home with Play's own dialog for it, and a file that is not on Home asks "Add this game?".

Tested on the owner's phone (Android 16, iiSU 0.1.6.1, with the test app's package name in place of `dev.ankanative.android`): the import in iiSU's settings, this console with `routeType` `"path"` and `-e game %ROM_PATH%` starting the game from iiSU, a path with spaces and brackets arriving whole, and `%ROM%` giving the same value. Read in iiSU 0.1.6.1's code, not tried on a phone: that `shortName` decides which of two consoles is kept, that the first page of Add console lists only its fixed groups and where else a new console is listed, that iiSU's own list updates replace the imported one, and that no screen adds a border for a new console.

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

A second alias, `<application ID>.Open`, shows the app in "Open with" for a file manager's `.nup`. It behaves as `Launch`: a game on Home is Play's, a new file asks first. That works when the file manager sends a path or a URI with one (see above).

## When the frontend says "Unable to find explicit activity class"

On Android 15, an explicit intent reaches an exported component that has intent filters only when the intent matches one of them, and an intent with no action matches none (adb's shell is exempt, so adb tests do not show it). Daijisho met both: its `-d {file.uri}` player sends `VIEW` with no MIME type, and its `-e game {file.path}` player sends no action. So the `Launch` alias has no intent filters at all, and takes every form. The "Open with" filter for file managers is on a second alias, `<application ID>.Open` (2026-11-04). If a frontend still gets this message, the log shows what it sent: `adb logcat | grep -E "AmStart args|START u0|frontend launch"` (the app logs the caller, the action and the extras' names of each request).

## Code

- `FrontendLaunch` (the aliases' activity): reads the intent, finds the card it names (`Frontend.target`), takes Play's decision for it (`Play.check`) and what follows from it (`Frontend.route`), then starts the game as Play does (`Play.start`, with the card's paths and the extra `frontend`), or hands Home the card or the new file under a one-time token (`Frontend.handOff` / `take`). It draws nothing (a translucent activity in a task of its own, not in Recents).
- `PlayRule.decide`: the one decision of Play and of a frontend's request: which of Play's steps comes first, or which library the game starts with (`straight`). `Play.facts` reads the card's facts for it; `Play.start` / `go` start the game.
- `MainActivity.fromFrontend`: Play for a card (`launch(card, true)`, no question), or for a new file the question (`askAdd`) before Add game (`gamePicked`). For a new file it first looks for a card of the same title and build (`GameList.matches`, which Add game would replace with no question) and plays that card instead; other dumps of the title are named in the question.
- `Frontend`: the plain-Java part (URIs, paths, the paths another app may name, cards, title IDs, the token), checked on the JVM with `PlayRule` by `dm/tools/tests/test_android_frontend.py` (`FrontendJvmTest`, and `FrontendPlayOnly`, which checks that `FrontendLaunch` writes nothing of its own, that it and Play take the one decision, that a card gets no question and a new file still does, and that the adb control extras are gated as before).
- `GameActivity.backToFrontend`: when the game ends, the app's task goes behind the frontend instead of showing Home.

## Status

- 2026-11-04, Honor phone, test app: over adb, the extra `game` (with `/sdcard`), `ACTION_VIEW` of a `file://` URI and of a SAF document URI, and the extra `title_id` each start the game straight away; a missing file shows its dialog. From Daijisho, on its Switch platform imported with the Anka Native players, both players (`-e game {file.path}` and `-a VIEW -d {file.uri}`) start the game.
- 2026-11-14 (play only; new files ask first; Pegasus's provider; URI strings in `game`): built and checked on the JVM, not yet on a phone. Every frontend entry above is untested with it: Daijisho's worked with the earlier version; ES-DE, iiSU, Pegasus, LaunchBox, Beacon and Cocoon were never tried. Review fixes of the same day (a frontend's file of the same title and build as a card is that card and is never added in its place; another dump of the title is named in Home's question; a folder of several games is refused; a path compared through its links; repeated `.` steps and other users' `Android/data` refused; the extra `game` shown at most 200 characters, without control characters, in the error): checked on the JVM and in a build, not on a phone.
- 2026-11-14, owner's phone, iiSU 0.1.6.1, test app of `work/big` 7a33169: iiSU's `-e game <path>` reached `Launch` and found the card, but the start asked "Start …?" because the pack had this version's library made from older shader inputs, which Play starts without asking and the frontend's own check did not; the game started only after Play was tapped, from Home, so without the extra that brings the frontend back when it stops. Changed the same day: the request takes Play's own decision (`PlayRule`), starts such a game at once, and a card that needs Play's steps gets them with no question in front. Checked on the JVM and in a build, not yet on a phone.
- 2026-11-14, owner's phone (Android 16), iiSU 0.1.6.1, test app (`dev.ankanative.android.oot`): the form of the [iiSU](#iisu) entry, a console of its own (`switch2`) imported in iiSU's settings, with `routeType` `"path"` and `-e game %ROM_PATH%`, started the game from iiSU; a path with spaces and brackets arrived whole, and `%ROM%` gave the same value. The earlier entry (an emulator inside iiSU's `"switch"` console, a URI command, `supported_emulators.json`) is gone. The start with no question of the same day's change is not yet tried with iiSU on a phone.
