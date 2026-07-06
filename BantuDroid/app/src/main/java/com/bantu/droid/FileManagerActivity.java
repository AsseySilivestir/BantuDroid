package com.bantu.droid;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * File manager and project browser.
 *
 * Two modes:
 * 1. Project list — shows all Bantu projects (from bantu init + standalone .b files)
 * 2. Directory browser — shows files inside a project, with full navigation
 *
 * Supports:
 * - Open files in editor
 * - Create files and folders
 * - Rename files and folders
 * - Delete files and folders
 * - Run .b files in terminal
 * - Navigate into/out of directories
 * - Auto-refresh when terminal creates/deletes files
 */
public class FileManagerActivity extends AppCompatActivity {

    private BantuEngine engine;
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private TextView tvBreadcrumb;
    private Button btnBack, btnAddFile, btnAddFolder, btnRefresh, btnTree;

    // Navigation state
    private List<BantuEngine.BantuProject> projects = new ArrayList<>();
    private File currentDir = null; // null = project list mode
    private boolean inProjectMode = false;

    // Auto-refresh receiver
    private BroadcastReceiver dataChangedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        engine = new BantuEngine(this);

        recyclerView = findViewById(R.id.recycler_files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        tvBreadcrumb = findViewById(R.id.tv_breadcrumb);
        btnBack = findViewById(R.id.btn_back);
        btnAddFile = findViewById(R.id.btn_add_file);
        btnAddFolder = findViewById(R.id.btn_add_folder);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnTree = findViewById(R.id.btn_tree);

        btnBack.setOnClickListener(v -> navigateUp());
        btnAddFile.setOnClickListener(v -> showCreateFileDialog());
        btnAddFolder.setOnClickListener(v -> showCreateFolderDialog());
        btnRefresh.setOnClickListener(v -> refresh());
        btnTree.setOnClickListener(v -> showTreeDialog());

        // Register for data change notifications from terminal
        dataChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };
        registerReceiver(dataChangedReceiver,
            new IntentFilter("com.bantu.droid.DATA_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (dataChangedReceiver != null) {
            unregisterReceiver(dataChangedReceiver);
        }
        super.onDestroy();
    }

    private void refresh() {
        if (inProjectMode && currentDir != null) {
            loadDirectory(currentDir);
        } else {
            loadProjectList();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Project list mode
    // ──────────────────────────────────────────────────────────────

    private void loadProjectList() {
        inProjectMode = false;
        currentDir = null;
        projects = engine.scanProjects();
        tvBreadcrumb.setText("Bantu Projects");
        btnBack.setVisibility(View.GONE);
        btnAddFolder.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();

        View emptyView = findViewById(R.id.empty_view);
        if (projects.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Directory browser mode
    // ──────────────────────────────────────────────────────────────

    private void loadDirectory(File dir) {
        inProjectMode = true;
        currentDir = dir;
        tvBreadcrumb.setText(getBreadcrumb(dir));
        btnBack.setVisibility(View.VISIBLE);
        btnAddFolder.setVisibility(View.VISIBLE);
        projects.clear();
        adapter.notifyDataSetChanged();

        View emptyView = findViewById(R.id.empty_view);
        if (dir.listFiles() == null || dir.listFiles().length == 0) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private String getBreadcrumb(File dir) {
        String workspace = engine.getWorkspaceRoot().getAbsolutePath();
        String path = dir.getAbsolutePath();
        if (path.equals(workspace)) return "~";
        if (path.startsWith(workspace + "/")) {
            return "~/" + path.substring(workspace.length() + 1);
        }
        return path;
    }

    private void navigateUp() {
        if (!inProjectMode) return;
        File workspace = engine.getWorkspaceRoot();
        if (currentDir.equals(workspace) || currentDir.getParentFile() == null ||
            !currentDir.getAbsolutePath().startsWith(workspace.getAbsolutePath())) {
            loadProjectList();
        } else {
            loadDirectory(currentDir.getParentFile());
        }
    }

    private void openProject(BantuEngine.BantuProject project) {
        if (project.isDirectoryProject()) {
            loadDirectory(project.getDir());
        } else {
            // Standalone .b file — open in terminal
            Intent intent = new Intent(this, TerminalActivity.class);
            intent.putExtra("run_file", project.getName());
            startActivity(intent);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Create file/folder dialogs
    // ──────────────────────────────────────────────────────────────

    private void showCreateFileDialog() {
        if (inProjectMode && currentDir != null) {
            showCreateInDirDialog(false);
        } else {
            // In project list mode — create standalone .b file
            showCreateProjectFileDialog();
        }
    }

    private void showCreateFolderDialog() {
        if (inProjectMode && currentDir != null) {
            showCreateInDirDialog(true);
        }
    }

    private void showCreateProjectFileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Bantu File");

        final EditText input = new EditText(this);
        input.setHint("filename.b");
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a filename", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!name.endsWith(".b")) name += ".b";
            try {
                String template = "# " + name + "\n# Created with BantuDroid\n\nprint(\"Hello from " + name + "\");\n";
                engine.createProject(name, template);
                refresh();
                Toast.makeText(this, "Created " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showCreateInDirDialog(boolean isFolder) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isFolder ? "New Folder" : "New File");

        final EditText input = new EditText(this);
        input.setHint(isFolder ? "folder_name" : "filename.b");
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show();
                return;
            }
            File target = new File(currentDir, name);
            try {
                if (isFolder) {
                    target.mkdirs();
                    Toast.makeText(this, "Created folder: " + name, Toast.LENGTH_SHORT).show();
                } else {
                    if (!name.contains(".")) name += ".b";
                    target = new File(currentDir, name);
                    target.createNewFile();
                    Toast.makeText(this, "Created: " + name, Toast.LENGTH_SHORT).show();
                }
                refresh();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Edit file dialog
    // ──────────────────────────────────────────────────────────────

    private void showEditDialog(File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit: " + file.getName());

        final EditText editor = new EditText(this);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setPadding(32, 24, 32, 24);

        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            editor.setText(sb.toString());
        } catch (Exception e) {
            editor.setText("# Error reading file");
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 800);
        editor.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 0, 24, 0);
        container.addView(editor);
        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            try {
                FileWriter writer = new FileWriter(file);
                writer.write(editor.getText().toString());
                writer.close();
                Toast.makeText(this, "Saved " + file.getName(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Delete confirmation
    // ──────────────────────────────────────────────────────────────

    private void showDeleteDialog(File file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + file.getName() + "?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                deleteRecursive(file);
                refresh();
                Toast.makeText(this, "Deleted " + file.getName(), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ──────────────────────────────────────────────────────────────
    // Rename dialog
    // ──────────────────────────────────────────────────────────────

    private void showRenameDialog(File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename: " + file.getName());

        final EditText input = new EditText(this);
        input.setText(file.getName());
        input.setPadding(48, 24, 48, 24);
        builder.setView(input);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) return;
            File dest = new File(file.getParentFile(), newName);
            if (file.renameTo(dest)) {
                refresh();
                Toast.makeText(this, "Renamed to " + newName, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
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
            if (inProjectMode && currentDir != null) {
                bindDirectoryEntry(holder, position);
            } else {
                bindProjectEntry(holder, position);
            }
        }

        private void bindProjectEntry(FileViewHolder holder, int position) {
            BantuEngine.BantuProject project = projects.get(position);

            holder.tvName.setText(project.getName());
            if (project.isDirectoryProject()) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_view);
                holder.tvSize.setText(project.hasBantuJson() ? "Bantu Project" : "Project");
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_edit);
                holder.tvSize.setText(new java.io.File(project.getPath()).length() + " B");
            }

            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            holder.tvModified.setText(sdf.format(new Date(project.getLastModified())));

            holder.btnRun.setOnClickListener(v -> {
                if (project.isDirectoryProject()) {
                    // cd into project and run bantu run
                    Intent intent = new Intent(FileManagerActivity.this, TerminalActivity.class);
                    intent.putExtra("open_dir", project.getPath());
                    intent.putExtra("run_file", "main.b");
                    startActivity(intent);
                } else {
                    Intent intent = new Intent(FileManagerActivity.this, TerminalActivity.class);
                    intent.putExtra("run_file", project.getName());
                    startActivity(intent);
                }
            });

            holder.btnEdit.setOnClickListener(v -> {
                if (project.isDirectoryProject()) {
                    openProject(project);
                } else {
                    showEditDialog(new java.io.File(project.getPath()));
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                showDeleteDialog(new java.io.File(project.getPath()));
            });

            holder.itemView.setOnClickListener(v -> {
                if (project.isDirectoryProject()) {
                    openProject(project);
                } else {
                    showEditDialog(new java.io.File(project.getPath()));
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                showRenameDialog(new java.io.File(project.getPath()));
                return true;
            });
        }

        private void bindDirectoryEntry(FileViewHolder holder, int position) {
            File[] files = currentDir.listFiles();
            if (files == null) return;

            // Sort: directories first, then files
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            Collections.sort(sorted, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            File file = sorted.get(position);

            holder.tvName.setText(file.getName() + (file.isDirectory() ? "/" : ""));

            if (file.isDirectory()) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_view);
                holder.tvSize.setText("folder");
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_edit);
                holder.tvSize.setText(formatSize(file.length()));
            }

            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            holder.tvModified.setText(sdf.format(new Date(file.lastModified())));

            holder.btnRun.setVisibility(file.getName().endsWith(".b") ? View.VISIBLE : View.GONE);
            holder.btnRun.setOnClickListener(v -> {
                Intent intent = new Intent(FileManagerActivity.this, TerminalActivity.class);
                intent.putExtra("open_dir", currentDir.getAbsolutePath());
                intent.putExtra("run_file", file.getName());
                startActivity(intent);
            });

            holder.btnEdit.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    loadDirectory(file);
                } else {
                    showEditDialog(file);
                }
            });

            holder.btnDelete.setOnClickListener(v -> showDeleteDialog(file));

            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    loadDirectory(file);
                } else if (file.getName().endsWith(".b")) {
                    showEditDialog(file);
                } else {
                    showEditDialog(file);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                showRenameDialog(file);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            if (inProjectMode && currentDir != null) {
                File[] files = currentDir.listFiles();
                return files != null ? files.length : 0;
            } else {
                return projects.size();
            }
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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

    private void showTreeDialog() {
        File target = (currentDir != null) ? currentDir : engine.getWorkspaceRoot();
        if (target == null || !target.exists()) {
            Toast.makeText(this, "No folder to display", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            TreeRenderer renderer = new TreeRenderer(target, false, 10, false);
            final String treeText = renderer.render();
            runOnUiThread(() -> {
                TextView treeView = new TextView(this);
                treeView.setTypeface(android.graphics.Typeface.MONOSPACE);
                treeView.setText(treeText);
                treeView.setTextSize(11f);
                treeView.setTextColor(0xFFE0E0E0);
                treeView.setPadding(48, 32, 48, 32);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(treeView);
                new AlertDialog.Builder(this)
                    .setTitle("Tree - " + target.getName())
                    .setView(scroll)
                    .setPositiveButton("Copy", (d, w) -> {
                        android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData data = android.content.ClipData.newPlainText("tree", treeText);
                        clip.setPrimaryClip(data);
                        Toast.makeText(this, "Tree copied to clipboard", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
            });
        }).start();
    }
}
