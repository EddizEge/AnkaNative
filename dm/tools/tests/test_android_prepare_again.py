"""A game card's "Prepare again" (MainActivity's Manage sheet, Ondevice.java). The rc1 phone check (2026-11-15) found two faults:

- with several pack folders in the work folder, Prepare again went through Folders.packFor, which picked another folder (the best that fits, here one updated
  to the current library), found it ready, wrote a done job for it and moved the game card to it, which nobody asked for;
- after Manage › Forget, Prepare again found the forgotten job (by design: Forget deletes nothing and says "Picking the same dump again finds the job"), but
  remembered it before asking, so Cancel brought it back onto Home.

Now the card's own pack is the one checked and kept, and a job found again is remembered only once the user takes it."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
RES = REPO / 'android/app/src/main/res'


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
        ondevice_pack (packOverride without again) is still never checked"""
        p = body(self.o, 'void picked(File dump, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('boolean cardPack = card || again && packOverride != null;', p)
        self.assertIn('if (f.r.ok() && (packOverride == null || cardPack))', p)
        self.assertIn('f.packDir = cardPack ? packOverride : Folders.packFor(a, f.r.dump, f.r.titleId);', p)

    def test_new_job_names_its_folder(self):
        """A new whole job writes its pack into packOverride when there is one: the question names that folder, not the app's packs/<id>"""
        c = body(self.o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        self.assertIn('packOverride != null ? packOverride : AppStorage.pack(a, r.titleId));', c)
        self.assertIn('if (packOverride != null) j.pack(packOverride);', c)


class Forgotten(unittest.TestCase):
    def test_remembered_only_when_taken(self):
        o = code(ONDEVICE / 'Ondevice.java')
        c = body(o, 'void checked(Found f, boolean start, File packOverride, boolean again, boolean card)', '\n        }\n')
        branch = c[c.index('if (same && ('):c.index('if (!s.equals("done") && !s.isEmpty())')]
        take = branch[branch.index('Runnable take = () -> {'):branch.index('};')]
        self.assertIn('Job.remember(a, f.existing);', take)
        self.assertIn('startService(j);', take)
        self.assertEqual(branch.count('Job.remember('), 1)   # only inside take: Cancel leaves a forgotten job forgotten
        self.assertIn('a.getString(R.string.prepare_button_resume), take);', branch)
        self.assertIn('if (start) take.run();', branch)

    def test_forget_says_a_pick_finds_it(self):
        """By design, and said so where the user forgets it"""
        t = (RES / 'values/strings_prepare.xml').read_text(encoding='utf-8')
        self.assertIn('Picking the same dump again finds the job.', t)


if __name__ == '__main__':
    unittest.main()
