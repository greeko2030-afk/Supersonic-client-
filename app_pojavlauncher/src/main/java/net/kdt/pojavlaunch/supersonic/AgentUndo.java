package net.kdt.pojavlaunch.supersonic;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything SS Agent changes is recorded here first, so that "Undo" can put it back:
 * files are copied to a backup folder before they are overwritten or deleted, renames and settings
 * are remembered. Entries are replayed in reverse order.
 */
public final class AgentUndo {
    private AgentUndo() {}

    private static final String PREF_JOURNAL = "ss_agent_undo2";
    private static final long MAX_BACKUP_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ENTRIES = 300;
    private static final Gson GSON = new Gson();

    private static final String FILE = "file";          // path was changed or deleted; backup holds the old content
    private static final String CREATED = "created";    // path did not exist before; undo deletes it
    private static final String RENAME = "rename";      // path was renamed to "to"
    private static final String PREF = "pref";          // setting "key" had value "old" of type "vtype"

    private static final class Entry {
        String type;
        String path;
        String backup;
        String to;
        String key;
        String old;
        String vtype;
    }

    private static final class Journal {
        List<Entry> entries = new ArrayList<>();
    }

    private static int sBackupCounter = 0;

    // ------------------------------------------------------------------ recording

    /** Call right before a file or folder is changed or deleted. Throws if it is too big to back up. */
    public static synchronized void beforeModify(Context context, File target) throws IOException {
        Entry entry = new Entry();
        entry.path = target.getAbsolutePath();
        if (target.exists()) {
            if (sizeOf(target) > MAX_BACKUP_BYTES) {
                throw new IOException(target.getName() + " is bigger than 100 MB and cannot be backed up, so it was not changed");
            }
            File backup = new File(backupRoot(), (System.currentTimeMillis()) + "_" + (sBackupCounter++) + "_" + target.getName());
            copyRecursive(target, backup);
            entry.type = FILE;
            entry.backup = backup.getAbsolutePath();
        } else {
            entry.type = CREATED;
        }
        add(context, entry);
    }

    /** Call after a new file has been created by something other than {@link #beforeModify}. */
    public static synchronized void recordCreated(Context context, File file) {
        Entry entry = new Entry();
        entry.type = CREATED;
        entry.path = file.getAbsolutePath();
        add(context, entry);
    }

    public static synchronized void recordRename(Context context, File from, File to) {
        Entry entry = new Entry();
        entry.type = RENAME;
        entry.path = from.getAbsolutePath();
        entry.to = to.getAbsolutePath();
        add(context, entry);
    }

    /** @param old the value before the change: Boolean, Integer, Long, Float, String, or null if it did not exist */
    public static synchronized void recordPref(Context context, String key, Object old) {
        Entry entry = new Entry();
        entry.type = PREF;
        entry.key = key;
        if (old == null) {
            entry.vtype = "absent";
        } else if (old instanceof Boolean) {
            entry.vtype = "boolean";
            entry.old = old.toString();
        } else if (old instanceof Integer) {
            entry.vtype = "int";
            entry.old = old.toString();
        } else if (old instanceof Long) {
            entry.vtype = "long";
            entry.old = old.toString();
        } else if (old instanceof Float) {
            entry.vtype = "float";
            entry.old = old.toString();
        } else {
            entry.vtype = "string";
            entry.old = old.toString();
        }
        add(context, entry);
    }

    public static synchronized int count(Context context) {
        return load(context).entries.size();
    }

    // ------------------------------------------------------------------ undo

    /** Undoes everything SS Agent changed, including what the rule based fixes changed. */
    public static String undoEverything(Context context) {
        List<String> parts = new ArrayList<>();
        if (count(context) > 0) parts.add(undoAll(context));
        if (AgentEngine.hasUndo(context)) parts.add(AgentEngine.undoLast(context));
        if (parts.isEmpty()) return "Nothing to undo";
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append("; ");
            out.append(part);
        }
        return out.toString();
    }

    public static synchronized String undoAll(Context context) {
        Journal journal = load(context);
        int done = 0, failed = 0;
        for (int i = journal.entries.size() - 1; i >= 0; i--) {
            Entry entry = journal.entries.get(i);
            try {
                if (undoOne(context, entry)) done++;
            } catch (IOException | RuntimeException e) {
                failed++;
            }
        }
        for (Entry entry : journal.entries) {
            if (entry.backup != null) deleteRecursive(new File(entry.backup));
        }
        save(context, new Journal());
        refresh(context);
        return "Undid " + done + " change" + (done == 1 ? "" : "s")
                + (failed == 0 ? "" : " (" + failed + " could not be undone)");
    }

    private static boolean undoOne(Context context, Entry entry) throws IOException {
        switch (entry.type) {
            case FILE: {
                File target = new File(entry.path);
                if (entry.backup == null || !new File(entry.backup).exists()) return false;
                if (target.exists()) deleteRecursive(target);
                File parent = target.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                copyRecursive(new File(entry.backup), target);
                return true;
            }
            case CREATED: {
                File target = new File(entry.path);
                if (!target.exists()) return false;
                deleteRecursive(target);
                return true;
            }
            case RENAME: {
                File from = new File(entry.path);
                File to = new File(entry.to);
                return to.exists() && !from.exists() && to.renameTo(from);
            }
            case PREF: {
                SharedPreferences prefs = prefs(context);
                SharedPreferences.Editor editor = prefs.edit();
                switch (entry.vtype) {
                    case "absent":
                        editor.remove(entry.key);
                        break;
                    case "boolean":
                        editor.putBoolean(entry.key, Boolean.parseBoolean(entry.old));
                        break;
                    case "int":
                        editor.putInt(entry.key, Integer.parseInt(entry.old));
                        break;
                    case "long":
                        editor.putLong(entry.key, Long.parseLong(entry.old));
                        break;
                    case "float":
                        editor.putFloat(entry.key, Float.parseFloat(entry.old));
                        break;
                    default:
                        editor.putString(entry.key, entry.old);
                }
                editor.apply();
                return true;
            }
            default:
                return false;
        }
    }

    /** Makes the launcher notice restored settings and profiles. */
    static void refresh(Context context) {
        try {
            LauncherPreferences.loadPreferences(context);
            LauncherProfiles.load();
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ storage

    private static SharedPreferences prefs(Context context) {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        return prefs != null ? prefs : PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static Journal load(Context context) {
        try {
            Journal journal = GSON.fromJson(prefs(context).getString(PREF_JOURNAL, ""), Journal.class);
            if (journal != null && journal.entries != null) return journal;
        } catch (RuntimeException ignored) { }
        return new Journal();
    }

    private static void save(Context context, Journal journal) {
        prefs(context).edit().putString(PREF_JOURNAL, journal.entries.isEmpty() ? "" : GSON.toJson(journal)).apply();
    }

    private static void add(Context context, Entry entry) {
        Journal journal = load(context);
        journal.entries.add(entry);
        while (journal.entries.size() > MAX_ENTRIES) {
            Entry old = journal.entries.remove(0);
            if (old.backup != null) deleteRecursive(new File(old.backup));
        }
        save(context, journal);
    }

    private static File backupRoot() throws IOException {
        File root = new File(Tools.DIR_GAME_HOME, "ss_agent_backups");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create the backup folder");
        return root;
    }

    // ------------------------------------------------------------------ file helpers

    static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    static void copyRecursive(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + target);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) copyRecursive(child, new File(target, child.getName()));
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
