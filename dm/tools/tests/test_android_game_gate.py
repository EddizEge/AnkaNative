"""A game and this app's Prepare job (GameGate.java, PrepareGate.java, PackService.java, GameActivity.java, Ondevice.java, MainActivity.java). The phone test
of 2026-11-14: a game started beside a running Prepare (from a frontend) or beside the automatic shader update (Play, which stays on during it) ran a 12 GB
phone out of memory, and about 30 s after the game was in, lmkd ended the job's process and the user's background apps; the game card said "Ready · updating
shaders" when no update was queued. Now a game about to start pauses the job first (reason "game"), a process that has run a tool ends instead of waiting
beside the game, and the job goes on by itself when the game's process is gone; the card says how its pack's update stands. The decisions run on the JVM
(GameGateJvmTest); the rest checks that the game, the service and Home use them as written. Skipped without a JDK (the JVM part)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GameGateJvmTest.java'
RES = REPO / 'android/app/src/main/res'


def code(p):
    """A Java file's code, without its comments"""
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', p.read_text(encoding='utf-8'), flags=re.S)


def body(s, start, end='\n    }\n'):
    i = s.index(start)
    return s[i:s.index(end, i)]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Decide(unittest.TestCase):
    def test_decisions(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(d / 'classes'), str(MAIN / 'GameGate.java'), str(ONDEVICE / 'JobForm.java'), str(ONDEVICE / 'JobDeath.java'), str(TEST)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.GameGateJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('all ok', r.stdout)


class Game(unittest.TestCase):
    g = code(MAIN / 'GameActivity.java')
    gate = code(ONDEVICE / 'PrepareGate.java')

    def test_pauses_before_the_host(self):
        """The game asks before the readiness check and before the host starts; start() runs again from the gate"""
        st = body(self.g, 'private void start() {')
        self.assertLess(st.index('if (prepareHold(name)) return;'), st.index('if (readinessHold(id)) return;'))
        self.assertLess(st.index('if (prepareHold(name)) return;'), st.index('Native.start('))
        hold = body(self.g, 'private boolean prepareHold(String name) {')
        self.assertIn('PrepareGate.hold(this, () -> { if (!isFinishing() && !isDestroyed()) start(); })', hold)
        self.assertIn('R.string.game_prepare_pausing', hold)
        self.assertIn('Native.note("[nxlaunch] Prepare: " + PrepareGate.outcome)', self.g)

    def test_the_gate(self):
        """ACTION_GAME from the game's process (an activity in front may start the service), a binding that only follows the service, game_ready, a new
        start when the job's process ended to make room, and a bound wait"""
        self.assertIn('GameGate.pauseFirst(j.state(), j.reason())', self.gate)
        self.assertIn('a.startForegroundService(new Intent(a, PackService.class).setAction(PackService.ACTION_GAME))', self.gate)
        self.assertIn('a.bindService(new Intent(a, PackService.class), this, 0)', self.gate)
        self.assertIn('getBoolean("game_ready", false)', self.gate)
        self.assertIn('ask();', body(self.gate, 'public void onServiceDisconnected('))
        self.assertIn('h.postDelayed(() -> finish(', self.gate)
        self.assertRegex(self.gate, r'WAIT_MS = 25_000;')
        fin = body(self.gate, 'private void finish(String why) {')
        self.assertIn('if (done) return;', fin)
        self.assertIn('go.run();', fin)

    def test_a_pause_in_hand_holds_the_game(self):
        """The rc1-fixes review: a Pause writes the job paused/user at once, before its tool has stopped (up to 20 s), and the game started at once beside it,
        without ACTION_GAME, so the process the tool ran in was not ended for the game either. The gate now also holds while the job's process runs"""
        hold = body(self.gate, 'static boolean hold(Activity a, Runnable go) {')
        self.assertIn('if (j == null || !GameGate.holds(j.state(), j.reason(), packRunning(a, j.pid()))) return false;', hold)
        pr = body(self.gate, 'static boolean packRunning(Context c, int pid) {')
        self.assertIn('c.getPackageName() + ":pack"', pr)
        self.assertIn('(pid <= 0 || p.pid == pid)', pr)
        s = code(ONDEVICE / 'PackService.java')
        fin = body(s, 'private void finished() {')
        self.assertIn('if (gameAsked && PackNative.ranTool()) {', fin)   # asked while the worker stopped: the process ends once it has

    def test_binding_death_says_what_happened(self):
        """The rc1 phone check: all four starts that stopped a tool ended through onBindingDied ("the binding to the job's service died: the game starts"),
        never through "asked N time(s)". Android takes the service down when its process ends (HyperOS refuses the restart) and tells the binding
        onServiceDisconnected (which asks for the new process) and then onBindingDied: the path is sound (the old process is gone), and the log now says so,
        with the job's state, the asks and the time"""
        raw = (ONDEVICE / 'PrepareGate.java').read_text(encoding='utf-8')
        bd = body(raw, '    @Override public void onBindingDied(ComponentName n) {')
        self.assertNotIn('the binding to the job', bd)
        self.assertIn('"the job\'s process ended to make room and Android took its service down, so no tool runs beside the game; a new process was asked to wait for it"', bd)
        self.assertIn('"%s (the job %s; asked %d time(s), %.1f s)", asks > 1 ? ended : stopped, now, asks, s)', bd)
        dis = body(self.gate, 'public void onServiceDisconnected(')
        self.assertIn('ask();', dis)   # the new process is asked for before the binding is declared dead

    def test_game_process_name(self):
        """The process the service watches is GameActivity's"""
        m = (REPO / 'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertRegex(m, r'android:name="\.GameActivity"[^>]*android:process=":game"')
        self.assertIn('c.getPackageName() + ":game"', self.gate)


class Service(unittest.TestCase):
    s = code(ONDEVICE / 'PackService.java')

    def test_action_game(self):
        self.assertIn('if (ACTION_GAME.equals(action)) return game(startId);', self.s)
        g = body(self.s, 'private int game(int startId) {')
        self.assertIn('GameGate.onGame(alive, PackNative.ranTool(), stopReason != null,', g)
        # a dead running job: its last process's exit record decides (a Force stop is not resumed by a game's start)
        self.assertIn('!alive && j != null && "running".equals(j.state()) ? JobWatch.exitOf(this, j) : null', g)
        self.assertIn('exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason());', g)
        self.assertIn('startForeground(job);', g)   # startForegroundService's answer
        self.assertIn('return begin(j, false, startId);', g)

    def test_policy_and_watch(self):
        pol = body(self.s, 'private void policy() {')
        self.assertIn('String r = game ? GameGate.REASON :', pol)
        w = body(self.s, 'private final Runnable gameWatch = new Runnable() {', '\n    };\n')
        self.assertIn('PrepareGate.gameRunning(PackService.this)', w)
        self.assertIn('policy();', w)
        self.assertIn('main.postDelayed(gameWatch, GAME_POLL_MS);', body(self.s, 'private int begin(Job j, boolean user, int startId) {'))

    def test_wait_first_and_end_for_the_game(self):
        """The worker waits before anything else (the reads that load the packer), and a wait for a game that asked, after a tool ran here, ends the process"""
        work = body(self.s, 'private void work() {')
        self.assertLess(work.index('if (!waitWhileAuto(j)) {'), work.index('dropOtherGames(j);'))
        wait = body(self.s, 'private boolean waitWhileAuto(Job j) throws InterruptedException {')
        self.assertIn('GameGate.endProcess(PackNative.ranTool(), gameAsked)) endForGame(j);', wait)
        self.assertLess(wait.index('waiting = true;'), wait.index('save(j, true);'))
        end = body(self.s, 'private void endForGame(Job j) {')
        self.assertLess(end.index('save(j, true);'), end.index('leaveNote(j);'))
        self.assertLess(end.index('leaveNote(j);'), end.index('Process.killProcess(Process.myPid());'))
        pause = body(self.s, 'private void gamePause() {')
        self.assertLess(pause.index('j.state("paused", GameGate.REASON, null);'), pause.index('policy();'))
        self.assertIn('jj.state("paused", GameGate.fallbackReason(stopReason), null);', pause)   # a user's Pause in hand stays the user's

    def test_publish_says_ready(self):
        pub = body(self.s, 'private void publish(Job j, boolean force) {')
        self.assertIn('b.putBoolean("game_ready", GameGate.ready(worker != null, waiting && GameGate.REASON.equals(autoReason), PackNative.ranTool()));', pub)
        self.assertIn('b.putBoolean("game_ready", true);', pub)   # no job

    def test_ran_tool(self):
        e = code(ONDEVICE / 'PackEngine.java')
        run = body(e, 'static int packRun(String tool, String[] argv, String logPath) {')
        self.assertLess(run.index('ran = true;'), run.index('return run0('))

    def test_sticky_restart_waits_again(self):
        """A sticky restart (and the app's own start, JobDeath) resumes a job waiting by itself, the game's wait among them"""
        self.assertIn('JobDeath.go(intent != null, auto, j.state(), j.reason(), j.apkChanged())', body(self.s, 'public int onStartCommand('))
        self.assertIn('"paused".equals(state) && JobForm.waitsByItself(reason)', code(ONDEVICE / 'JobDeath.java'))


class Home(unittest.TestCase):
    def test_after_game(self):
        """Home resumes a job left waiting for a game whose service is gone, once no game runs (Ondevice.Panel.afterDeath, JobDeath: test_android_job_death.py)"""
        o = code(ONDEVICE / 'Ondevice.java')
        show = body(o, '        void show() {', '\n        }\n')
        self.assertIn('afterDeath();', show)
        self.assertIn('JobDeath.after(job.state(), job.reason(), jobAlive, exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason(), PrepareGate.gameRunning(a))',
                      body(o, '        void afterDeath() {', '\n        }\n'))
        self.assertIn('GameGate.resumeAfterGame(state, reason, false, gameRuns)', code(ONDEVICE / 'JobDeath.java'))
        self.assertIn('d.containsKey("job")', o)

    def test_card_line(self):
        """The game card's "updating shaders" comes from the job of its pack (GameGate.card), and the cards are made again when that changes"""
        m = code(MAIN / 'MainActivity.java')
        self.assertIn('Ondevice.updateLine(new File(g.packDir), queued)', m)
        self.assertNotIn('getString(R.string.home_state_ready_updating), R.color.bn_leaf)', m)
        o = code(ONDEVICE / 'Ondevice.java')
        self.assertIn('return GameGate.card(queued, j.state(), j.reason(), panel.alive());', o)
        show = body(o, '        void show() {', '\n        }\n')
        self.assertIn('if (cardKey != null && !cardKey.equals(key)) a.refresh();', show)

    def test_texts(self):
        for d in ('values', 'values-tr', 'values-pt-rBR', 'values-b+es+419'):
            t = ''.join(p.read_text(encoding='utf-8') for p in (RES / d).glob('strings_*.xml'))
            for k in ('prepare_reason_game', 'game_prepare_pausing', 'home_state_ready_update_game', 'home_state_ready_update_paused', 'home_state_ready_older'):
                self.assertIn(f'name="{k}"', t, f'{d}: {k}')
        self.assertIn('case "game": return new Msg("prepare_reason_game");', code(ONDEVICE / 'Job.java'))


if __name__ == '__main__':
    unittest.main()
