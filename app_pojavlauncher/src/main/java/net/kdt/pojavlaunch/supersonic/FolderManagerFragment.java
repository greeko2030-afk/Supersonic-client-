package net.kdt.pojavlaunch.supersonic;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import net.kdt.pojavlaunch.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Manages one folder of the selected instance (worlds or resource packs):
 * list, export (zip), import and delete.
 */
public abstract class FolderManagerFragment extends BaseListFragment {

    protected abstract File folder(SupersonicProfile profile);
    protected abstract boolean isWorlds();

    private SupersonicProfile mProfile;
    private File mPendingExport;

    private final ActivityResultLauncher<Intent> mExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (data == null || data.getData() == null || mPendingExport == null) return;
                exportTo(mPendingExport, data.getData());
            });

    private final ActivityResultLauncher<Intent> mImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (data == null || data.getData() == null) return;
                importFrom(data.getData());
            });

    @Override
    protected void onViewReady() {
        if (isWorlds()) {
            setHeader(R.string.supersonic_worlds_title, R.string.supersonic_worlds_subtitle, R.drawable.ic_world);
        } else {
            setHeader(R.string.supersonic_resource_packs_title, R.string.supersonic_resource_packs_subtitle,
                    R.drawable.ic_image_pack);
        }
        reload();
    }

    @Override
    protected RowAdapter.Listener createListener() {
        return new RowAdapter.Listener() {
            @Override
            public void onPrimary(RowAdapter.Row row) {
                File file = (File) row.tag;
                mPendingExport = file;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, row.title + ".zip");
                mExportLauncher.launch(intent);
            }

            @Override
            public void onSecondary(RowAdapter.Row row) {
                File file = (File) row.tag;
                new AlertDialog.Builder(requireContext())
                        .setTitle(row.title)
                        .setMessage("Delete permanently? This cannot be undone.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            setBusy(true);
                            runBg(() -> {
                                deleteRecursively(file);
                                ui(() -> reload());
                            });
                        })
                        .show();
            }
        };
    }

    private void reload() {
        mProfile = SupersonicProfile.current();
        if (mProfile == null) {
            showSummary("No instance selected", null, null);
            setRows(new ArrayList<>(), "Create or select an instance first.");
            return;
        }
        final SupersonicProfile profile = mProfile;
        final File dir = folder(profile);
        setBusy(true);
        runBg(() -> {
            List<RowAdapter.Row> rows = new ArrayList<>();
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File file : files) {
                    if (file.getName().startsWith(".")) continue;
                    if (isWorlds() && !file.isDirectory()) continue;
                    if (!isWorlds() && file.isFile() && !file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                    RowAdapter.Row row = new RowAdapter.Row();
                    row.id = file.getName();
                    row.title = file.getName().replaceAll("(?i)\\.zip$", "");
                    row.subtitle = SupersonicUi.formatBytes(sizeOf(file)) + "  •  "
                            + DateFormat.format("yyyy-MM-dd HH:mm", new Date(file.lastModified()));
                    row.iconRes = isWorlds() ? R.drawable.ic_world : R.drawable.ic_image_pack;
                    row.primaryLabel = "Export";
                    row.secondaryLabel = "Delete";
                    row.tag = file;
                    rows.add(row);
                }
            }
            ui(() -> {
                setBusy(false);
                String noun = isWorlds() ? "world" : "pack";
                showSummary(profile.displayName() + "  •  " + rows.size() + " " + noun + (rows.size() == 1 ? "" : "s"),
                        "Import", v -> pickImport());
                setRows(rows, isWorlds() ? "No worlds in this instance yet." : "No resource packs in this instance yet.");
            });
        });
    }

    private void pickImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                "application/octet-stream"});
        mImportLauncher.launch(intent);
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ export

    private void exportTo(File source, Uri destination) {
        setBusy(true);
        runBg(() -> {
            String message;
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("Cannot open destination");
                if (source.isDirectory()) {
                    try (ZipOutputStream zip = new ZipOutputStream(out)) {
                        zipDirectory(source, source.getName(), zip);
                    }
                } else {
                    copy(new FileInputStream(source), out);
                }
                message = "Exported";
            } catch (Exception e) {
                message = "Export failed: " + e.getMessage();
            }
            final String finalMessage = message;
            ui(() -> {
                setBusy(false);
                toast(finalMessage);
            });
        });
    }

    private static void zipDirectory(File dir, String prefix, ZipOutputStream zip) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = prefix + "/" + child.getName();
            if (child.isDirectory()) {
                zipDirectory(child, name, zip);
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(child)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) > 0) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------ import

    private String displayName(Uri uri) {
        ContentResolver resolver = requireContext().getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "imported.zip";
    }

    private void importFrom(Uri uri) {
        final SupersonicProfile profile = mProfile;
        if (profile == null) return;
        final File dir = folder(profile);
        final String name = displayName(uri);
        final File cacheDir = requireContext().getCacheDir();
        setBusy(true);
        runBg(() -> {
            String message;
            File temp = new File(cacheDir, "import_" + System.currentTimeMillis() + ".zip");
            try {
                try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Cannot read the selected file");
                    copy(in, out);
                }
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
                if (isWorlds()) {
                    importWorld(temp, dir, name);
                } else {
                    File target = new File(dir, name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name : name + ".zip");
                    try (InputStream in = new FileInputStream(temp); OutputStream out = new FileOutputStream(target)) {
                        copy(in, out);
                    }
                }
                message = "Imported";
            } catch (Exception e) {
                message = "Import failed: " + e.getMessage();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            final String finalMessage = message;
            ui(() -> {
                toast(finalMessage);
                reload();
            });
        });
    }

    /** A world zip either has level.dat at its root (we create a folder) or contains the world folder itself. */
    private static void importWorld(File zipFile, File savesDir, String zipName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            boolean levelAtRoot = zip.getEntry("level.dat") != null;
            String base = zipName.replaceAll("(?i)\\.zip$", "");
            File destination = levelAtRoot ? uniqueDir(savesDir, base) : savesDir;
            String canonicalRoot = destination.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File out = new File(destination, entry.getName());
                // Protect against "zip slip" entries like ../../something
                if (!(out.getCanonicalPath() + File.separator).startsWith(canonicalRoot)
                        && !out.getCanonicalPath().equals(destination.getCanonicalPath())) {
                    throw new IOException("Unsafe path in zip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Cannot create " + parent);
                    }
                    try (InputStream in = zip.getInputStream(entry); OutputStream os = new FileOutputStream(out)) {
                        copy(in, os);
                    }
                }
            }
        }
    }

    private static File uniqueDir(File parent, String name) {
        File dir = new File(parent, name);
        int counter = 2;
        while (dir.exists()) dir = new File(parent, name + " (" + counter++ + ")");
        return dir;
    }

    // ------------------------------------------------------------ helpers

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            in.close();
        }
    }

    static long sizeOf(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static List<File> listSafe(File dir) {
        File[] files = dir.listFiles();
        return files == null ? Collections.<File>emptyList() : Arrays.asList(files);
    }
}
