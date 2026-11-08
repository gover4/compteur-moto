package com.personal.compteur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Runs the meter in the foreground so it keeps counting with the screen off.
 * Like a taxi meter: above the threshold speed it charges distance, below it charges waiting time.
 * The running ride is saved every few seconds, so a ride survives Android killing the app:
 * the system restarts the service (START_STICKY), or the page offers to resume or finish it.
 */
public class MeterService extends Service implements LocationListener {
    private static final String CHANNEL = "course";
    private static final float MAX_ACCURACY_M = 30;
    static final String PREFS = "meter";

    static volatile boolean running;
    static volatile double distM;
    static volatile long waitMs, startTs, endTs;
    static volatile double speedKmh;
    static volatile float accuracy = -1;
    static volatile double startLat = Double.NaN, startLng, lastLat, lastLng;
    static volatile long lastFixTs;
    /** true when Android restarted the service by itself (in the background, where GPS may not be delivered) */
    static volatile boolean sticky;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private double thr, base, perKm, perMin;
    private Location last;
    private long lastTick;
    private int ticks;

    static void markStarted() {
        distM = 0; waitMs = 0; speedKmh = 0; accuracy = -1; lastFixTs = 0; startLat = Double.NaN;
        startTs = System.currentTimeMillis(); endTs = 0;
        running = true;
    }

    static void markStopped() {
        if (!running) return;
        running = false;
        endTs = System.currentTimeMillis();
    }

    // ---------- saved ride (survives the process being killed) ----------
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static long bits(double d) { return Double.doubleToRawLongBits(d); }
    private static double dbl(SharedPreferences p, String k, double def) { return p.contains(k) ? Double.longBitsToDouble(p.getLong(k, 0)) : def; }

    /** Writes the running ride and its tariff to disk. */
    static void save(Context c, double thr, double base, double perKm, double perMin) {
        prefs(c).edit()
                .putBoolean("running", running)
                .putLong("startTs", startTs).putLong("savedTs", System.currentTimeMillis())
                .putLong("distM", bits(distM)).putLong("waitMs", waitMs)
                .putLong("startLat", bits(startLat)).putLong("startLng", bits(startLng))
                .putLong("lastLat", bits(lastLat)).putLong("lastLng", bits(lastLng))
                .putLong("thr", bits(thr)).putLong("base", bits(base)).putLong("km", bits(perKm)).putLong("min", bits(perMin))
                .apply();
    }

    /** True when a ride was still running when the app last stopped unexpectedly. */
    static boolean hasSaved(Context c) { return prefs(c).getBoolean("running", false); }

    /** Forgets the saved ride (a ride ended normally, or was finished after an interruption). */
    static void clearSaved(Context c) { prefs(c).edit().putBoolean("running", false).commit(); }

    /** The saved ride as fields for the page: distM, waitMs, elapsedMs, startTs, a, b. */
    static void putSaved(Context c, JSONObject o) throws JSONException {
        SharedPreferences p = prefs(c);
        long start = p.getLong("startTs", 0), saved = p.getLong("savedTs", start);
        o.put("distM", dbl(p, "distM", 0));
        o.put("waitMs", p.getLong("waitMs", 0));
        o.put("elapsedMs", Math.max(0, saved - start));
        o.put("startTs", start);
        double sLat = dbl(p, "startLat", Double.NaN);
        if (!Double.isNaN(sLat)) {
            o.put("a", sLat + "," + dbl(p, "startLng", 0));
            o.put("b", dbl(p, "lastLat", 0) + "," + dbl(p, "lastLng", 0));
        }
    }

    /** Loads the saved ride back into memory so the service can keep counting. */
    static boolean restore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("running", false)) return false;
        distM = dbl(p, "distM", 0);
        waitMs = p.getLong("waitMs", 0);
        startTs = p.getLong("startTs", System.currentTimeMillis());
        endTs = 0;
        startLat = dbl(p, "startLat", Double.NaN);
        startLng = dbl(p, "startLng", 0);
        lastLat = dbl(p, "lastLat", 0);
        lastLng = dbl(p, "lastLng", 0);
        speedKmh = 0; accuracy = -1; lastFixTs = 0;
        running = true;
        return true;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) {
            // restarted by Android after the process was killed: continue the saved ride, if any
            if (!running && !restore(this)) { stopSelf(); return START_NOT_STICKY; }
            sticky = true;
            SharedPreferences p = prefs(this);
            thr = dbl(p, "thr", 10); base = dbl(p, "base", 0.5); perKm = dbl(p, "km", 0.9); perMin = dbl(p, "min", 0.15);
        } else {
            thr = i.getDoubleExtra("thr", 10);
            base = i.getDoubleExtra("base", 0.5);
            perKm = i.getDoubleExtra("km", 0.9);
            perMin = i.getDoubleExtra("min", 0.15);
            sticky = false;
            if (!running) markStarted();
        }
        last = null;
        save(this, thr, base, perKm, perMin);

        createChannel();
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(1, n);
            lm.removeUpdates(this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (RuntimeException e) {
            // Android refused (no location permission, or a background restart is not allowed on this
            // version): keep the saved ride on disk; the page offers to resume or finish it when opened.
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        lastTick = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    /** Every second: waiting time accrues while slower than the threshold (or with no recent GPS fix). */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            long dt = now - lastTick;
            lastTick = now;
            // no fix for 5 s: bill waiting, and never bill the same gap again as distance later
            if (System.currentTimeMillis() - lastFixTs > 5000) { speedKmh = 0; last = null; }
            if (speedKmh < thr) waitMs += dt;
            if (++ticks % 5 == 0) {
                save(MeterService.this, thr, base, perKm, perMin);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(1, notification());
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onLocationChanged(Location loc) {
        if (!running) return;
        accuracy = loc.getAccuracy();
        lastFixTs = System.currentTimeMillis();
        // an imprecise fix can't be trusted for distance: count it like standing still (waiting time),
        // otherwise the last good speed would stay above the threshold and nothing would be charged
        if (loc.getAccuracy() > MAX_ACCURACY_M) {
            speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0;
            if (speedKmh < thr) last = null;   // this time is billed as waiting: don't add its distance afterwards
            return;
        }
        double d = last == null ? 0 : last.distanceTo(loc);
        if (last != null) {
            double dtS = Math.max(0.5, (loc.getTime() - last.getTime()) / 1000.0);
            if (d / dtS * 3.6 > 160 || (dtS <= 1.5 && d > 300)) return;   // impossible jump: ignore this fix
        }
        if (Double.isNaN(startLat)) { startLng = loc.getLongitude(); startLat = loc.getLatitude(); }
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        double v;
        if (loc.hasSpeed()) v = loc.getSpeed() * 3.6;
        else if (last != null && d > loc.getAccuracy()) v = d / Math.max(1, (loc.getTime() - last.getTime()) / 1000.0) * 3.6;
        else v = 0;   // movement within the GPS error: treat as standing still
        speedKmh = v;
        if (last != null && v >= thr) distM += d;
        last = loc;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Course en cours", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Compteur actif pendant une course");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification() {
        long min = Math.max(0, System.currentTimeMillis() - startTs) / 60000;
        String text = String.format(Locale.FRANCE, "%.2f km · %d min", distM / 1000, min);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Course en cours")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        // A normal stop has already cleared the saved ride (Bridge.stop). If Android destroys the
        // service on its own, the saved ride stays on disk so the page can offer to resume it.
        if (running) save(this, thr, base, perKm, perMin);
        markStopped();
        handler.removeCallbacks(tick);
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        lm.removeUpdates(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
