package com.personal.compteur;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Exposed to the page as window.Android. */
public class Bridge {
    private final MainActivity act;

    Bridge(MainActivity act) { this.act = act; }

    @JavascriptInterface
    public String get(String key) { return act.getSharedPreferences("compteur", Context.MODE_PRIVATE).getString(key, null); }

    @JavascriptInterface
    public void set(String key, String value) {
        act.getSharedPreferences("compteur", Context.MODE_PRIVATE).edit().putString(key, value).apply();
        Backup.schedule(act);
    }

    /** Durable write for money records (rides, shifts, expenses, restore): on disk before returning. */
    @JavascriptInterface
    public boolean setSync(String key, String value) {
        boolean ok = act.getSharedPreferences("compteur", Context.MODE_PRIVATE).edit().putString(key, value).commit();
        Backup.schedule(act);
        return ok;
    }

    // ---------- automatic backup folder ----------
    @JavascriptInterface
    public void pickBackupDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        act.runOnUiThread(() -> act.startActivityForResult(i, MainActivity.REQ_BACKUP_DIR));
    }

    @JavascriptInterface
    public String backupStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("dir", Backup.dirName(act));
            o.put("last", Backup.state(act).getLong("last", 0));
            o.put("err", Backup.state(act).getString("err", null));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public void backupNow() { new Thread(() -> act.emit("onBackup", Backup.run(act) ? "ok" : "err")).start(); }

    @JavascriptInterface
    public boolean hasPerm() {
        return act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void askPerm() {
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        act.runOnUiThread(() -> act.requestPermissions(perms, MainActivity.REQ_LOCATION));
    }

    @JavascriptInterface
    public boolean gpsOn() {
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    @JavascriptInterface
    public void openGps() {
        act.runOnUiThread(() -> act.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
    }

    /** Opens a maps link in the Google Maps app, or in any app that can show it if Maps isn't installed. */
    @JavascriptInterface
    public void openUrl(String url) {
        act.runOnUiThread(() -> {
            act.floatMeter();
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(new Intent(i).setPackage("com.google.android.apps.maps"));
            } catch (ActivityNotFoundException e) {
                try { act.startActivity(i); } catch (ActivityNotFoundException e2) {
                    // no Maps app: fall back to directions in the browser
                    if (url.startsWith("google.navigation:q=")) {
                        String d = url.substring(20).split("&")[0];
                        try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=" + d)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (ActivityNotFoundException ignored) { }
                    }
                }
            }
        });
    }

    private void launch(double thresholdKmh, double base, double perKm, double perMin) {
        Intent i = new Intent(act, MeterService.class)
                .putExtra("thr", thresholdKmh).putExtra("base", base).putExtra("km", perKm).putExtra("min", perMin);
        if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
        else act.startService(i);
        act.keepScreenOn(true);
    }

    /** Starts a new ride from zero. */
    @JavascriptInterface
    public void start(double thresholdKmh, double base, double perKm, double perMin) {
        MeterService.markStarted();
        launch(thresholdKmh, base, perKm, perMin);
    }

    /** Continues a ride that was interrupted when Android closed the app. */
    @JavascriptInterface
    public boolean resume(double thresholdKmh, double base, double perKm, double perMin) {
        if (!MeterService.running && !MeterService.restore(act)) return false;
        launch(thresholdKmh, base, perKm, perMin);
        return true;
    }

    /** Ends the ride (or discards an interrupted one) and forgets the saved copy. */
    @JavascriptInterface
    public void stop() {
        MeterService.markStopped();
        MeterService.clearSaved(act);
        act.stopService(new Intent(act, MeterService.class));
        // the screen stays on while the client reads the receipt; the page turns it off (keepAwake)
    }

    /** Keeps the screen on while the client looks at the meter or the receipt. */
    @JavascriptInterface
    public void keepAwake(boolean on) { act.keepScreenOn(on); }

    /** Opens this app's page in Android settings (to allow precise location). */
    @JavascriptInterface
    public void openAppSettings() {
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", act.getPackageName(), null)));
            } catch (ActivityNotFoundException ignored) { }
        });
    }

    @JavascriptInterface
    public String state() {
        JSONObject o = new JSONObject();
        try {
            if (!MeterService.running && MeterService.hasSaved(act)) {
                // the app was closed during a ride: report what was counted so the page can resume or finish it
                o.put("running", false);
                o.put("interrupted", true);
                o.put("speed", 0);
                o.put("acc", -1);
                MeterService.putSaved(act, o);
                return o.toString();
            }
            long end = MeterService.running ? System.currentTimeMillis() : MeterService.endTs;
            o.put("running", MeterService.running);
            o.put("sticky", MeterService.running && MeterService.sticky);
            o.put("distM", MeterService.distM);
            o.put("waitMs", MeterService.waitMs);
            o.put("elapsedMs", MeterService.startTs > 0 ? Math.max(0, end - MeterService.startTs) : 0);
            o.put("startTs", MeterService.startTs);
            o.put("speed", MeterService.running ? MeterService.speedKmh : 0);
            o.put("acc", MeterService.accuracy);
            o.put("fixAge", MeterService.lastFixTs > 0 ? System.currentTimeMillis() - MeterService.lastFixTs : -1);
            // first and last good GPS points, so a finished ride can be shown in Google Maps
            if (!Double.isNaN(MeterService.startLat)) {
                o.put("a", MeterService.startLat + "," + MeterService.startLng);
                o.put("b", MeterService.lastLat + "," + MeterService.lastLng);
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** Full screen on the meter page (hides the status bar and notification icons from the client). */
    @JavascriptInterface
    public void immersive(boolean on) { act.setFullScreen(on); }

    /** Opens the old Caisse Moto app so the rider can save its backup file. False if it isn't installed. */
    @JavascriptInterface
    public boolean openCaisse() {
        Intent i = act.getPackageManager().getLaunchIntentForPackage("com.personal.caisse");
        if (i == null) return false;
        act.runOnUiThread(() -> { try { act.startActivity(i); } catch (ActivityNotFoundException ignored) { } });
        return true;
    }

    /** True when the phone is in dark mode (for the Auto theme). */
    @JavascriptInterface
    public boolean nightMode() {
        return (act.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Lets the rider pick symjet-backup.json once; Compteur Moto keeps read access to it. */
    @JavascriptInterface
    public void linkSymJet() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        act.runOnUiThread(() -> act.startActivityForResult(i, MainActivity.REQ_SYMJET));
    }

    /** Reads the linked SymJet backup again (result through onSymJet). False when no file is linked. */
    @JavascriptInterface
    public boolean syncSymJet(boolean manual) {
        if (act.getSharedPreferences("symjet", Context.MODE_PRIVATE).getString("uri", null) == null) return false;
        act.readSymJet(manual);
        return true;
    }

    /** Opens the SymJet maintenance app. False if it isn't installed. */
    @JavascriptInterface
    public boolean openSymJet() {
        Intent i = act.getPackageManager().getLaunchIntentForPackage("com.personal.symjet");
        if (i == null) return false;
        act.runOnUiThread(() -> { try { act.startActivity(i); } catch (ActivityNotFoundException ignored) { } });
        return true;
    }

    // ---------- files (from Caisse Moto) ----------

    /** Opens the share sheet (WhatsApp, Messages…) with plain text. */
    @JavascriptInterface
    public void share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        act.runOnUiThread(() -> act.startActivity(Intent.createChooser(i, null)));
    }

    /** Lets the user choose where to save a file (CSV export, backup). */
    @JavascriptInterface
    public void saveFile(String name, String mime, String content) {
        act.pendingSave = content;
        act.writePending(content);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        act.runOnUiThread(() -> act.startActivityForResult(i, MainActivity.REQ_SAVE));
    }

    /** Lets the user pick a backup file; its text comes back through onImport(text). */
    @JavascriptInterface
    public void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        act.runOnUiThread(() -> act.startActivityForResult(i, MainActivity.REQ_IMPORT));
    }

    @JavascriptInterface
    public String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
