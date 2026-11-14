"""The Android launcher's emulator-frontend entry (FrontendLaunch, android/FRONTENDS.md): what Frontend.java reads from a frontend's intent (file:// URIs, provider
URIs that carry a /storage path, the shared storage's aliases, the paths another app may name, the card a request names, title IDs, the one-time token that
hands Home a game) and PlayRule (the one decision of Home's Play and of a frontend's request), on the JVM with android/app/src/jvmtest's FrontendJvmTest. Also
checks that the manifest exports only the alias, and the alias by the application ID, that the play intent writes nothing of its own, that it and Play take
the same decision, that a card reaches Play with no question and a new file only Add game's question, and that Home takes a frontend's game only by
FrontendLaunch's token (FrontendPlayOnly). The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/FrontendJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException')]
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'
MANIFEST = REPO / 'android/app/src/main/AndroidManifest.xml'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class FrontendJvm(unittest.TestCase):
    def test_frontend_paths(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', d, str(SRC / 'Frontend.java'), str(SRC / 'PlayRule.java'), *map(str, TR), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', TR_RES, '-cp', d, 'dev.nxnative.android.FrontendJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


class FrontendManifest(unittest.TestCase):
    def test_aliases(self):
        m = MANIFEST.read_text()
        act = re.search(r'<activity\s[^>]*android:name="\.FrontendLaunch"[^>]*>', m)
        self.assertIsNotNone(act)
        self.assertIn('android:exported="false"', act.group(0))
        # Android 15 checks an explicit intent against an exported component's filters when it has any, and never matches one with no action (Daijisho's
        # -e game {file.path}): the frontends' alias must have none
        launch = re.search(r'<activity-alias\s[^>]*?android:name="\$\{applicationId\}\.Launch"[^>]*?(/>|>)', m)
        self.assertIsNotNone(launch)
        self.assertEqual(launch.group(1), '/>', 'the Launch alias must have no intent filters')
        self.assertIn('android:targetActivity=".FrontendLaunch"', launch.group(0))
        self.assertIn('android:exported="true"', launch.group(0))
        op = re.search(r'<activity-alias\s[^>]*?android:name="\$\{applicationId\}\.Open"[^>]*>(.*?)</activity-alias>', m, re.S)
        self.assertIsNotNone(op)
        self.assertIn('pathPattern', op.group(1))


def code(name):
    """A Java file's code, without its comments"""
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', (SRC / name).read_text(), flags=re.S)


def body(src, signature):
    """The body of the method that starts with signature (four spaces in, as the launcher's classes are written)"""
    m = re.search(re.escape(signature) + r'(.*?)\n    \}\n', src, re.S)
    assert m, signature
    return m.group(1)


class FrontendPlayOnly(unittest.TestCase):
    """The play intent only does what the card's Play does, or hands Home a game (FRONTENDS.md, "What another app can do"): it never writes a pack, a driver,
    a setting or a save of its own, and Home takes a frontend's game only from FrontendLaunch, by its one-time token (MainActivity is exported)."""

    def test_launch_writes_nothing(self):
        src = re.sub(r'/\*.*?\*/|//[^\n]*', '', (SRC / 'FrontendLaunch.java').read_text(), flags=re.S)   # code only, not comments
        for call in ('Game.put', 'Game.save', 'Game.touch', 'Prefs.', 'GameActivity', 'putExtra("frontend"', 'Ondevice.', 'GpuDriver.', 'PackUi.fromIntent', 'SaveUi.activity', 'Folders.pickedPack',
                     'Folders.packFor', 'setResult', 'getContentResolver', 'FLAG_GRANT', 'getParcelableExtra', 'Intent.parseUri', 'setComponent', 'setClassName'):
            self.assertFalse(call in src, f'FrontendLaunch must not use {call}')

    def test_play_start_writes_only_what_play_does(self):
        """FrontendLaunch starts a game through Play.start / Play.go, so what they call is what another app can cause with no question: exactly the calls
        below (the automatic shader update of the card's own pack, the card marked played last, the legacy toast, GameActivity with the card's paths). Any other
        call added there (Ondevice.prepare, Game.put, Prefs., a driver…) fails here and must be weighed for FRONTENDS.md, "What another app can do" first"""
        play = code('Play.java')
        start = body(play, 'static void start(Activity a, Game g, PlayRule.Kind k, boolean frontend) {')
        go = body(play, 'static void go(Activity a, Game g, boolean frontend) {')
        calls = sorted(set(re.findall(r'\b([A-Z]\w*\.\w+)\(', start + go)) | set(re.findall(r'\b(a\.\w+|i\.\w+)\(', start + go)))
        self.assertEqual(calls, ['Game.touch', 'Ondevice.autoUpdate', 'Ondevice.isDump', 'ShaderLibs.legacyWarning', 'Toast.makeText', 'a.getApplicationContext',
                                 'a.startActivity', 'i.addFlags'], 'Play.start / Play.go call only what Play did without asking')
        self.assertIn('Ondevice.autoUpdate(gd, new File(g.packDir), g.titleId);', start, 'only the card\'s own pack is updated')
        self.assertIn('new Intent(a, GameActivity.class).putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir).putExtra("pack", g.packDir);', go,
                      'the game starts with the card\'s own paths')

    def test_auto_update_waits_for_a_running_job(self):
        """Play.start's automatic shader update from a frontend finds Home stopped (behind the frontend or the game), its panel unbound from the service, so
        alive() cannot see a running Prepare: job.json decides, or a second job would be written and remembered that the service never runs"""
        od = re.sub(r'/\*.*?\*/|//[^\n]*', '', (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java').read_text(), flags=re.S)
        au = body(od, 'static void autoUpdate(File dump, File packDir, String titleId) {')
        self.assertIn('if (!panel.resumed) {\n            panel.load();\n            if (panel.job != null && Panel.mayRun(panel.job)) return;\n        }', au)
        self.assertTrue(au.rstrip().endswith('autoUpdate(panel.a, dump, packDir, titleId);'), 'the guarded call is the last thing it does')
        may = re.search(r'static boolean mayRun\(Job j\) \{(.*?)\n        \}', od, re.S)
        self.assertIsNotNone(may)
        self.assertIn('"running".equals(s) || "paused".equals(s) && !"user".equals(j.reason()) && !"timeout".equals(j.reason())', may.group(1),
                      'a job paused by the service\'s own policy still has its worker')

    def test_home_takes_only_the_token(self):
        main = (SRC / 'MainActivity.java').read_text()
        self.assertNotIn('"frontend_game"', main, 'Home must not take a game path from an extra another app can send')
        self.assertIn('FrontendLaunch.take(i.getStringExtra("frontend_token"))', main)
        self.assertIn('return Frontend.take(token);', (SRC / 'FrontendLaunch.java').read_text(), 'the token is Frontend\'s, checked on the JVM')
        self.assertNotIn('handOff', code('MainActivity.java'), 'only FrontendLaunch hands Home a game')
        # the adb control extras stay exactly as gated by issue #7 (Prefs.adbControl)
        m = re.search(r'CONTROL_EXTRAS = \{([^}]*)\}', main)
        self.assertIsNotNone(m)
        self.assertEqual(re.findall(r'"([^"]+)"', m.group(1)),
                         ['game', 'add_game', 'pack', 'save', 'run', 'ondevice_dump', 'ondevice_start', 'ondevice_pause', 'ondevice_pack', 'show_when_locked',
                          'gpu_driver_zip', 'gpu_driver', 'pack_title', 'pack_export', 'pack_import'])
        self.assertIn('if (!Prefs.adbControl(this))', main)


    def test_one_decision(self):
        """Play and a frontend's request decide "starts straight away" with the same function (Play.check → PlayRule.decide), so they cannot drift (2026-11-14:
        FrontendLaunch's own ready() refused the older library Play starts, and iiSU's start asked "Start …?"); a straight verdict starts through Play.start, which
        also runs Play's automatic shader update and the legacy warning"""
        launch = code('FrontendLaunch.java')
        self.assertNotIn('ready(', launch, 'FrontendLaunch keeps no rule of its own')
        # the verdict is Play.check's alone, and what follows from it is Frontend.route's (checked on the JVM for every PlayRule.Kind)
        self.assertEqual(launch.count('Play.check('), 1)
        self.assertIn('PlayRule.Verdict v = card != null ? Play.check(this, card) : null;', launch)
        self.assertIn('switch (Frontend.route(card != null, tid != null, v != null ? v.kind : null)) {', launch)
        self.assertNotIn('straight(', launch, 'only Frontend.route reads the verdict')
        self.assertNotIn('Kind.', launch, 'only Frontend.route reads the verdict')
        self.assertEqual(re.findall(r'\bv\.\w+', launch), ['v.kind'] * 4, 'the verdict is read only for the route, the log and Play.start')
        start = re.search(r'case START:(.*?)case PLAY_ON_HOME:', launch, re.S).group(1)
        self.assertEqual([l.strip() for l in start.strip().splitlines()][1:], ['Play.start(this, card, v.kind, true);', 'finish();', 'return;'],
                         'a straight verdict starts the game, with nothing in front (the first line is the log)')
        self.assertTrue(start.strip().startswith('android.util.Log.i('))
        # nothing of the card's pack, profile or busy state is looked at on its own (2026-11-14: FrontendLaunch's own pack checks refused what Play starts)
        for own in ('packDir', 'libraryProblem', 'PackCheck.', 'Profile.', 'SaveUi.', 'PackUi.', 'ShaderLibs.', 'romfs', 'Ondevice.'):
            self.assertNotIn(own, launch, f'FrontendLaunch keeps no check of its own: {own}')
        self.assertEqual(re.findall(r'\bPack\.\w+', launch), ['Pack.titleIdOf'], 'Pack is read only for the file\'s title (sameBuild)')
        main = code('MainActivity.java')
        la = body(main, 'void launch(Game g, boolean frontend) {')
        self.assertTrue(la.lstrip().startswith('PlayRule.Verdict v = Play.check(this, g);\n        if (v.kind.straight()) { Play.start(this, g, v.kind, frontend); return; }\n'),
                        'Play starts what PlayRule starts, before anything else')
        self.assertIn('case NO_LIBRARY:\n                break;\n            default:', la, 'only NO_LIBRARY reaches the shaders dialog')
        for gone in ('Pack.read(', 'PackCheck.', 'Pack.libraryProblem', 'playableLibrary', 'legacyLibrary', 'SaveUi.busy', 'PackUi.busy', 'Profile.missing', 'startActivity'):
            self.assertNotIn(gone, la, f'Play decides only through PlayRule: {gone}')
        self.assertIn('void launch(Game g) { launch(g, false); }', main)
        play = code('Play.java')
        self.assertIn('return PlayRule.decide(facts(c, g));', play)
        # the library facts are Play's own calls (Pack.playableLibrary of an older-input library is checked on the JVM by PackFindJvmTest)
        for fact in ('@Override public Msg libraryProblem() { return Pack.libraryProblemMsg(pd, g.titleId); }',
                     '@Override public boolean playableLibrary() { return Pack.playableLibrary(pd, g.titleId, Ondevice.isDump(gd)) != null; }',
                     '@Override public boolean legacyLibrary() { return Pack.legacyLibrary(pd, g.titleId) != null; }'):
            self.assertIn(fact, play)
        self.assertIn('Ondevice.autoUpdate(', body(play, 'static void start(Activity a, Game g, PlayRule.Kind k, boolean frontend) {'))
        self.assertIn('putExtra("frontend", true)', body(play, 'static void go(Activity a, Game g, boolean frontend) {'))

    def test_card_has_no_question(self):
        """A card a frontend names that Play cannot start straight away reaches Play's own steps on Home, with no question in front (the owner, 2026-11-14:
        a game started from a frontend must not ask "start?" again); the same game build as a card is that card, also with no question"""
        main = code('MainActivity.java')
        ff = body(main, 'private void fromFrontend(File game, boolean card) {')
        self.assertIn('for (Game x : games) if (GameList.samePath(x.gameDir, game.getPath())) { launch(x, true); return; }', ff)
        self.assertIn('if (s != null) { launch(s, true); return; }', ff)
        self.assertEqual(re.findall(r'\b\w+\((?:x|s), ', ff), ['launch(x, ', 'launch(s, '], 'a card goes straight to Play, through no helper of its own')
        self.assertNotIn('AlertDialog', ff)
        for key in ('home_frontend_start_title', 'home_frontend_start_body', 'home_frontend_same_build_body'):
            self.assertNotIn(key, main)
            for res in sorted((REPO / 'android/app/src/main/res').glob('values*/strings_home.xml')):
                self.assertNotIn(f'name="{key}"', res.read_text(), f'{res}: {key} is unused')

    def test_new_file_still_asks(self):
        """A file that is no card changes Home when added: Home asks before Add game's steps (askAdd), and only its button runs them"""
        main = code('MainActivity.java')
        self.assertIn('askAdd(f, others);', body(main, 'private void fromFrontend(File game, boolean card) {'))
        ask = body(main, 'private void askAdd(File file, List<Game> others) {')
        self.assertIn('R.string.home_frontend_add_title', ask)
        self.assertIn('(x, w) -> gamePicked(file)', ask)
        self.assertIn('filterTaps(d);', ask)
        self.assertIn('setFilterTouchesWhenObscured(true)', body(main, 'private static void filterTaps(AlertDialog d) {'))
        self.assertEqual(main.count('askAdd('), 2, 'askAdd is fromFrontend\'s only')

    def test_play_dialogs_filter_overlay_taps(self):
        """Play's dialogs a frontend's request opens (Prepare, Prepare again, pick pack, Start anyway) keep askAdd's guard: their buttons ignore taps through
        another app's overlay, so no hidden tap gets past the first dialog that leads to a pack write (the "Start …?" question had it before 2026-11-14)"""
        main = code('MainActivity.java')
        for name, sig in (('launch', 'void launch(Game g, boolean frontend) {'), ('packProblem', 'private void packProblem(Game g, String why, Runnable pickPack, boolean frontend) {')):
            b = body(main, sig)
            self.assertNotIn('.show()', b, f'{name}: every dialog through showPlay')
        la = body(main, 'void launch(Game g, boolean frontend) {')
        self.assertEqual(len(re.findall(r'showPlay\(', la)), 2)
        self.assertEqual(la.count('packProblem('), la.count('pickPack, frontend);'), 'packProblem is told about the frontend')
        self.assertIn('showPlay(b, frontend);', body(main, 'private void packProblem(Game g, String why, Runnable pickPack, boolean frontend) {'))
        self.assertIn('if (frontend) d.setOnShowListener(x -> filterTaps(d));', body(main, 'private static void showPlay(AlertDialog.Builder b, boolean frontend) {'))

    def test_new_file_never_replaces_a_card(self):
        """Add game replaces a card of the same title and build with no question (GameList.matches): a frontend's new file must be checked against the
        cards first and, when it is such a copy, only ever start that card (review of work/frontend-launch, MainActivity.fromFrontend)."""
        main = re.sub(r'/\*.*?\*/|//[^\n]*', '', (SRC / 'MainActivity.java').read_text(), flags=re.S)
        m = re.search(r'private void fromFrontend\(File game, boolean card\) \{(.*?)\n    \}\n', main, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)
        self.assertIn('GameList.matches(', body)
        self.assertIn('GameList.others(', body)
        self.assertIn('PackFind.gamesIn(', body, 'a folder of several games must not reach Add game from a frontend')
        self.assertNotIn('gamePicked(', body, 'Add game runs only from the question, after the cards were checked')
        launch = (SRC / 'FrontendLaunch.java').read_text()
        self.assertIn('GameList.canon(g.gameDir)', launch, 'the card that holds the file is found through links, as Home finds it')
        self.assertIn('Frontend.shown(i.getStringExtra("game"))', launch, 'the caller\'s text is shown cut and without control characters')


if __name__ == '__main__':
    unittest.main()
