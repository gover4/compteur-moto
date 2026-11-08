package com.personal.compteur;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final int REQ_LOCATION = 1, REQ_IMPORT = 2, REQ_SAVE = 3, REQ_BACKUP_DIR = 4, REQ_FILE = 5, REQ_SYMJET = 6;
    private ValueCallback<Uri[]> fileCb;
    WebView web;
    String pendingSave, pendingImport;
    boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#050505"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);       // needed for file:///android_asset
        s.setAllowContentAccess(false);
        s.setTextZoom(100);               // keep the meter layout identical whatever the phone's font size
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                // a file picked while Android had closed the app: hand it over once the page exists
                if (pendingImport != null) { String t = pendingImport; pendingImport = null; emit("onImport", t); }
            }
        });
        // lets <input type="file"> pick a receipt photo (gallery or camera app), no permission needed
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(params.createIntent(), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        if (MeterService.running) keepScreenOn(true);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (web != null) web.evaluateJavascript("window.applyTheme && applyTheme()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fullScreen) setFullScreen(true);
        if (web != null) web.evaluateJavascript("window.onResume && onResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.onPause && onPause()", null);
    }

    // ---------- full screen on the meter page ----------
    private boolean fullScreen;

    @SuppressWarnings("deprecation")
    void setFullScreen(boolean on) {
        fullScreen = on;
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c == null) return;
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else c.show(WindowInsets.Type.systemBars());
            } else {
                getWindow().getDecorView().setSystemUiVisibility(on
                        ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        : 0);   // no LAYOUT_* flags, so the keyboard still resizes the page
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreen) setFullScreen(true);
    }

    void keepScreenOn(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    /** Calls fn(text) in the page, with text safely quoted. */
    /** Reads SymJet's backup (photos dropped) and hands log + fuel to the page through onSymJet(text, manual). */
    void readSymJet(boolean manual) {
        String u = getSharedPreferences("symjet", MODE_PRIVATE).getString("uri", null);
        if (u == null) return;
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(u))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 80_000_000) throw new Exception("too big"); out.write(buf, 0, n); }
                JSONObject o = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)), r = new JSONObject();
                for (String k : new String[]{"log", "fuel", "fuelPrice", "currency", "odo", "backupTs"}) if (o.has(k)) r.put(k, o.get(k));
                String js = "window.onSymJet && onSymJet(" + JSONObject.quote(r.toString()) + "," + manual + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            } catch (Exception e) {
                if (manual) emit("toast", "Impossible de lire la sauvegarde SymJet");
                else emit("onSymJetErr", String.valueOf(e.getMessage()));
            }
        }).start();
    }

    void emit(String fn, String text) {
        String js = "window." + fn + " && " + fn + "(" + JSONObject.quote(text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_LOCATION) return;
        // only precise location can measure the distance ("approximative" is not enough)
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        web.evaluateJavascript("onPerm(" + granted + ")", null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri uri = res == RESULT_OK && data != null ? data.getData() : null;
        if (req == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        if (req == REQ_BACKUP_DIR) {
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    Backup.state(this).edit().putString("tree", uri.toString()).remove("err").apply();
                    new Thread(() -> emit("onBackup", Backup.run(this) ? "ok" : "err")).start();
                } catch (Exception e) { emit("toast", "Dossier refusé, choisis-en un autre"); }
            }
            return;
        }
        if (req == REQ_SYMJET) {
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                getSharedPreferences("symjet", MODE_PRIVATE).edit().putString("uri", uri.toString()).apply();
                readSymJet(true);
            }
            return;
        }
        if (req == REQ_IMPORT && uri != null) {
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 5_000_000) throw new Exception("too big");
                        out.write(buf, 0, n);
                    }
                    String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    runOnUiThread(() -> { if (pageReady) emit("onImport", text); else pendingImport = text; });
                } catch (Exception e) {
                    emit("toast", "Impossible de lire le fichier");
                }
            }).start();
        } else if (req == REQ_SAVE) {
            String mem = pendingSave;
            pendingSave = null;
            if (uri != null) new Thread(() -> {
                File f = pendingFile();
                try {
                    // the content is also kept in a cache file, in case Android closed the app while the picker was open
                    String content = mem != null ? mem : readFile(f);
                    if (content == null) {
                        try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                        emit("toast", "Fichier non enregistré, réessayez");
                        return;
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    emit("toast", "Fichier enregistré");
                } catch (Exception e) {
                    emit("toast", "Impossible d'enregistrer le fichier");
                } finally {
                    f.delete();
                }
            }).start();
            else pendingFile().delete();
        }
    }

    File pendingFile() { return new File(getCacheDir(), "pending-save"); }

    void writePending(String content) {
        try (FileOutputStream o = new FileOutputStream(pendingFile())) { o.write(content.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** During a ride, leaving the app shrinks the meter into a floating window (prices stay visible over Maps). */
    void floatMeter() {
        if (Build.VERSION.SDK_INT >= 26 && MeterService.running) {
            try {
                enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(new android.util.Rational(4, 3)).build());
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onUserLeaveHint() { super.onUserLeaveHint(); floatMeter(); }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("handleBack()", v -> { if (!"1".equals(v)) moveTaskToBack(true); });
    }
}
