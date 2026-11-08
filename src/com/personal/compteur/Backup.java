package com.personal.compteur;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Automatic backup: after every change (debounced), the whole app data is written as
 * compteur-moto-sauvegarde.json into a folder the rider picked once (Storage Access Framework,
 * no storage permission). The file has the same format as "Sauvegarder (fichier)", so it can be restored.
 */
final class Backup {
    static final String FILE = "compteur-moto-sauvegarde.json";
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private Backup() { }

    static SharedPreferences state(Context c) { return c.getSharedPreferences("backup", Context.MODE_PRIVATE); }

    /** Many saves in a row produce one backup a few seconds later. */
    static synchronized void schedule(Context c) {
        Context app = c.getApplicationContext();
        if (state(app).getString("tree", null) == null) return;
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> {
            synchronized (Backup.class) { pending = null; }
            new Thread(() -> run(app)).start();
        };
        handler.postDelayed(pending, 5000);
    }

    static synchronized boolean run(Context c) {
        SharedPreferences st = state(c);
        String tree = st.getString("tree", null);
        if (tree == null) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("app", "compteur-moto");
            o.put("v", 2);
            o.put("exported", System.currentTimeMillis());
            o.put("auto", true);
            for (Map.Entry<String, ?> e : c.getSharedPreferences("compteur", Context.MODE_PRIVATE).getAll().entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String)) continue;
                String s = ((String) v).trim();
                try {
                    if (s.startsWith("{")) o.put(e.getKey(), new JSONObject(s));
                    else if (s.startsWith("[")) o.put(e.getKey(), new JSONArray(s));
                    else if (s.equals("null")) o.put(e.getKey(), JSONObject.NULL);
                    else if (s.equals("true") || s.equals("false")) o.put(e.getKey(), Boolean.parseBoolean(s));
                    else o.put(e.getKey(), Double.parseDouble(s));
                } catch (Exception bad) {
                    o.put(e.getKey(), s);
                }
            }
            byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            Uri file = findOrCreate(c, Uri.parse(tree));
            OutputStream os;
            try { os = c.getContentResolver().openOutputStream(file, "wt"); }
            catch (Exception e) { os = c.getContentResolver().openOutputStream(file, "w"); }
            if (os == null) throw new Exception("fichier inaccessible");
            try (OutputStream out = os) { out.write(bytes); }
            st.edit().putLong("last", System.currentTimeMillis()).remove("err").apply();
            return true;
        } catch (Exception e) {
            st.edit().putString("err", String.valueOf(e.getMessage())).apply();
            return false;
        }
    }

    private static Uri findOrCreate(Context c, Uri tree) throws Exception {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = c.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cur != null && cur.moveToNext())
                if (FILE.equals(cur.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
        }
        Uri created = DocumentsContract.createDocument(c.getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(tree, docId), "application/json", FILE);
        if (created == null) throw new Exception("impossible de créer le fichier");
        return created;
    }

    static String dirName(Context c) {
        String tree = state(c).getString("tree", null);
        if (tree == null) return null;
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            int i = id.indexOf(':');
            String path = i >= 0 ? id.substring(i + 1) : id;
            return path.isEmpty() ? "Mémoire interne" : path;
        } catch (Exception e) {
            return "Dossier choisi";
        }
    }
}
