# dm/games/game.py's profile-key validation, on a synthetic profile: pso_gpu (the GPU architectures the app's offline pipeline archive is made for, beside pso_mtl4) takes, none and no others,
# and a profile without the key generates its header unchanged; romfs_check (a RomFS file line that tells a version's data apart) takes <path> <line> <expected | main>;
# host_env takes DM_<NAME>=<value> host switches, each once; detail_setting takes shown or hidden, and hidden empties the launcher's detail column (build.gradle detailOf, run by Groovy)
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
GAME = REPO / 'dm/games/game.py'
ID = '0100000000F0F000'
TSV = ('title_id\t' + ID + '\nname\tTest\nprogram_id\t' + ID + '\nmodules\tmain\nromfs_marker\tromfs.img\n'   # what a header needs beside the keys under test
       'mem_reserve\t0x100000\nmem_alias\t0x100000\nmem_stack\t0x100000\napp_pool\t0x100000\n')


def header(rows):
    """game.py header of a synthetic profile with these extra rows: (exit code, its output)"""
    with tempfile.TemporaryDirectory() as d:
        Path(d, ID).mkdir()
        (Path(d, ID) / 'game.tsv').write_text(TSV + rows)
        r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')],
                           env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
    return r.returncode, (r.stderr + r.stdout).strip()


class PsoGPU(unittest.TestCase):
    def test_valid_tokens(self):
        for rows in ('', 'pso_mtl4\ton\npso_gpu\tg18p\n', 'pso_mtl4\ton\npso_gpu\tapplegpu_g19p\n',   # without the key: the header is made as before, no message
                     'pso_mtl4\ton\npso_gpu\tg18p g19p\n', 'pso_mtl4\ton\npso_gpu\tg14s g15s g16s g17p\n'):
            rc, out = header(rows)
            self.assertEqual((rc, out), (0, ''), rows)

    def test_rejected_tokens(self):
        for rows in ('pso_gpu\tnone\n',                     # none is not a value: absence of the key says no archive of its own
                     'pso_mtl4\ton\npso_gpu\tg18p none\n', 'pso_gpu\tx g18p!\n', 'pso_gpu\tg14s g15s g16s g17p g18p\n',
                     'pso_gpu\tapplegpu_g19p,x\n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': pso_gpu takes 1-4 whitespace-separated GPU architectures', out, rows)


class RomfsCheck(unittest.TestCase):
    def test_valid_rows(self):
        for rows in ('romfs_check\tSystem/RegionLangMask.txt\t4\tmain\n', 'romfs_check\tSystem/RegionLangMask.txt\t2\tNormal\nromfs_check\ta/b.txt\t1\tJPja USen\n'):
            self.assertEqual(header(rows), (0, ''), rows)

    def test_rejected_rows(self):
        for rows in ('romfs_check\tSystem/RegionLangMask.txt\t4\n', 'romfs_check\t/abs.txt\t1\tmain\n', 'romfs_check\ta/../b.txt\t1\tmain\n',
                     'romfs_check\ta.txt\t0\tmain\n', 'romfs_check\ta.txt\tx\tmain\n', 'romfs_check\ta.txt\t1\ta|b\n', 'romfs_check\ta.txt\t1\t \n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': romfs_check takes <path in the RomFS> <line, from 1>', out, rows)



class HostEnv(unittest.TestCase):   # host_env: DM_<NAME>=<value> host switches a game starts with (main.c env_defaults)
    def test_valid_rows(self):
        for rows in ('', 'host_env\tDM_VK_PLACE_PART_MB=64\n', 'host_env\tDM_VK_PLACE_PART_MB=64 DM_VK_PSO_ASYNC=2\n', 'host_env\tDM_WINDOW_SIZE=1280x720\n'):
            self.assertEqual(header(rows), (0, ''), rows)

    def test_rejected_rows(self):
        for rows in ('host_env\tDM_VK_PSO_ASYNC\n', 'host_env\tVK_PSO_ASYNC=2\n', 'host_env\tdm_vk_pso_async=2\n', 'host_env\tDM_A=1 DM_A=2\n',
                     'host_env\tDM_FASTBOOT=1\n', 'host_env\tDM_A="x"\n', 'host_env\tDM_A=\n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': host_env takes DM_<NAME>=<value> host switches', out, rows)


def groovy():
    """java and the Groovy jar of the Gradle the launcher builds with (its wrapper's version), or None"""
    java = Path(os.environ['JAVA_HOME'], 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    props = (REPO / 'android/app/gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    ver = re.search(r'gradle-([\d.]+)-bin\.zip', props).group(1)
    home = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle')
    jars = sorted(home.glob(f'wrapper/dists/gradle-{ver}-bin/*/gradle-{ver}/lib/groovy-[0-9]*.jar'))
    return (str(java), str(jars[0])) if java and Path(java).is_file() and jars else None


DETAIL_ROWS = ('detail_level\tfull\tshown\tFull\ndetail_level\tless\tshown\tLess (made-up)\ndetail_level\tm_x\thidden\tMeasurement: x\n'
               'detail_param\tless\ta/b.bgyml\tKey\t=false\n')


class DetailSetting(unittest.TestCase):   # detail_setting <shown | hidden>: hidden empties the launcher's detail column (build.gradle detailOf), so no row and no level
    def test_valid_rows(self):
        for rows in ('', 'detail_setting\tshown\n', 'detail_setting\thidden\n'):
            self.assertEqual(header(DETAIL_ROWS + rows), (0, ''), rows)

    def test_rejected_rows(self):
        for rows in ('detail_setting\tmaybe\n', 'detail_setting\n', 'detail_setting\tshown\ndetail_setting\thidden\n', 'detail_setting\thidden\tx\n'):
            rc, out = header(DETAIL_ROWS + rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': detail_setting takes one row, shown or hidden', out, rows)

    def test_gradle_column(self):   # build.gradle's own tsvRows and detailOf, run by Gradle's Groovy on a made-up profile
        g = groovy()
        if not g: self.skipTest("no java or no Groovy jar of the Gradle wrapper's version")
        src = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        lines = [re.search(rf'(?m)^def {n} = .*$', src).group(0) for n in ('tsvRows', 'detailOf')]
        script = 'def repo = new File(args[0])\n' + '\n'.join(lines) + '\nprint detailOf(args[1])\n'
        want = 'full|shown|Full;less|shown|Less (made-up);m_x|hidden|Measurement: x'
        with tempfile.TemporaryDirectory() as d:
            Path(d, 'col.groovy').write_text(script, encoding='utf-8')
            for rows, col in (('', want), ('detail_setting\tshown\n', want), ('detail_setting\thidden\n', ''), ('', '')):
                prof = Path(d, 'dm/games', ID)
                prof.mkdir(parents=True, exist_ok=True)
                (prof / 'game.tsv').write_text(TSV + (DETAIL_ROWS + rows if col or rows else ''), encoding='utf-8')
                r = subprocess.run([g[0], '-cp', g[1], 'groovy.ui.GroovyMain', str(Path(d, 'col.groovy')), d, ID], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(r.stdout, col, rows)


if __name__ == '__main__':
    unittest.main()
