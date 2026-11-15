"""A game card's "Prepare again" (MainActivity's Manage sheet, Ondevice.java). The rc1 phone check (2026-11-15) found two faults:

- with several pack folders in the work folder, Prepare again went through Folders.packFor, which picked another folder (the best that fits, here one updated
  to the current library), found it ready, wrote a done job for it and moved the game card to it, which nobody asked for;
- after Manage › Forget, Prepare again found the forgotten job (by design: Forget deletes nothing and says "Picking the same dump again finds the job"), but
  remembered it before asking, so Cancel brought it back onto Home.

Now the card's own pack is the one checked and kept, and a job found again is remembered only once the user takes it.

The rc2 phone check (session-2026-11-14/rc2-phone-check.md A1, NIGHT.md 11-14) found a third: the dump's one job folder, <work>/ondevice, held an unfinished
test job of 2026-11-08 for another pack folder (<work>/ondevice/test-pack), so the card's automatic shader update of its own pack (<work>/android-pack) was
classed "another folder" and never ran, at every app start; and the card's Prepare again offered "Show the job", after which Home resumed that test job by
itself (paused for the battery counts as working). Now a job belongs to the pack folder it writes: a pick that names its pack takes only that pack's job, and
when the default folder holds another pack folder's unfinished job, its own job goes into the pack's own job folder beside it (JobPick.place); a job the user
only asked to see is not started by the app itself (Job.shown)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
RES = REPO / 'android/app/src/main/res'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/JobPickJvmTest.java'


def code(p):
    """A Java file's code, without its comments"""
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', p.read_text(encoding='utf-8'), flags=re.S)


def body(s, start, end='\n    }\n'):
    i = s.index(start)
    return s[i:s.index(end, i)]


class CardPack(unittest.TestCase):
    o = code(ONDEVICE / 'Ondevice.java')

    def test_card_passes_its_pack(self):
        m = code(MAIN / 'MainActivity.java')
        self.assertIn('Ondevice.prepareAgain(this, new File(g.gameDir), new File(g.packDir))', m)
        self.assertNotIn('Ondevice.prepare(this, new File(g.gameDir), true)', m)
        pa = body(self.o, 'static void prepareAgain(MainActivity a, File dump, File cardPack)')
        self.assertIn('panel.picked(dump, false, cardPack, true);', pa)

    def test_card_pack_is_the_one_checked(self):
        """The pack checked is the card's (its automatic update, its Prepare again), Folders.packFor's only for a pick without a card; an adb test's
        ondevice_pack (packOverride without again) is still never checked (JobPick.packToCheck, JobPickJvmTest)"""
        p = body(self.o, 'void picked(File dump, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('JobPick.Pack which = JobPick.packToCheck(card, again, packOverride != null);', p)
        self.assertIn('if (f.r.ok() && which != JobPick.Pack.NONE)', p)
        self.assertIn('f.packDir = which == JobPick.Pack.CARD ? packOverride : Folders.packFor(a, f.r.dump, f.r.titleId);', p)

    def test_folders_compared_resolved(self):
        """The review of work/rc1-fixes: java.io.File.equals compares the paths as written, so /sdcard/… and /storage/emulated/0/… were two folders. The
        comparisons are JobPick.place's now (JobPickJvmTest)"""
        c = body(self.o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertNotIn('.equals(j.pack())', c)
        self.assertNotIn('.equals(j.dump())', c)
        pk = code(ONDEVICE / 'JobPick.java')
        self.assertIn('samePlace(seen[k].pack, pack)', pk)
        self.assertIn('samePlace(seen[k].dump, dump)', pk)

    def test_new_job_names_its_folder(self):
        """A new whole job writes its pack into packOverride when there is one: the question names that folder, not the app's packs/<id>"""
        c = body(self.o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('packOverride != null ? packOverride : AppStorage.pack(a, r.titleId));', c)
        self.assertIn('if (packOverride != null) nj.pack(packOverride);', c)


class Forgotten(unittest.TestCase):
    def test_remembered_only_when_taken(self):
        o = code(ONDEVICE / 'Ondevice.java')
        c = body(o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('JobPick.Place place = f.place;', c)
        branch = c[c.index('if (place.found == JobPick.Found.TAKE) {'):c.index('if (place.found == JobPick.Found.OTHER_FOLDER) {')]
        take = branch[branch.index('Runnable take = () -> {'):branch.index('};')]
        self.assertIn('Job.remember(a, place.file);', take)
        self.assertIn('startService(j);', take)
        self.assertEqual(branch.count('Job.remember('), 1)   # only inside take: Cancel leaves a forgotten job forgotten
        self.assertIn('a.getString(R.string.prepare_button_resume), take);', branch)
        self.assertIn('if (start) take.run();', branch)

    def test_other_folder_remembered_only_when_asked(self):
        """The review of work/rc1-fixes: a job.json of another pack folder (now reachable from Prepare again, which passes the card's pack) was remembered at
        once, so a forgotten job came back on Home without asking. It is now said in a dialog whose "Show the job" remembers it"""
        o = code(ONDEVICE / 'Ondevice.java')
        c = body(o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        other = c[c.index('if (place.found == JobPick.Found.OTHER_FOLDER) {'):c.index('if (alive() && job != null')]
        self.assertEqual(other.count('Job.remember('), 1)
        show = other[other.index('Runnable showIt = () -> {'):other.index('};')]
        self.assertIn('Job.remember(a, place.file);', show)
        self.assertIn('a.getString(R.string.prepare_button_show_job), showIt);', other)
        self.assertNotIn('a.error(', other)
        for d in ('values', 'values-tr', 'values-pt-rBR', 'values-b+es+419'):
            t = (RES / d / 'strings_prepare.xml').read_text(encoding='utf-8')
            self.assertIn('name="prepare_button_show_job"', t, d)
            self.assertIn('name="prepare_other_folder_title"', t, d)

    def test_forget_says_a_pick_finds_it(self):
        """By design, and said so where the user forgets it"""
        t = (RES / 'values/strings_prepare.xml').read_text(encoding='utf-8')
        self.assertIn('Picking the same dump again finds the job.', t)


class OtherFolder(unittest.TestCase):
    """The rc2 phone check: an unfinished job of another pack folder in the dump's job folder blocked the card's own update and Prepare again. The decision is
    JobPick.place (JobPickJvmTest builds the owner's folders: the card's pack A needing the update, <work>/ondevice holding the 11-08 job of pack B)"""
    o = code(ONDEVICE / 'Ondevice.java')

    def test_pick_places_its_job_by_its_pack(self):
        p = body(self.o, 'void picked(File dump, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('File jobPack = packOverride != null ? packOverride : f.pack != null ? f.packDir : new File(AppStorage.packs(a), f.r.titleId);', p)
        self.assertIn('f.place = JobPick.place(f.r.work, f.r.dump, f.r.version, jobPack, packOverride != null, jf -> {', p)
        self.assertIn('JobPick.candidates(DumpCheck.workOf(dump, id), packOverride)', p)
        self.assertNotIn('Job.fileOf(', p)

    def test_new_jobs_go_where_place_says(self):
        c = body(self.o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('Job.create(r, AppStorage.pack(a, r.titleId), place.file);', c)
        self.assertIn('adopt(r, pd, false, false, place.file);', c)
        self.assertIn('adopt(r, pd, code, shaders, place.file);', c)
        self.assertNotIn('JobPick.existing(', c)
        a = body(self.o, 'void adopt(DumpCheck.Result r, File packDir, boolean code, boolean shaders, File jobFile)', '\n        }\n')
        self.assertIn('Job.adopt(r, packDir, code, shaders, jobFile);', a)
        j = code(ONDEVICE / 'Job.java')
        self.assertNotIn('fileOf(', j)   # no job.json path of its own: the one place chose
        self.assertIn('static Job create(DumpCheck.Result r, File appPack, File jobFile)', j)
        self.assertIn('return new Job(jobFile, o);', j)
        self.assertIn('new File(jobFile.getParentFile(), "fake-pack")', j)

    def test_rewrite_check_looks_in_the_packs_folder(self):
        """PackCheck.rewriting (a pack whose code a job is rewriting cannot start) finds that job in the pack's own job folder too"""
        r = body(code(MAIN / 'PackCheck.java'), 'static Job rewriting(Context c, File packDir, File game, String titleId)')
        self.assertIn('JobPick.candidates(DumpCheck.workOf(game, titleId), packDir)', r)

    def test_packer_env_of_the_dump(self):
        """The packer's test overrides (ondevice/env.txt) stay the dump's: read from the default job folder, then from a pack's own job folder"""
        st = body(code(ONDEVICE / 'PackService.java'), 'private String step(Job j, int i) throws IOException {')
        self.assertIn('File defaultOd = JobPick.defaultFolder(j.work());', st)
        self.assertIn('new File[] {new File(defaultOd, "env.txt"), new File(od, "env.txt")}', st)

    def test_shown_is_not_started(self):
        """"Show the job" puts a job on Home but does not start it: Home's open, a game's end, the job watch and alarm (restart), and a sticky or automatic
        start of the service leave a job the user only asked to see as it is (Job.onlyShown), until the user starts it"""
        c = body(self.o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        show = c[c.index('Runnable showIt = () -> {'):]
        self.assertIn('Job.shown(a, j);', show[:show.index('};')])
        self.assertIn('if (Job.onlyShown(a, job)) return;', body(self.o, 'void afterDeath()', '\n        }\n'))
        self.assertIn('if (Job.onlyShown(a, j))', body(code(ONDEVICE / 'PrepareGate.java'), 'static void afterGame(Activity a)'))
        self.assertIn('if (Job.onlyShown(c, j))', body(code(ONDEVICE / 'JobWatch.java'), 'static void restart(Context c, Job j, String why)'))
        self.assertIn('if (go && (intent == null || auto) && Job.onlyShown(this, j)) {', code(ONDEVICE / 'PackService.java'))
        j = code(ONDEVICE / 'Job.java')
        self.assertIn('JobPick.shownKey(j.file, j.updated())', body(j, 'static boolean onlyShown(Context c, Job j)'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Decide(unittest.TestCase):
    def test_decisions(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(d / 'classes'), str(ONDEVICE / 'JobPick.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.JobPickJvmTest', str(d)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


if __name__ == '__main__':
    unittest.main()
