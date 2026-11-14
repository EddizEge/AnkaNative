"""The settings area's texts after they moved into strings_settings.xml (docs/i18n.md): android/app/src/jvmtest's SettingsTextsJvmTest renders them through Tr's
XML backend, with Profile, MemText and UpdateCheck and a stub BuildConfig, and each must be the English the code wrote before (byte for byte), so a text whose
spaces aapt2 would collapse (the two spaces of the driver lists, written as \\u0020) or whose plural, possessive or arguments changed is caught on the JVM. The
JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/SettingsTextsJvmTest.java'
# The launcher's texts (docs/i18n.md): Tr and its message classes compile with every launcher class, and the JVM reads the English from the app's resources
TR = [REPO / f'android/app/src/main/java/dev/nxnative/android/{n}.java' for n in ('Tr', 'Msg', 'LocalizedIOException', 'HomeLabels')]   # HomeLabels: home's labels that other areas' sentences name
TR_RES = f'-Dnx.tr.res={REPO / "android/app/src/main/res"}'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
}
'''
RUNS = ' has no game profile in this app, so it cannot run here. This app runs: 040074A01BF12000.'

# The English as the code built it before the move (the old concatenations, with the same sample arguments as the JVM test)
EXPECTED = {
    'missing_named': 'Mario (0100000000010000)' + RUNS,
    'missing_bare': '0100000000010000' + RUNS,
    'missing_ours': '<null>',
    'missing_msg': "That pack's game (0100000000010000)" + RUNS,
    'missing_msg_text': "That pack's game (0100000000010000)" + RUNS,
    'missing_msg_ours': 'null',
    'res_dynamic': "Dynamic (the game's own)",
    'mb': '117.7 MB',
    'gpu_zip_found': "Turnip_v32.zip" + "  (the app's gpu_drivers folder)",
    'driverfetch_asset': 'v26.3' + '\\n  ' + 'turnip.adpkg.zip' + '  (12.0 MB)',
    'env_lines_1': '1 line: DM_WATCH_STALL=25',   # was "1 line(s): …" (rephrased on purpose: a plural)
    'env_lines_2': '2 lines: A=1 B=2',
    'game_default': 'Default · 60 fps',
    'res_level_gone': "Dynamic (the game's own; the stored " + 'fixed:7' + ' is no longer offered)',
    'save_old': '/sdcard/Switch2/saves/X' + " (default: your save from an earlier version, beside the game. The game's next start copies it into the app's storage, "
                + '/data/saves/X' + ", and plays it from there; this folder is never changed)",
    'save_app': '/data/saves/X' + " (default: in the app's storage" + '' + "). Back up saves puts copies in " + '/sdcard/Switch2/backups/X'
                + ", on shared storage: they stay when the app is uninstalled.",
    'save_copied': '/data/saves/X' + " (default: in the app's storage" + "; copied from " + '/sdcard/Switch2/saves/X' + ", which is kept as it was"
                   + "). Back up saves puts copies in " + '/sdcard/Switch2/backups/X' + ", on shared storage: they stay when the app is uninstalled.",
    'no_games': "No games yet: + Add game first. Each game has its own folders (its card's ⋯ › Folders…).",
    'game_intro': 'For this game only, from its next start. Default follows Settings.',
    'fps_cap_default': 'Default (Settings: ' + '30 fps' + ')',
    'pack_default_app': '/data/packs/X' + " (default: in the app's storage)" + '\\n' + 'Shader library ' + 'vk7' + ': present.',
    'pack_set_ready': '/sdcard/pack' + '\\n\\n' + "It has this app's shader library (" + 'vk7' + "): ready to play.",
    'copy_message': "The folder you picked is empty. Your current save is in " + '/a' + ".\\n\\nCopy it into " + '/b' + "? The current folder stays as it is: nothing is deleted.",
    'gpu_remove_message': "Deleted: this driver's copy inside the app (" + '/data/gpu_drivers/T' + ").\\n\\nKept: the zip you installed it from, "
                          + "your games, packs and saves. The game uses the phone's own driver until you pick another.",
    'update_incomplete': 'the download is incomplete (' + '5' + ' of ' + '10' + ' bytes)',
    'net_http': 'GitHub answered HTTP ' + '404',
    'allow_message': 'To install the update, allow Anka Native to install apps ("Install unknown apps"), then come back.',
    'driverzip_too_many': 'more than ' + '256' + ' files: not a driver zip',
    'driverzip_missing_lib': 'meta.json names ' + 'libvulkan_freedreno.so' + ', which is not in the zip',
    'mem_app': 'App 4.3 GiB',
}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SettingsTexts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='settings_texts_'))
        (cls.tmp / 'stub').mkdir()
        (cls.tmp / 'stub/BuildConfig.java').write_text(STUB)
        srcs = [SRC / f'{n}.java' for n in ('Profile', 'MemText', 'UpdateCheck', 'ReleaseNotes')]
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), *map(str, srcs), *map(str, TR), str(TEST),
                        str(cls.tmp / 'stub/BuildConfig.java')], check=True, capture_output=True, text=True)
        r = subprocess.run(['java', TR_RES, '-Dstdout.encoding=UTF-8', '-cp', str(cls.tmp / 'classes'), 'dev.nxnative.android.SettingsTextsJvmTest'],
                           capture_output=True, text=True, encoding='utf-8')
        assert r.returncode == 0, r.stderr
        cls.got = dict(line.split('\t', 1) for line in r.stdout.splitlines())

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_every_case_ran(self):
        self.assertEqual(sorted(self.got), sorted(EXPECTED))

    def test_english_unchanged(self):
        for name, want in EXPECTED.items():
            with self.subTest(name):
                self.assertEqual(self.got.get(name), want)


if __name__ == '__main__':
    unittest.main()
