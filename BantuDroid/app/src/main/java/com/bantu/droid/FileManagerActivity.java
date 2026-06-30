package com.bantu.droid;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * File manager with real filesystem navigation.
 *
 * Features:
 * - Browse actual directories on the device
 * - Navigate up (..) and into subdirectories
 * - Show current path as breadcrumb
 * - Create files and folders
 * - Edit text files
 * - Run .b files in terminal
 * - Delete files/folders
 * - Show file sizes and dates
 */
public class FileManagerActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<FileItem> items = new ArrayList<>();
    private File currentDir;
    private TextView tvPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        // Start at app files directory
        currentDir = getFilesDir();

        tvPath = findViewById(R.id.tv_current_path);
        recyclerView = findViewById(R.id.recycler_files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_add_file).setOnClickListener(v -> showCreateDialog());
        findViewById(R.id.btn_add_folder).setOnClickListener(v -> showCreateFolderDialog());

        refreshFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    private void refreshFiles() {
        items.clear();

        // Update path display
        String path = currentDir.getAbsolutePath();
        String home = getFilesDir().getAbsolutePath();
        if (path.startsWith(home)) {
            path = "~" + path.substring(home.length());
        }
        tvPath.setText(path);

        // Add parent directory entry (..) if not at root
        if (currentDir.getParent() != null) {
            items.add(new FileItem(currentDir.getParentFile(), true)); // isUpNav
        }

        // List files
        File[] files = currentDir.listFiles();
        if (files != null) {
            // Sort: directories first, then files
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (File f : files) {
                // Skip hidden files
                if (!f.getName().startsWith(".")) {
                    items.add(new FileItem(f, false));
                }
            }
        }

        adapter.notifyDataSetChanged();

        // Show empty state
        View emptyView = findViewById(R.id.empty_view);
        if (items.isEmpty() || (items.size() == 1 && items.get(0).isUpNav)) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void navigateTo(File dir) {
        if (dir != null && dir.isDirectory() && dir.canRead()) {
            currentDir = dir;
            refreshFiles();
            recyclerView.scrollToPosition(0);
        } else {
            Toast.makeText(this, "Cannot access: " + dir, Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Create file
    // ──────────────────────────────────────────────────────────────

    private void showCreateDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New File");

        final EditText input = new EditText(this);
        input.setHint("filename.b");
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a filename", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                File newFile = new File(currentDir, name);
                if (newFile.createNewFile()) {
                    // If .b file, add template
                    if (name.endsWith(".b")) {
                        String template = "# " + name + "\n# Created with BantuDroid\n\nprint(\"Hello from " + name + "\");\n";
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(newFile);
                        fos.write(template.getBytes());
                        fos.close();
                    }
                    refreshFiles();
                    Toast.makeText(this, "Created: " + name, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "File already exists", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Create folder
    // ──────────────────────────────────────────────────────────────

    private void showCreateFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Folder");

        final EditText input = new EditText(this);
        input.setHint("folder name");
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a folder name", Toast.LENGTH_SHORT).show();
                return;
            }

            File newDir = new File(currentDir, name);
            if (newDir.mkdirs()) {
                refreshFiles();
                Toast.makeText(this, "Created folder: " + name, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Folder already exists or error", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Edit file
    // ──────────────────────────────────────────────────────────────

    private void showEditDialog(File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit: " + file.getName());

        final EditText editor = new EditText(this);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setPadding(32, 24, 32, 24);

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            editor.setText(sb.toString());
        } catch (Exception e) {
            editor.setText("# Error reading file: " + e.getMessage());
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 800
        );
        editor.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 0, 24, 0);
        container.addView(editor);

        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                fos.write(editor.getText().toString().getBytes());
                fos.close();
                Toast.makeText(this, "Saved " + file.getName(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────────────────────

    private void showDeleteDialog(File file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + file.getName() + "?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                if (file.isDirectory()) {
                    deleteRecursive(file);
                } else {
                    file.delete();
                }
                refreshFiles();
                Toast.makeText(this, "Deleted " + file.getName(), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    // ──────────────────────────────────────────────────────────────
    // File item model
    // ──────────────────────────────────────────────────────────────

    private static class FileItem {
        final File file;
        final boolean isUpNav; // ".." entry

        FileItem(File file, boolean isUpNav) {
            this.file = file;
            this.isUpNav = isUpNav;
        }

        boolean isDirectory() {
            return isUpNav || file.isDirectory();
        }

        String getName() {
            return isUpNav ? ".." : file.getName();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RecyclerView adapter
    // ──────────────────────────────────────────────────────────────

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

        @Override
        public FileViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(FileViewHolder holder, int position) {
            FileItem item = items.get(position);

            if (item.isUpNav) {
                // Parent directory entry
                holder.tvName.setText("..");
                holder.tvSize.setText("");
                holder.tvModified.setText("Parent directory");
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_revert);
                holder.btnRun.setVisibility(View.GONE);

                holder.itemView.setOnClickListener(v -> navigateTo(item.file));
                holder.btnEdit.setVisibility(View.GONE);
                holder.btnDelete.setVisibility(View.GONE);
                return;
            }

            File file = item.file;
            holder.tvName.setText(file.getName() + (file.isDirectory() ? "/" : ""));

            if (file.isDirectory()) {
                holder.tvSize.setText("folder");
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_view);
                holder.btnRun.setVisibility(View.GONE);
            } else {
                holder.tvSize.setText(formatSize(file.length()));
                // Icon based on file type
                if (file.getName().endsWith(".b")) {
                    holder.ivIcon.setImageResource(android.R.drawable.ic_menu_edit);
                } else if (file.getName().endsWith(".html") || file.getName().endsWith(".css") || file.getName().endsWith(".js")) {
                    holder.ivIcon.setImageResource(android.R.drawable.ic_menu_compass);
                } else {
                    holder.ivIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                }

                // Run button only for .b files
                if (file.getName().endsWith(".b")) {
                    holder.btnRun.setVisibility(View.VISIBLE);
                    holder.btnRun.setOnClickListener(v -> {
                        Intent intent = new Intent(FileManagerActivity.this, TerminalActivity.class);
                        intent.putExtra("run_file", file.getName());
                        startActivity(intent);
                    });
                } else {
                    holder.btnRun.setVisibility(View.GONE);
                }
            }

            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            holder.tvModified.setText(sdf.format(new Date(file.lastModified())));

            // Click to navigate into directory
            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    navigateTo(file);
                } else {
                    // Open file for editing if it's a text file
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".b") || name.endsWith(".txt") || name.endsWith(".html") ||
                        name.endsWith(".css") || name.endsWith(".js") || name.endsWith(".json") ||
                        name.endsWith(".xml") || name.endsWith(".md") || name.endsWith(".py") ||
                        name.endsWith(".sh") || name.endsWith(".cfg") || name.endsWith(".ini") ||
                        name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".conf")) {
                        showEditDialog(file);
                    } else {
                        Toast.makeText(FileManagerActivity.this,
                            file.getName() + " (" + formatSize(file.length()) + ")",
                            Toast.LENGTH_SHORT).show();
                    }
                }
            });

            // Long press for options
            holder.itemView.setOnLongClickListener(v -> {
                if (!file.isDirectory()) {
                    showEditDialog(file);
                }
                return true;
            });

            holder.btnEdit.setOnClickListener(v -> showEditDialog(file));
            holder.btnDelete.setOnClickListener(v -> showDeleteDialog(file));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class FileViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName, tvSize, tvModified;
            Button btnRun, btnEdit, btnDelete;

            FileViewHolder(View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_file_icon);
                tvName = itemView.findViewById(R.id.tv_file_name);
                tvSize = itemView.findViewById(R.id.tv_file_size);
                tvModified = itemView.findViewById(R.id.tv_file_modified);
                btnRun = itemView.findViewById(R.id.btn_file_run);
                btnEdit = itemView.findViewById(R.id.btn_file_edit);
                btnDelete = itemView.findViewById(R.id.btn_file_delete);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────────────────────

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
