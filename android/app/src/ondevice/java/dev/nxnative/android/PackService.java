package dev.nxnative.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-device job (ONDEVICE.md "The service and the screen"): a foreground service of type dataSync in its own process (:pack), started by the launcher's
 * "Prepare game". It runs the job's steps in order on one worker thread with a partial wake lock, posts an ongoing notification with the step's progress and a
 * Pause action, and goes on when the app is swiped away. Every change goes to job.json, so a killed process loses at most the step in hand (the translation not even
 * that): START_STICKY brings the service back and it resumes a job that was running, where Android does (HyperOS refuses it: then the app's main process, Home
 * or a game's end start it again, else a notification says it stopped, JobDeath); the launcher's Resume does the same after a reboot or a force stop.
 * <ul>
 * <li>Pause (the notification or the launcher): the job is written paused at once, the tool is interrupted (the split and the translation stop at the file or
 *     shader in hand, within seconds), and while it stops the card and the notification say so ("Pausing", no time left); then the service stops and leaves a
 *     notification with Resume. A tool that has not stopped within 20 s ends with the process, which leaves the same paused job and notification.</li>
 * <li>Battery below 20 % and not charging, or thermal status SEVERE or worse: the tool is interrupted, the job waits (paused, wake lock released) and goes on by
 *     itself when charging / cooled (thermal MODERATE or better).</li>
 * <li>Android 14+'s 6 hours a day for dataSync services (onTimeout): paused like Pause, reason "timeout"; the launcher's Resume, once the app is open, goes on.</li>
 * <li>A game (GameGate, PrepareGate): while a game's process runs, the job waits, reason "game", and goes on by itself when it is gone (polled every 2 s,
 *     however the game ended). A game about to start asks first (ACTION_GAME): a process that has run a tool stops it and ends, leaving the game its memory,
 *     and the game starts the service again in a new process, which waits; one that has run none waits as it is.</li>
 * </ul>
 * The launcher binds with a Messenger (MSG_REGISTER with replyTo) and gets MSG_STATE messages, data "job" = job.json's text, as the job changes, and "eta_min" =
 * the minutes the running job has left (Eta: 0 under a minute, -1 for none; not in job.json), every 10 s at least while the job runs.
 */
public final class PackService extends Service {
    static final String ACTION_START = "dev.nxnative.android.pack.START", ACTION_PAUSE = "dev.nxnative.android.pack.PAUSE";
    /** A game is about to start (PrepareGate, from the game's process): the job waits while it runs (GameGate) */
    static final String ACTION_GAME = "dev.nxnative.android.pack.GAME";
    static final String EXTRA_JOB = "job";
    /** ACTION_START from the app's own automatic start (JobWatch, Home's open, a game's end): like a sticky restart, it resumes only a job that should be working
     *  (JobDeath.go), and its starts of a step add up (MAX_STARTS) */
    static final String EXTRA_AUTO = "auto";
    /** ACTION_PAUSE's sender, for the logs (a job paused with reason "user"): notification, launcher or adb; missing = an intent from elsewhere */
    static final String EXTRA_FROM = "from";
    static final int MSG_REGISTER = 1, MSG_UNREGISTER = 2, MSG_STATE = 3;
    private static final String TAG = "nxpack";
    /** The notification channel and the job's notification (JobWatch posts its stopped notification in the same place from the main process) */
    static final String CHANNEL = "pack";
    static final int NOTE = 1;
    /** Starts of one step the process may not live through before the job fails (Job.start) */
    private static final int MAX_STARTS = 2;
    private static final Pattern PROGRESS = Pattern.compile("^progress:? (\\d+)(?: |/)(\\d+)(?: (\\S+))?");

    private final List<Messenger> clients = new CopyOnWriteArrayList<>();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private NotificationManager nm;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private PackEngine engine;
    private volatile Job job;
    private volatile Thread worker;
    private volatile String stopReason;   // user, timeout: the job pauses and the service stops
    private volatile String autoReason;   // battery, thermal, game: the job waits and goes on by itself
    /** A game's process runs (PrepareGate.gameRunning: polled every 2 s while a worker runs, and set by ACTION_GAME) */
    private volatile boolean game;
    /** A game asked this process to make room (ACTION_GAME) and starts the service again when its process ends (GameGate.endProcess) */
    private volatile boolean gameAsked;
    /** The worker waits in waitWhileAuto (for GameGate.ready) */
    private volatile boolean waiting;
    /** The heat wait holds until thermal MODERATE or better */
    private boolean hot;
    /** The worker is inside a tool (engine.run): what a pause waits for. Changed only under noteLock, which the notification's every post takes */
    private volatile boolean inTool;
    /**
     * Taken by the one place that decides the job's notification and posts it (postNote, and startForeground) and by the tool's start and end (step): a form
     * decided while the tool ran cannot be posted after its end. The phone test of 2026-11-15 (rc1): the worker's last job.json write, made after the tool
     * had exited, posted "Pausing" (decided from "the worker has not ended yet") a few milliseconds before finished() posted "Paused", and the shade kept
     * "Pausing" with no button until the job was resumed or forgotten
     */
    private final Object noteLock = new Object();
    /** The notification left for after this service (leaveNote: the 20-s fallback, onTimeout): the worker's updates no longer replace it */
    private volatile boolean noteLeft;
    private int batteryPct = 100, thermal;
    private boolean charging = true;
    private long lastNote, lastSave, lastSend;
    /** The running job's time left (Eta), kept across a pause while this process lives (etaKey: its job), so a resume goes on from the rate it measured */
    private static volatile Eta eta;
    private static String etaKey;
    /** Every 10 s while a job runs: the launcher and the notification get the time left as it counts down (a library write prints no line for minutes) */
    private static final long TICK_MS = 10_000;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (worker == null) return;
            Job j = job;
            if (j != null && "running".equals(j.state())) publish(j, false);   // a wait for the battery or the heat has no time left to show: its notification stands
            JobAlarm.arm(PackService.this, false);   // a check of the job some minutes on, should this process die with no other of the app left (JobAlarm)
            main.postDelayed(this, TICK_MS);
        }
    };
    /** Every 2 s while a worker runs: whether a game's process runs (the job waits while it does, and goes on when it is gone, however it ended) */
    private static final long GAME_POLL_MS = 2_000;
    private final Runnable gameWatch = new Runnable() {
        @Override public void run() {
            if (worker == null) return;
            boolean g = PrepareGate.gameRunning(PackService.this);
            if (g != game) {
                Log.i(TAG, g ? "a game runs: the job waits" : "the game's process is gone: the job goes on");
                game = g;
                if (!g) gameAsked = false;
                policy();
            }
            main.postDelayed(this, GAME_POLL_MS);
        }
    };
    private final PowerManager.OnThermalStatusChangedListener thermalListener = s -> { thermal = s; policy(); };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { battery(i); policy(); }
    };

    /** Android 12: the language picked in Settings › Language (Lang.wrap; Android 13+ applies it itself) */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Lang.wrap(base)); }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        pm = getSystemService(PowerManager.class);
        channel(this, nm);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nxnative:pack");
        wake.setReferenceCounted(false);
        engine = PackEngine.create(this);
        // ACTION_BATTERY_CHANGED is a protected broadcast (only the system sends it); the export flag exists from Android 13
        IntentFilter bf = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        battery(Build.VERSION.SDK_INT >= 33 ? registerReceiver(batteryReceiver, bf, Context.RECEIVER_NOT_EXPORTED) : registerReceiver(batteryReceiver, bf));
        thermal = pm.getCurrentThermalStatus();
        pm.addThermalStatusListener(getMainExecutor(), thermalListener);
        game = PrepareGate.gameRunning(this);
        policy();
    }

    /** The job's notification channel (made again with the texts in the language of c, which renames it) */
    static void channel(Context c, NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.prepare_channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.prepare_channel_description));
        nm.createNotificationChannel(ch);
    }

    @Override public void onDestroy() {
        unregisterReceiver(batteryReceiver);
        pm.removeThermalStatusListener(thermalListener);
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            String from = intent.getStringExtra(EXTRA_FROM);
            boolean running = worker != null && worker.isAlive();
            logPause(job, (from != null ? from : "an intent without \"from\"") + (running ? "" : ", nothing running"));   // i18n-keep: a log line
            if (running) requestStop("user");
            else if (worker == null) {   // nothing runs here: a stale notification, or the card's Pause on a job whose process died
                pauseDead();
                stopSelf(startId);
            }
            return START_STICKY;
        }
        if (ACTION_GAME.equals(action)) return game(startId);
        String path = intent == null ? null : intent.getStringExtra(EXTRA_JOB);
        File f = path != null ? new File(path) : Job.remembered(this);
        Job j = null;
        try {
            if (f != null) j = Job.read(f);
        } catch (IOException e) {
            Log.w(TAG, "job: " + e.getMessage());
        }
        // A sticky restart (no intent: the process was killed) and the app's own automatic start (JobWatch, Home, a game's end) resume only a job that was
        // running or waiting by itself (the battery, the heat, a game: it waits again as long as that holds); a start resumes one that is not done
        boolean auto = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
        boolean go = j != null && JobDeath.go(intent != null, auto, j.state(), j.reason(), j.apkChanged());
        if (!go && (worker == null || !worker.isAlive())) {
            // startForegroundService must be answered by startForeground even when there is nothing to do
            if (intent != null) startForeground(j);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            startForeground(job);
            return START_STICKY;
        }
        if (auto) Log.i(TAG, "the app starts the job again by itself (" + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason()) + ")");
        return begin(j, intent != null && !auto, startId);
    }

    /** Runs job j on a new worker (none runs). user: the user's Start / Resume / Retry, after which earlier deaths in a step count no more */
    private int begin(Job j, boolean user, int startId) {
        job = j;
        if (!startForeground(j)) {
            // Android refused a foreground service here (a start from the background): the job stays as it is, the launcher's Resume starts it
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopReason = null;
        j.process(Process.myPid(), System.currentTimeMillis());   // its exit record is the job's from now on (JobWatch.exitOf); job.json has it with the next write
        if (user) j.endedAll();   // only sticky restarts (and a game's start) add up
        Job.remember(this, j.file);
        game = game || PrepareGate.gameRunning(this);   // the worker's first wait (work) must know before it starts (game: a game's start asked just now)
        policy();
        worker = new Thread(this::work, "nxpack-job");
        worker.start();
        JobAlarm.arm(this, true);
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
        main.removeCallbacks(gameWatch);
        main.postDelayed(gameWatch, GAME_POLL_MS);
        return START_STICKY;
    }

    /**
     * A game is about to start (PrepareGate, from the game's process: its activity is in front, so Android allowed this start). GameGate decides: nothing (no job
     * works, or a pause the user asked for is in hand), the job waits in this process, or its tool stops and this process ends (waitWhileAuto), after which the
     * game starts this service again. The game listens for "game_ready" (publish)
     */
    private int game(int startId) {
        game = true;   // the caller is the game's process; the watch confirms it every 2 s
        boolean alive = worker != null && worker.isAlive();
        Job j = alive ? job : null;
        if (!alive) {
            File f = Job.remembered(this);
            try {
                if (f != null && f.isFile()) j = Job.read(f);
            } catch (IOException e) {
                Log.w(TAG, "a game starts: the job: " + e.getMessage());
            }
        }
        // with no worker, a job that says running died with its process: how it ended decides (a stop the user asked for is not resumed by a game's start)
        android.app.ApplicationExitInfo exit = !alive && j != null && "running".equals(j.state()) ? JobWatch.exitOf(this, j) : null;
        GameGate.Act act = GameGate.onGame(alive, PackNative.ranTool(), stopReason != null, j == null ? "" : j.state(), j == null ? "" : j.reason(),
                exit == null ? JobDeath.EXIT_UNKNOWN : exit.getReason());
        Log.i(TAG, "a game starts: " + act + (j == null ? ", no job" : ", the job " + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason()))
                + (alive ? ", its worker runs" : "") + (PackNative.ranTool() ? ", a tool ran in this process" : "") + (exit != null ? ", its process ended (" + JobWatch.describe(exit) + ")" : ""));
        gameAsked = true;   // also a Pause in hand: once its worker ends, this process ends too (finished)
        if (alive) {
            startForeground(job);   // startForegroundService's answer
            if (act != GameGate.Act.NOTHING) gamePause();
            return START_STICKY;
        }
        if (act == GameGate.Act.NOTHING) {
            startForeground(j);   // startForegroundService's answer, then nothing to do; a paused or failed job's notification stays as it was (Resume, Retry)
            stopForeground(j != null && ("paused".equals(j.state()) || "failed".equals(j.state())) ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            if (PackNative.ranTool()) {   // an earlier job's process, kept by Android: what its tools left goes before the game starts
                Log.i(TAG, "a game starts: ending this process, which keeps what an earlier tool left");
                Process.killProcess(Process.myPid());
            }
            return START_NOT_STICKY;
        }
        return begin(j, false, startId);   // its worker waits for the game before anything else (work)
    }

    /**
     * The job is to wait for the game that starts: written paused (reason game) at once, so a process that dies while the tool stops leaves it waiting for the
     * game; the policy interrupts the tool, and the worker waits (or ends this process: waitWhileAuto). A tool that has not stopped within 20 s goes with the
     * process, as after a Pause (requestStop)
     */
    private void gamePause() {
        Job j = job;
        if (j != null && "running".equals(j.state())) {
            j.state("paused", GameGate.REASON, null);
            save(j, true);
        }
        policy();
        synchronized (lock) { lock.notifyAll(); }   // a worker that already waits (the battery, the heat) looks again: it may end this process
        Thread w = worker;
        main.postDelayed(() -> {
            if (w != null && w.isAlive() && worker == w && inTool && game) {
                Log.w(TAG, "the tool did not stop within 20 s for the game: ending the process");
                Job jj = job;
                if (jj != null) {
                    jj.state("paused", GameGate.fallbackReason(stopReason), null);   // a Pause tapped meanwhile stays the user's
                    save(jj, true);
                    leaveNote(jj);
                }
                Process.killProcess(Process.myPid());
            }
        }, 20_000);
    }

    /** Android 14+'s daily dataSync limit (6 h): pause cleanly and stop within the seconds Android gives (else it ends the app) */
    @Override public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "onTimeout: the daily dataSync limit is reached; pausing");
        requestStop("timeout");   // the job is paused, reason timeout
        Job j = job;
        if (j != null) leaveNote(j);   // Android ends the service within seconds: the paused job's notification, not the stopping one
        else stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private boolean startForeground(Job j) {
        try {
            synchronized (noteLock) { startForeground(NOTE, note(j), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
            noteLeft = false;
            return true;
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the type's time limit used up
            Log.w(TAG, "startForeground refused: " + e);
            if (j != null && !"done".equals(j.state())) {
                j.state("paused", "refused", null);
                save(j, true);
            }
            return false;
        }
    }

    /** A pause the user asked for, in logcat (nxpack) and in the current step's log (ondevice/logs/<step>.log): who asked and when */
    private void logPause(Job j, String from) {
        String line = "[pack] " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " pause asked (from " + from + ")";   // i18n-keep: a log line
        Log.i(TAG, line);
        if (j == null) return;
        int i = j.current();
        if (i < 0) return;
        File logs = new File(j.dir(), "logs");
        try {
            logs.mkdirs();
            Files.write(new File(logs, Job.STEPS[i] + ".log").toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "pause log: " + e);
        }
    }

    /**
     * A pause the job does not go on from by itself (user, timeout). The job is written paused with that reason at once, before the tool has stopped: while it stops
     * the card and the notification say so (JobForm STOPPING), and a process that dies meanwhile (the fallback below, a low-memory kill) leaves the job paused, which
     * neither a sticky restart nor the launcher resumes by itself
     */
    private void requestStop(String reason) {
        stopReason = reason;
        Job j = job;
        if (pausable(j)) {
            j.state("paused", reason, null);
            save(j, true);
        }
        engine.interrupt();
        synchronized (lock) { lock.notifyAll(); }
        Thread w = worker;
        // a tool that does not stop within 20 s goes with the process: every step is restartable, and the job is paused as after a clean stop (the tools stop at the
        // file or shader in hand, a few seconds on the phone: vk_android_build.py run_batches, shader_split.py pooled; 20 s covers a translator call or a large
        // file in hand on a slow phone)
        main.postDelayed(() -> {
            if (w != null && w.isAlive() && worker == w) {
                Log.w(TAG, "the tool did not stop within 20 s: ending the process");
                Job jj = job;
                if (jj != null) {
                    if (pausable(jj)) jj.state("paused", reason, null);
                    save(jj, true);
                    leaveNote(jj);
                }
                Process.killProcess(Process.myPid());
            }
        }, 20_000);
    }

    /**
     * A Pause with no worker here: the remembered job, when it says it works (running, or waiting by itself) while its process is gone, is written paused by the
     * user, with its paused notification. Home's card offers Pause for 2.5 s after it opens on such a job (it cannot know yet that the service is gone), and the
     * Pause used to be dropped here, after which Home resumed the job by itself (Ondevice.Panel.afterDeath); now nothing resumes it but the user
     */
    private void pauseDead() {
        File f = Job.remembered(this);
        Job j;
        try {
            j = f != null && f.isFile() ? Job.read(f) : null;
        } catch (IOException e) {
            Log.w(TAG, "pause: the job: " + e.getMessage());
            return;
        }
        if (!pausable(j)) return;
        Log.i(TAG, "pause: the job " + j.state() + (j.reason().isEmpty() ? "" : "/" + j.reason()) + " has no worker (its process ended): paused by the user");
        j.state("paused", "user", null);
        try {
            j.save();
        } catch (IOException e) {
            Log.e(TAG, "job.json: " + e);
            return;
        }
        postNote(j, -1, false);
    }

    /** A job a pause applies to: running, or waiting for the battery or the heat (not one already done, failed or paused for good) */
    private static boolean pausable(Job j) {
        return j != null && ("running".equals(j.state()) || "paused".equals(j.state()) && JobForm.waitsByItself(j.reason()));
    }

    /** The job's notification as it stands once this service is gone (paused: Resume; failed: Retry), detached from the service, which no later update replaces */
    private void leaveNote(Job j) {
        noteLeft = true;
        stopForeground(STOP_FOREGROUND_DETACH);
        postNote(j, -1, false);
    }

    // ---- Battery and heat ----

    private void battery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale;
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** The automatic pause: a game's process runs (GameGate), below 20 % on battery, or SEVERE heat (until MODERATE or better) */
    private void policy() {
        hot = thermal >= PowerManager.THERMAL_STATUS_SEVERE || hot && thermal > PowerManager.THERMAL_STATUS_MODERATE;
        String r = game ? GameGate.REASON : batteryPct < 20 && !charging ? "battery" : hot ? "thermal" : null;
        String was = autoReason;
        autoReason = r;
        if (r != null && was == null) {
            Log.i(TAG, "pausing: " + r + " (battery " + batteryPct + " %" + (charging ? ", charging" : "") + ", thermal " + thermal + ")");
            if (inTool) engine.interrupt();
        }
        if (r == null && was != null) Log.i(TAG, "going on: battery " + batteryPct + " %" + (charging ? ", charging" : "") + ", thermal " + thermal);
        if (!java.util.Objects.equals(r, was)) synchronized (lock) { lock.notifyAll(); }   // the worker's wait ends, or says the new reason
    }

    // ---- The job ----

    /**
     * Deletes the split's kept scan results (Job.splitScan) and the translated shaders' results (Job.xlate), both in the app's own storage, of every game but
     * this job's, as the job starts: one job runs at a time, so no other game's step can resume from them before a job of that game starts again (after Forget,
     * or a job whose folder went: a few hundred MB to a GB). Decided from the job that runs, read from its job.json, never from the remembered path: shared
     * storage that is briefly unreadable deletes nothing.
     */
    private void dropOtherGames(Job j) {
        String keep = j.titleId();
        if (keep.isEmpty()) return;
        for (File root : new File[] {j.splitScan(this).getParentFile(), j.xlate(this).getParentFile()}) {
            File[] games = root.listFiles();
            if (games != null) for (File g : games) if (!g.getName().equals(keep)) {
                Log.i(TAG, "deleting the kept results of another game: " + g);
                Job.deleteTree(g);
            }
        }
    }

    private void work() {
        Job j = job;
        wake.acquire(7 * 3600_000L);
        try {
            // a game runs (or the battery or the heat says wait): nothing is done before, not even the reads below (the profile, the dump's results), which load
            // the packer
            if (!waitWhileAuto(j)) {
                j.state("paused", stopReason, null);
                save(j, true);
                return;
            }
            dropOtherGames(j);
            File oldXlate = new File(j.dir(), "xlate");   // where an older build (0.2.2) kept the results, on shared storage
            if (oldXlate.exists()) {
                // Stamped with that build's tools, but the build keeps each one whose library entry is the manifest's (vk_android_build.py stale_kept): a Prepare an
                // app update stopped in its shaders step keeps what it translated. Moved into the app's storage (Job.xlate) unless this APK translates every shader
                // again anyway (apkChanged: another chain); the rest goes with the folder
                if (!j.apkChanged()) {
                    List<String> problems = new ArrayList<>();
                    int moved = ShaderResults.move(oldXlate, j.xlate(this), problems);
                    Log.i(TAG, "moved " + moved + " translated shaders an older build kept in " + oldXlate + " to " + j.xlate(this));
                    for (String p : problems) Log.w(TAG, "moving the older build's translated shaders: " + p + "; the rest are translated again");
                }
                Log.i(TAG, "deleting the translated shaders an older build kept in " + oldXlate);
                Job.deleteTree(oldXlate);
            }
            if (j.apkChanged()) {
                Log.i(TAG, "the job's shaders are from another app version: translating them again");
                j.resetChainSteps();
                Job.deleteTree(j.xlate(this));
                Job.deleteTree(new File(j.dir(), "lib"));
            }
            // An app update that changed the game's shader tables but no chain version (Ocarina of Time's mesh shaders): a split or library of the old tables is made again
            if (!engine.simulated()) {
                int redo = j.inputsChanged(profileMap(j));
                if (redo >= 0) {
                    Log.i(TAG, "the job's " + (redo == JobInputs.SPLIT ? "split" : "shader library") + " was made from other shader tables than this app's (inputs "
                            + Job.inputs(j.titleId()) + "): " + Job.STEPS[redo] + " again");
                    if (redo == JobInputs.SPLIT) {
                        try {
                            Outputs.delete(new File(j.dir(), "split"));
                        } catch (IOException e) {
                            Log.w(TAG, "split: " + e.getMessage());   // its step is pending: its next run's Outputs.begin tries again
                        }
                        Job.deleteTree(j.splitScan(this));
                    }
                    Job.deleteTree(new File(j.dir(), "lib"));
                }
            }
            j.state("running", null, null);
            Eta est = etaFor(j);
            est.running(true, SystemClock.elapsedRealtime());
            save(j, true);
            int i;
            while ((i = j.next()) >= 0) {
                if (!waitWhileAuto(j)) break;
                String s = Job.STEPS[i];
                est.step(i, SystemClock.elapsedRealtime());
                j.stepState(s, "running");
                int starts = j.start(s);
                save(j, true);
                if (starts > MAX_STARTS) {   // twice ended by Android inside this step (START_STICKY brought it back): stop instead of looping
                    j.ended(s);
                    j.stepState(s, "pending");
                    // job.json's error is English (docs/i18n.md), its code and arguments beside it: the status line shows it in the user's language
                    String[] killed = PrepareErrors.coded("step_killed", s, starts - 1, s.equals("rewrite") ? "2.7" : "1.5");
                    j.state("failed", null, PrepareErrors.message(killed).english(), killed);
                    save(j, true);
                    break;
                }
                failCode = null;
                String fail = step(j, i);
                j.ended(s);
                if (stopReason != null) { j.stepState(s, "pending"); break; }
                // interrupted by the policy (which may have cleared since): wait if it still holds, then run the step again
                if (fail != null && (autoReason != null || fail.equals("interrupted"))) { j.stepState(s, "pending"); continue; }
                if (fail != null) {
                    j.stepState(s, "pending");
                    j.state("failed", null, fail, failCode);   // the packer's error code beside the English error (JobErrors)
                    save(j, true);
                    break;
                }
                j.stepState(s, "done");
                if (s.equals("split")) j.clearNotice();   // why it ran again: said while it ran
                save(j, true);
                if (s.equals("split")) Job.deleteTree(j.splitScan(this));   // recorded done: the scan results are no longer needed
            }
            if (j.next() < 0) {
                j.state("done", null, null);
                // the split and the translation results are not needed once the library is in the pack
                try {
                    Outputs.delete(new File(j.dir(), "split"));
                } catch (IOException e) {
                    Log.w(TAG, "split: " + e.getMessage());   // the job is done all the same; a later split begins by deleting it again
                }
                Job.deleteTree(j.splitScan(this));
                Job.deleteTree(j.xlate(this));
                Job.deleteTree(new File(j.dir(), "lib"));
            } else if (stopReason != null) j.state("paused", stopReason, null);
            save(j, true);
        } catch (Exception e) {
            Log.e(TAG, "job", e);
            j.state("failed", null, String.valueOf(e));
            save(j, true);
        } finally {
            Eta est = eta;
            if (est != null) est.running(false, SystemClock.elapsedRealtime());
            if (wake.isHeld()) wake.release();
            main.post(this::finished);
        }
    }

    /**
     * The job's estimator: the one this process already has for it (a resume), else a new one. What its shaders step translates as far as known before it says:
     * its own total once the tool gave one, else the update's count when the pack has an older library the profile's delta manifest knows (the build's
     * DMSH_VK_DELTA_FROM below), else every shader of the game; the library holds every shader of the game either way
     */
    private static synchronized Eta etaFor(Job j) {
        String key = j.file.getPath() + "@" + j.created();
        if (eta != null && key.equals(etaKey)) return eta;
        Profile p = Profile.of(j.titleId());
        int all = p != null ? p.shaderTotal : (int) Math.max(0, j.shadersTotal());
        long told = j.stepTotal(Job.SHADERS);
        int delta = told > 1 ? -1 : Pack.deltaShaders(j.pack(), j.titleId());
        eta = new Eta(told > 1 ? (int) told : delta >= 0 ? delta : all, all, Eta.SHADER_S);
        etaKey = key;
        // a new process of a job that ran before: the rate its last process measured goes on, if its first line is of the same part (Eta.carried)
        double[] carry = j.etaCarry();
        if (carry != null) {
            eta.carried(carry);
            Log.i(TAG, String.format(Locale.ROOT, "time left: the process before measured %.2f a second in step %s (total %d); taken if this run goes on with it",
                    carry[3], Job.STEPS[Math.max(0, Math.min(Job.STEPS.length - 1, (int) carry[0]))], (long) carry[1]));
        }
        return eta;
    }

    private void finished() {
        Job j = job;
        worker = null;
        JobAlarm.cancel(this);   // the worker ended with the job done, failed or paused: nothing to check (a process that dies before this keeps its alarm)
        publish(j, true);
        stopForeground(STOP_FOREGROUND_DETACH);
        if (j != null) postNote(j, -1, false);   // worker is null: paused (Resume), failed (Retry) or done (Play)
        stopSelf();
        if (gameAsked && PackNative.ranTool()) {   // a game starts (a Pause was in hand, or the job ended meanwhile): what the tools left goes with this process
            Log.i(TAG, "a game starts: ending this process, which keeps what the tools left");
            Process.killProcess(Process.myPid());
        }
    }

    /**
     * Waits while the policy pauses the job (job.json says why, as the reason changes); false when the job is to stop. A wait for a game that asked to start
     * (ACTION_GAME) in a process that has run a tool ends the process instead (GameGate.endProcess): what the tool left would stay beside the game
     */
    private boolean waitWhileAuto(Job j) throws InterruptedException {
        if (autoReason == null) return stopReason == null;
        Eta est = eta;
        if (est != null) est.running(false, SystemClock.elapsedRealtime());   // waiting is no running time
        if (wake.isHeld()) wake.release();
        synchronized (lock) {
            String shown = null;
            while (autoReason != null && stopReason == null) {
                String r = autoReason;
                if (GameGate.REASON.equals(r) && GameGate.endProcess(PackNative.ranTool(), gameAsked)) endForGame(j);
                waiting = true;
                if (!r.equals(shown)) {
                    j.state("paused", r, null);
                    save(j, true);   // also tells the game (publish: game_ready)
                    shown = r;
                }
                lock.wait(30_000);
            }
            waiting = false;
        }
        if (stopReason != null) return false;
        wake.acquire(7 * 3600_000L);
        if (est != null) est.running(true, SystemClock.elapsedRealtime());
        j.state("running", null, null);
        save(j, true);
        return true;
    }

    /**
     * A game is to start and this process has run a tool (GameGate STOP_AND_END): the job is left paused for the game with its notification, and the process
     * ends; the game, which saw its binding drop, starts the service again in a new process that waits for it (PrepareGate). Never returns
     */
    private void endForGame(Job j) {
        Log.i(TAG, "a game starts: ending this process, which keeps what the tools left; the game starts the service again to wait for it");
        j.state("paused", GameGate.REASON, null);
        save(j, true);
        leaveNote(j);
        Process.killProcess(Process.myPid());
    }

    /** Runs step i; null when it is done, else why it failed */
    private String step(Job j, int i) throws IOException {
        String s = Job.STEPS[i];
        if (s.equals("identify")) {
            DumpCheck.Result r = DumpCheck.check(j.dump(), false);
            if (!r.ok()) { failCode = r.code; return r.error; }   // DumpCheck's own refusals have a code; DumpLayout's stay English (job.json's error)
            if (!r.titleId.equals(j.titleId()) || r.version != j.version()) return failed("dump_changed", r.titleId, String.valueOf(r.version));
            j.stepProgress(s, 1, 1, "identify");
            return null;
        }
        File od = j.dir(), dump = j.dump(), pack = j.pack();
        String v = String.valueOf(j.version());
        // The update's RomFS and main: folders of the dump, or (a .nup) <file>.nup:update/<version>/romfs and …/exefs/main (base/… for version 0), which the
        // tools take where they take the folders (android/NUP.md "Naming a package"); the dump itself goes to pack_android.py as it is, folder or .nup
        String upRomfs = DumpCheck.versionPath(dump, j.version(), "romfs"), upMain = DumpCheck.versionPath(dump, j.version(), "exefs/main");
        File split = new File(od, "split"), splitTmp = new File(od, "split.tmp"), splitScan = j.splitScan(this), xlate = j.xlate(this), lib = new File(od, "lib");
        String tool;
        String[] argv;
        // The tools' arguments: the in-process forms (pack_android.py --in-process: the rewriter in the same Python; shader_split.py with J=1 below: no process pool;
        // vk_android_build.py with the translator and the validator as libraries, XLATE / SPIRV_VAL below; the app's process must not fork, ONDEVICE.md "No subprocesses").
        // The split is resumable by RomFS file: its scan results are kept in the app's own storage (Job.splitScan, DM_SPLIT_RESUME, shader_split.py ScanCache) and a rerun scans only the files
        // that have none; it writes its outputs into split/ at its end, which counts as complete only once its marker is written after them (Outputs: no folder is
        // renamed on shared storage, where MediaProvider may refuse it).
        // The shaders step is resumable by shader: each result is a file in Job.xlate, in the app's own storage (DMSH_VK_RESULTS), and a rerun skips the shaders
        // that have one; it writes the library (lib/v-<DMSH_VK_CHAIN>/) from the results at its end. The pack step copies the library into the pack (--shader-cache)
        switch (s) {
            case "rewrite":
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--code-only", "-j", "1", "--in-process"};
                break;
            case "split":
                // only outputs a stopped run may have half written: the split writes them all at its end; what it kept is in Job.splitScan. The marker goes first
                Outputs.begin(split);
                Job.deleteTree(splitTmp);   // left by an older build, which wrote into split.tmp/ and renamed it
                tool = "dm/gfx/tools/shader_split.py";
                argv = new String[] {upRomfs, upMain, new File(split, "pkg").getPath(),
                        new File(split, "shader_map.tsv").getPath(), new File(split, "shader_facts.tsv").getPath(), new File(split, "shader_pairs.tsv").getPath(),
                        new File(split, "pso_pairs.tsv").getPath()};
                break;
            case "shaders": {
                // the split went (a finished job deletes it; an APK update then translates again), or it is not complete (no marker; also an older build's split/): split first
                if (!Outputs.complete(split)) {
                    j.restartStep("split");   // its scan results went with the job's end (or were never kept): its count starts over too
                    return "interrupted";
                }
                String bad = splitMatchesProfile(j, split);
                if (bad != null) return bad;
                bad = resultsRoom(xlate);
                if (bad != null) return bad;
                // the time left weighs the translation's two phases (non-vertex shaders, then vertex ones) by the profile's map, the one the tool takes its
                // shaders and its total from (a delta split's own map lists only the files it read)
                Eta est = eta;
                if (est != null) {
                    int[] mix = null;
                    try {
                        mix = Eta.shaderMix(profileMap(j));
                        if (mix == null) Log.w(TAG, "time left: the profile's shader_map.tsv lists no shader: every shader counts alike");
                    } catch (IOException e) {
                        Log.w(TAG, "time left: the profile's shader_map.tsv: " + e + ": every shader counts alike");   // the estimate still works, a little less well
                    }
                    est.mix(mix);
                }
                tool = "dm/gfx/tools/vk_android_build.py";
                argv = new String[] {"--target", "vulkan", new File(split, "pkg").getPath(), lib.getPath()};
                break;
            }
            case "pack":
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--shader-cache", j.shaderCache().getPath(), "--keep-code", "-j", "1", "--in-process"};
                break;
            default:
                return "unknown step " + s;   // i18n-keep: a programming error (STEPS has no other step), never a user's
        }
        File logs = new File(od, "logs");
        logs.mkdirs();
        File log = new File(logs, s + ".log");
        String head = "== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + Prefs.buildTag(this) + ", engine " + BuildConfig.NX_PACK_ENGINE + ": "   // i18n-keep: the step log's head
                + tool + " " + String.join(" ", argv) + "\n";
        Files.write(log.toPath(), head.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (s.equals("shaders")) {   // a failed shader's kept result would fail every Retry the same way: those shaders are translated again (ShaderResults)
            List<ShaderResults.Failed> again = ShaderResults.dropFailed(xlate);
            StringBuilder b = new StringBuilder();
            for (ShaderResults.Failed f : again) b.append("[retry] translating again, it failed before: ").append(f).append('\n');   // i18n-keep: the step log
            if (b.length() > 0) Files.write(log.toPath(), b.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        }
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        PackEngine.setenv("DM_GAME", j.titleId());
        PackEngine.setenv("DM_REWRITE_J", "1");
        PackEngine.setenv("NX_PACK_JOBS", String.valueOf(workers));
        PackEngine.setenv("J", s.equals("split") ? "1" : String.valueOf(workers));   // the split's J is its process pool: 1 = in this process
        PackEngine.setenv("DM_SPLIT_RESUME", splitScan.getPath());   // the split's kept scan results (only shader_split.py reads it)
        // A shader update reads only the dump's files that hold the shaders it translates (shader_split.py DM_SPLIT_DELTA, split_delta.py), unless a delta split's
        // package already missed one (Job.splitFull): the split decides from the pack's older libraries (DMSH_VK_DELTA_FROM below) and reads every file when it cannot
        // show it found each such shader where a full split finds it
        PackEngine.setenv("DM_SPLIT_DELTA", s.equals("split") && !j.splitFull() ? "1" : "0");
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);   // pack.tsv's packer: no repository on the phone to ask
        PackEngine.setenv("NX_PACK_WORK", od.getPath());
        PackEngine.setenv("NX_PACK_VERSION", v);
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        // vk_android_build.py: the per-shader results (kept until the job is done: a rerun resumes from them), the translator and SPIRV-Tools from the APK's lib/
        // (bare names: the linker finds them in the APK, as System.loadLibrary does; ctypes loads them in this process)
        PackEngine.setenv("DMSH_VK_RESULTS", xlate.getPath());
        // the delta update: entries of the pack's older libraries that the profile's manifest has unchanged are kept, only the rest translated (android/SHADERS.md#delta-updates)
        PackEngine.setenv("DMSH_VK_DELTA_FROM", new File(pack, "shaders").getPath());
        PackEngine.setenv("XLATE", "libdmsh_xlate.so");
        PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");
        PackEngine.setenv("DMSH_VK_REQUIRE_ALL", "1");   // a library without every shader is a failed step, never a finished one
        PackEngine.setenv("DMSH_VK_XLATE_J", "1");       // one translator call at a time (one thread was faster on the phone); the env file below can change it
        // the shaders' Python half on several cores (subinterpreters, as many as the memory allows) and the translator's garbage collector less often (ShaderWorkers)
        // and the split's scan likewise (shader_split.py DM_SPLIT_INTERPS, with its J=1 above)
        double totalGib = totalMemGib();
        int interps = s.equals("shaders") || s.equals("split") ? ShaderWorkers.interps(workers, totalGib) : 1;
        PackEngine.setenv("DMSH_VK_INTERPS", String.valueOf(s.equals("shaders") ? interps : 1));
        PackEngine.setenv("DM_SPLIT_INTERPS", String.valueOf(s.equals("split") ? interps : 1));
        PackEngine.setenv("DOTNET_GCgen0size", ShaderWorkers.gen0());
        // (asked for: the tool says whether they run, in a "subinterpreters: " line that Tail copies here; where none can be set up the step runs as before)
        Log.i(TAG, s + ": " + workers + " workers, " + interps + " subinterpreters asked (" + String.format(Locale.ROOT, "%.1f", totalGib) + " GiB)");
        // Tests: ondevice/env.txt, KEY=VALUE lines, overrides any of the above for the tools (e.g. DMSH_VK_XLATE_J=6 to compare the translator's threads)
        File envFile = new File(od, "env.txt");
        if (envFile.isFile()) for (String l : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
            int eq = l.indexOf('=');
            if (eq > 0 && !l.startsWith("#")) { PackEngine.setenv(l.substring(0, eq).trim(), l.substring(eq + 1).trim()); Files.write(log.toPath(), ("[env.txt] " + l.trim() + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); }   // i18n-keep: the step log
        }
        Tail tail = new Tail(j, s, log);
        tail.start();
        int code;
        synchronized (noteLock) { inTool = true; }
        try {
            if (autoReason != null || stopReason != null) return "interrupted";
            code = engine.run(tool, argv, log.getPath());
        } finally {
            synchronized (noteLock) { inTool = false; }   // from here every post of the notification says paused, not pausing (noteLock)
            tail.finish();
        }
        Log.i(TAG, s + ": exit " + code);
        if (code == PackEngine.INTERRUPTED) return "interrupted";
        if (s.equals("shaders") && code != 0 && new File(split, JobInputs.DELTA_MARKER).isFile() && tail.lastRed != null && tail.lastRed.contains(NOT_IN_PACKAGE)) {
            // The delta split's package lacks a shader the build translates (the pack's older library changed since the split, or one of its entries could not be read
            // here): every file is split now, then the build goes on from its kept results
            Log.i(TAG, "the delta split's package lacks shaders the build needs: splitting every file");
            try {
                Outputs.delete(split);
            } catch (IOException e) {
                Log.w(TAG, "split: " + e.getMessage());   // its step is pending: the next run's Outputs.begin tries again
            }
            Job.deleteTree(splitScan);
            j.splitFull(FULL_SPLIT_NOTICE);
            j.restartStep("split");
            return "interrupted";
        }
        if (code != 0) {   // the packer's code of the [RED] line when it printed one (JobErrors), else Prepare's own: the tool stopped, with its last [RED] line if any
            String[] packer = tail.lastRed != null ? tail.watch.code() : null;
            String err = tail.lastRed != null ? failed("tool_exit_red", s, code, tail.lastRed, log) : failed("tool_exit", s, code, log);
            if (packer != null) failCode = packer;
            return err;
        }
        if (s.equals("split")) {
            Outputs.finish(split);   // every output synced, then the marker: a reboot after the step is recorded done cannot leave cut tables accepted in split/
            // the scan results go only once job.json records the split done (work()): a kill before that resumes from them instead of scanning everything again
        }
        long t = j.stepTotal(i);
        j.stepProgress(s, t > 0 ? t : 1, t > 0 ? t : 1, "");
        return null;
    }

    /** What the shaders step's results take in the app's own storage at most (Ocarina of Time: 0.93 GiB for 42,346 shaders), with room to spare */
    static final long XLATE_BYTES = 1100L << 20;

    /**
     * null when the app's own storage has room for the results still to come (Job.xlate), else what to do (PrepareErrors results_free_unknown, results_space).
     * DumpCheck counts the free space beside the dump; the results are in the phone's internal storage, which is another place when the dump is on a memory card.
     */
    private String resultsRoom(File xlate) {
        File dir = xlate.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        long have = 0;
        File[] l = xlate.listFiles();
        if (l != null) for (File f : l) have += f.length();
        long free;
        try {
            free = new StatFs(dir != null ? dir.getPath() : xlate.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return failed("results_free_unknown", dir);
        }
        long need = XLATE_BYTES - have + (64L << 20);
        if (free < need) return failed("results_space", need / 1073741824.0, free / 1073741824.0);
        return null;
    }

    /** The phone's memory as Android reports it (ActivityManager totalMem, GiB; an 8 GB phone reports about 7.3), for ShaderWorkers */
    private double totalMemGib() {
        android.app.ActivityManager.MemoryInfo m = new android.app.ActivityManager.MemoryInfo();
        getSystemService(android.app.ActivityManager.class).getMemoryInfo(m);
        return m.totalMem / (double) (1L << 30);
    }

    /** vk_android_build.py's line when the package lacks a shader to build (locate): a delta split's package then gives way to a full split */
    static final String NOT_IN_PACKAGE = "shaders to build are not in the package";   // i18n-keep: the tool's English line, matched
    /** The job's notice then (job.json "notice"; Job.noticeMsg shows it as prepare_notice_split_full: "The quick read of your dump missed a shader this update needs, so
     *  all of the dump's shaders are read now.") */
    static final String FULL_SPLIT_NOTICE = "split_full";

    /** The profile's shader_map.tsv the APK carries (the tools' repository, PackNative.repo) */
    private static File profileMap(Job j) throws IOException { return new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv"); }

    /**
     * null when the split found the shaders the app's profile lists (its shader_map.tsv byte for byte, as a Mac split of the same dump does), else what to do.
     * vk_android_build.py builds the keys of the profile's map from the split's package: another map means a dump with other shaders (modified, or incomplete).
     */
    private String splitMatchesProfile(Job j, File split) {
        if (engine.simulated()) return null;
        // a delta split (shader_split.py DM_SPLIT_DELTA) wrote its marker only after it found each shader the update translates where a full split finds it first:
        // its tables are of the files it read, and the build checks every record it takes by its key (locate)
        if (new File(split, JobInputs.DELTA_MARKER).isFile()) return null;
        File have = new File(split, "shader_map.tsv");
        try {
            File want = profileMap(j);
            if (java.util.Arrays.equals(Files.readAllBytes(want.toPath()), Files.readAllBytes(have.toPath()))) return null;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return failed("compare_failed", e.getMessage());
        }
        try {
            Outputs.delete(split);   // a Retry splits again, from the start
        } catch (IOException e) {
            Log.w(TAG, "split: " + e.getMessage());   // its step is pending: the next run's Outputs.begin tries again
        }
        Job.deleteTree(j.splitScan(this));
        j.restartStep("split");
        // English, with the version's English (Profile.versionName), in job.json's error; the code and its arguments beside it
        return failed("shader_map", j.name(), j.version(), have.getName());
    }

    /** A failure of Prepare's own (PrepareErrors): its code and arguments for job.json (failCode), its English as the step's error */
    private String failed(String code, Object... args) {
        String[] c = PrepareErrors.coded(code, args);
        failCode = c;
        return PrepareErrors.message(c).english();
    }

    /** The failed step's code ({code, argument...}): Prepare's own (PrepareErrors) or the packer's error: line of its last [RED] line (JobErrors.Watch), null for
     *  none; set by step(), read by the loop */
    private volatile String[] failCode;

    /** Reads the step's log as it grows: progress lines into the job, the last [RED] line for a failure's message and the error: line right before it */
    private final class Tail extends Thread {
        private final Job j;
        private final String step;
        private final int index;
        private final File log;
        private volatile boolean done;
        private long pos;
        private final StringBuilder partial = new StringBuilder();
        volatile String lastRed;
        final JobErrors.Watch watch = new JobErrors.Watch();

        Tail(Job j, String step, File log) {
            super("nxpack-tail");
            this.j = j;
            this.step = step;
            this.index = java.util.Arrays.asList(Job.STEPS).indexOf(step);
            this.log = log;
            pos = log.length();
        }

        @Override public void run() {
            while (!done) {
                read();
                SystemClock.sleep(300);
            }
            read();
        }

        void finish() {
            done = true;
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void read() {
            try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
                long len = f.length();
                if (len <= pos) return;
                f.seek(pos);
                byte[] b = new byte[(int) Math.min(len - pos, 1 << 20)];
                f.readFully(b);
                pos += b.length;
                partial.append(new String(b, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            int nl;
            boolean changed = false;
            while ((nl = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, nl).trim();
                partial.delete(0, nl + 1);
                watch.line(line);
                Matcher m = PROGRESS.matcher(line);
                if (m.find()) {
                    long d = Long.parseLong(m.group(1)), t = Long.parseLong(m.group(2));
                    j.stepProgress(step, d, t, m.group(3));
                    Eta est = eta;
                    if (est != null) {
                        boolean wasCarried = est.carriedOver();
                        if (est.progress(index, d, t, m.group(3), SystemClock.elapsedRealtime()))
                            Log.w(TAG, "time left: the translation's total " + t + " is not the profile shader_map.tsv's shader count: its vertex shaders count as much as the others");
                        if (!wasCarried && est.carriedOver()) Log.i(TAG, "time left: going on from the rate the process before measured (carried over)");
                        j.etaCarry(est.carry());   // for a later process (a kill, a pause that ended the process): job.json keeps it with the count
                    }
                    changed = true;
                } else if (line.startsWith("[RED] ")) lastRed = line.substring(6);
                // the tools' word on their subinterpreter workers (vk_android_build.py, shader_split.py): how many, or that they are not usable here and the
                // step runs as before (android/SHADERS.md, The phone's form): in logcat beside the line above that set them
                else if (line.startsWith("subinterpreters: ")) Log.i(TAG, step + ": " + line);
            }
            if (changed) save(j, false);
        }
    }

    // ---- State out: job.json, the notification, the launcher ----

    /** Writes job.json (at most every 2 s unless forced) and tells the notification and the launcher */
    private void save(Job j, boolean force) {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (force || now - lastSave >= 2000) {
                lastSave = now;
                try {
                    j.save();
                } catch (IOException e) {
                    Log.e(TAG, "job.json: " + e);
                }
            }
        }
        main.post(() -> publish(j, force));
    }

    private void publish(Job j, boolean force) {
        if (j == null) {   // no job in this service (a game's start found nothing to pause): the game may start (PrepareGate)
            Message m = Message.obtain(null, MSG_STATE);
            Bundle b = new Bundle();
            b.putBoolean("alive", false);
            b.putBoolean("game_ready", true);
            b.putInt("pid", Process.myPid());
            m.setData(b);
            for (Messenger c : clients) {
                try { c.send(Message.obtain(m)); } catch (RemoteException e) { clients.remove(c); }
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // the time left, asked once here for both (it smooths and rounds as it is asked); none unless the worker runs the job and no pause was asked for
        Eta est = eta;
        boolean stopping = worker != null && stopReason != null;   // a pause was asked for: no time left (the form says pausing only while the tool stops)
        int etaMin = est != null && worker != null && !stopping && "running".equals(j.state()) ? est.minutes(now) : -1;
        if (force || now - lastSend >= 250) {
            lastSend = now;
            Message m = Message.obtain(null, MSG_STATE);
            Bundle b = new Bundle();
            b.putString("job", j.json());
            b.putString("file", j.file.getPath());
            b.putBoolean("alive", worker != null);
            b.putInt("pid", Process.myPid());   // JobWatch: the exit record of this process when it ends
            b.putBoolean("stop_asked", stopping);   // a pause was asked for (JobForm stopAsked)
            b.putBoolean("in_tool", inTool);        // and the tool has not stopped yet (JobForm toolRuns): the card says pausing only then
            b.putInt("eta_min", etaMin);
            // a game about to start may (PrepareGate): no worker, or one that waits for the game in a process that has run no tool
            b.putBoolean("game_ready", GameGate.ready(worker != null, waiting && GameGate.REASON.equals(autoReason), PackNative.ranTool()));
            m.setData(b);
            for (Messenger c : clients) {
                try {
                    c.send(Message.obtain(m));
                } catch (RemoteException e) {
                    clients.remove(c);
                }
            }
        }
        if (worker != null && !noteLeft && (force || now - lastNote >= 1000)) {
            lastNote = now;
            postNote(j, etaMin, true);
        }
    }

    /**
     * Posts the job's notification: the only place that does (with startForeground), deciding its form (note, JobForm) and posting it under noteLock, which
     * the tool's end also takes. live: the worker runs the job and goes on (false: the service is ending, or has no worker)
     */
    private void postNote(Job j, int etaMin, boolean live) {
        synchronized (noteLock) { nm.notify(NOTE, note(j, etaMin, live)); }
    }

    private boolean handle(Message m) {
        switch (m.what) {
            case MSG_REGISTER:
                if (m.replyTo != null) {
                    clients.add(m.replyTo);
                    publish(job, true);
                }
                return true;
            case MSG_UNREGISTER:
                clients.remove(m.replyTo);
                return true;
            default:
                return false;
        }
    }

    private Notification note(Job j) { return note(j, -1, worker != null); }

    /** etaMin: the minutes the running job has left (Eta), -1 for none; live: this service's worker runs the job and goes on (false: the service is ending) */
    private Notification note(Job j, int etaMin, boolean live) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.anka_notify).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        boolean sim = engine.simulated();
        if (j == null) return b.setContentTitle(simulated(getString(R.string.prepare_note_none), sim)).setContentText(getString(R.string.prepare_note_starting)).build();
        String name = j.name();
        String state = j.state();
        int pct = (int) Math.round(j.overall() * 100);
        int i = j.current();
        if (state.equals("done")) return b.setContentTitle(simulated(getString(R.string.prepare_note_done, name), sim))
                .setContentText(getString(R.string.prepare_note_done_text, getString(R.string.prepare_button_play))).setAutoCancel(true).build();
        JobForm.Form form = JobForm.of(state, j.reason(), live, stopReason != null, inTool);   // inTool under noteLock (postNote)
        boolean running = form == JobForm.Form.RUNNING, stopping = form == JobForm.Form.STOPPING;
        boolean waiting = form == JobForm.Form.WAITING;   // the policy's pause: it goes on by itself
        b.setContentTitle(simulated(getString(running ? R.string.prepare_note_running : stopping ? R.string.prepare_note_pausing : waiting ? R.string.prepare_note_waiting
                : form == JobForm.Form.FAILED ? R.string.prepare_note_failed : R.string.prepare_note_paused, name), sim));
        Intent pause = new Intent(this, PackService.class).setAction(ACTION_PAUSE).putExtra(EXTRA_FROM, "notification");
        Notification.Action pauseAction = new Notification.Action.Builder(null, getString(R.string.prepare_button_pause),
                PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        String step = j.stepMsg().text(), steps = getString(R.string.prepare_note_step, i + 1, Job.STEPS.length, pct);
        // The ongoing forms have no subText and no time ("now"): collapsed, Android 12+ shows a notification as one row of title, subText and time (with a
        // progress bar, no text under it), and on HyperOS (the phone check of rc1, 2026-11-15) the title kept 13 characters, "45 dk kaldı ·…", beside "Adım
        // 4 / 5…" and "şimdi". So the row is the title's, which starts with the state; the step line goes into the expanded text
        if (waiting) {
            String reason = Job.reasonMsg(j.reason()).text();
            return b.setContentText(reason).setStyle(new Notification.BigTextStyle().bigText(getString(R.string.prepare_note_progress_why, step, pct, reason)))
                    .setShowWhen(false).setOngoing(true).addAction(pauseAction).build();
        }
        if (running || stopping) {
            long d = j.stepDone(i), t = j.stepTotal(i);
            if (t > 1) b.setProgress((int) Math.min(t, Integer.MAX_VALUE), (int) Math.min(d, t), false);
            else b.setProgress(100, pct, false);
            b.setShowWhen(false).setOngoing(true);
            if (stopping) return b.setContentText(step).setStyle(new Notification.BigTextStyle().bigText(getString(R.string.prepare_note_step_big, steps, step)))
                    .build();   // no time left, no action: the tool stops at the shader in hand
            if (etaMin >= 0) {
                // Collapsed: the state and the time left in short ("Hazırlanıyor · 45 dk"), and the game in the text (shown where a skin shows text beside a
                // bar). Expanded: the usual title with the game, then the step line, the step and the time left in full
                b.setContentTitle(simulated(getString(R.string.prepare_note_running_short, Eta.compactText(etaMin).text()), sim)).setContentText(name)
                        .setStyle(new Notification.BigTextStyle().setBigContentTitle(simulated(getString(R.string.prepare_note_running, name), sim))
                                .bigText(getString(R.string.prepare_note_running_eta_big, steps, step, Eta.text(etaMin).text())));
            } else b.setContentText(step).setStyle(new Notification.BigTextStyle().bigText(getString(R.string.prepare_note_step_big, steps, step)));
            b.addAction(pauseAction);
        } else {
            // a failure: the text of the packer's error code when it has one, else the English error (job.json)
            String why = state.equals("failed") ? j.errorText() : Job.reasonMsg(j.reason()).text();
            b.setContentText(getString(R.string.prepare_note_progress, step, pct))
                    .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.prepare_note_progress_why, step, pct, why))).setOngoing(false);
            Intent resume = new Intent(this, PackService.class).setAction(ACTION_START).putExtra(EXTRA_JOB, j.file.getPath());
            b.addAction(new Notification.Action.Builder(null, getString(state.equals("failed") ? R.string.prepare_button_retry : R.string.prepare_button_resume),
                    PendingIntent.getForegroundService(this, 2, resume, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }

    /** A notification's title, marked when a test build's simulated engine made the pack */
    private String simulated(String title, boolean sim) { return sim ? getString(R.string.prepare_note_simulated, title) : title; }
}
