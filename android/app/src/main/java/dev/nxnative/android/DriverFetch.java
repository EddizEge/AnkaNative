package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * Driver download (GpuDriver's "Install…" → "Download…"): lists the .zip assets of the public GitHub releases of the repositories below and, only when the user taps
 * one, downloads it into the cache, installs it through GpuDriver.install and picks it. Nothing is fetched in the background or without a tap; no driver is bundled.
 * The repositories' own licences apply to what they publish (android/NOTICE.md).
 */
final class DriverFetch {
    private DriverFetch() {}

    /** The one list of driver repositories: { GitHub owner/name, label } */
    static final String[][] REPOS = {   // proper names, never translated
        {"MrPurple666/purple-turnip", "Mr. Purple Turnip"},   // i18n-keep: a repository's name
        {"K11MCH1/AdrenoToolsDrivers", "KIMCHI Turnip"},   // i18n-keep: a repository's name
        {"whitebelyash/freedreno_turnip-CI", "whitebelyash Turnip CI"},   // i18n-keep: a repository's name
        {"Weab-chan/freedreno_turnip-CI", "Weab-chan Freedreno CI"},   // i18n-keep: a repository's name
        {"crueter/GameHub-8Elite-Drivers", "GameHub Adreno 8xx"},   // i18n-keep: a repository's name
    };

    private static final long MAX_ZIP = 512L << 20;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static final class Asset {
        final String repo, tag, name, url; final long size;
        Asset(String repo, String tag, String name, String url, long size) { this.repo = repo; this.tag = tag; this.name = name; this.url = url; this.size = size; }
        /** The release's tag, then the zip's name and size (UpdateCheck.mb) */
        String label() { return Tr.s("settings_driverfetch_asset", tag, name, UpdateCheck.mb(size)); }
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new LocalizedIOException("settings_net_not_https", url);
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", "AnkaNative-driver-fetch");
        c.setRequestProperty("Accept", accept);   // i18n-keep: an HTTP header
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);   // release assets redirect to GitHub's object storage (https to https)
        int code = c.getResponseCode();
        if (code == 403 || code == 429) { c.disconnect(); throw new LocalizedIOException("settings_net_rate_limit"); }
        if (code != 200) { c.disconnect(); throw new LocalizedIOException("settings_net_http", code); }
        return c;
    }

    /** Why a list or a download failed, for the screen (in the user's language) */
    private static String why(Throwable e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException) return Tr.s("settings_net_no_network");
        if (e instanceof SocketTimeoutException) return Tr.s("settings_net_timeout");
        if (e instanceof JSONException) return Tr.s("settings_net_bad_answer", e.getMessage());
        String m = e.getLocalizedMessage();   // this class's and GpuDriver.install's reasons (no meta.json, …): LocalizedIOException
        return m != null ? m : e.getClass().getSimpleName();
    }

    /** The .zip assets of a repository's releases, newest release first (network: call off the UI thread) */
    static List<Asset> list(String repo) throws IOException, JSONException {
        HttpsURLConnection c = open("https://api.github.com/repos/" + repo + "/releases?per_page=30", "application/vnd.github+json");
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(Streams.readAll(in), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
        List<Asset> out = new ArrayList<>();
        JSONArray rel = new JSONArray(body);
        for (int k = 0; k < rel.length(); k++) {
            JSONObject r = rel.getJSONObject(k);
            if (r.optBoolean("draft")) continue;
            String tag = r.optString("tag_name", r.optString("name", "?"));
            JSONArray as = r.optJSONArray("assets");
            if (as == null) continue;
            for (int j = 0; j < as.length(); j++) {
                JSONObject a = as.getJSONObject(j);
                String n = a.optString("name", ""), url = a.optString("browser_download_url", "");
                if (n.toLowerCase(Locale.ROOT).endsWith(".zip") && url.startsWith("https://")) out.add(new Asset(repo, tag, n, url, a.optLong("size", 0)));
            }
        }
        return out;
    }

    /** Downloads the asset into the cache, installs it and returns the driver (network: call off the UI thread) */
    static GpuDriver.Info fetch(Activity a, Asset as) throws IOException {
        if (as.size > MAX_ZIP) throw new LocalizedIOException("settings_driverfetch_zip_too_large");
        File tmp = File.createTempFile("driver-", ".zip", a.getCacheDir());
        try {
            HttpsURLConnection c = open(as.url, "application/octet-stream");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; long n = 0; int k;
                while ((k = in.read(b)) > 0) { if ((n += k) > MAX_ZIP) throw new LocalizedIOException("settings_driverfetch_download_too_large"); out.write(b, 0, k); }
            } finally { c.disconnect(); }
            try (InputStream in = new FileInputStream(tmp)) { return GpuDriver.install(a, in, as.name.replaceAll("(?i)(\\.adpkg)?\\.zip$", "")); }
        } finally { tmp.delete(); }
    }

    private static boolean gone(Activity a) { return a.isFinishing() || a.isDestroyed(); }

    private static AlertDialog busy(Activity a, String msg) {
        AlertDialog d = new AlertDialog.Builder(a).setMessage(msg).setCancelable(false).create();
        d.show();
        return d;
    }

    /** "Download…": the repositories, then a repository's zips; a tap on a zip downloads, installs and picks it, then reopens the driver dialog (request = its picker's request code) */
    static void dialog(Activity a, int request) {
        String[] labels = new String[REPOS.length];
        for (int k = 0; k < REPOS.length; k++) labels[k] = REPOS[k][1] + "\n  github.com/" + REPOS[k][0];   // i18n-keep: a name and its link
        new AlertDialog.Builder(a).setTitle(a.getString(R.string.settings_driverfetch_title))
                .setItems(labels, (d, w) -> releases(a, REPOS[w][0], request))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void releases(Activity a, String repo, int request) {
        AlertDialog wait = busy(a, a.getString(R.string.settings_driverfetch_reading, repo));
        new Thread(() -> {
            List<Asset> l = null; String err = null;
            try { l = list(repo); } catch (Throwable e) { err = why(e); }   // nothing on this thread may end the app
            final List<Asset> got = l; final String fail = err;
            UI.post(() -> {
                if (gone(a)) return;
                wait.dismiss();
                if (fail != null) { Toast.makeText(a, a.getString(R.string.settings_driverfetch_list_failed, repo, fail), Toast.LENGTH_LONG).show(); return; }
                if (got.isEmpty()) { Toast.makeText(a, a.getString(R.string.settings_driverfetch_no_zips, repo), Toast.LENGTH_LONG).show(); return; }
                String[] labels = new String[got.size()];
                for (int k = 0; k < got.size(); k++) labels[k] = got.get(k).label();
                new AlertDialog.Builder(a).setTitle(repo)
                        .setItems(labels, (d, w) -> download(a, got.get(w), request))
                        .setNegativeButton(a.getString(R.string.settings_back), (d, w) -> dialog(a, request)).show();
            });
        }, "driver-list").start();
    }

    private static void download(Activity a, Asset as, int request) {
        AlertDialog wait = busy(a, a.getString(R.string.settings_driverfetch_downloading, as.name));
        new Thread(() -> {
            GpuDriver.Info i = null; String err = null;
            try { i = fetch(a, as); } catch (Throwable e) { err = why(e); }   // a damaged zip throws unchecked exceptions too (DriverZip turns them into reasons)
            final GpuDriver.Info got = i; final String fail = err;
            if (got != null) GpuDriver.select(a, got);
            UI.post(() -> {
                if (gone(a)) return;
                wait.dismiss();
                if (fail != null) { Toast.makeText(a, a.getString(R.string.settings_driverfetch_failed, fail), Toast.LENGTH_LONG).show(); return; }
                Toast.makeText(a, a.getString(R.string.settings_driverfetch_installed, got.label()), Toast.LENGTH_LONG).show();
                GpuDriver.dialog(a, request);
            });
        }, "driver-download").start();
    }
}
