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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * File manager for .b project files.
 * Users can browse, create, edit, run, delete, and share .b files.
 */
public class FileManagerActivity extends AppCompatActivity {

    private BantuEngine engine;
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<BantuEngine.BantuFile> files = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        engine = new BantuEngine(this);

        recyclerView = findViewById(R.id.recycler_files);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_add_file).setOnClickListener(v -> showCreateDialog());

        refreshFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    private void refreshFiles() {
        files = engine.listProjects();
        adapter.notifyDataSetChanged();

        // Show empty state
        View emptyView = findViewById(R.id.empty_view);
        if (files.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Create new file dialog
    // ──────────────────────────────────────────────────────────────

    private void showCreateDialog() {
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
                refreshFiles();
                Toast.makeText(this, "Created " + name, Toast.LENGTH_SHORT).show();
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

    private void showEditDialog(BantuEngine.BantuFile bantuFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit: " + bantuFile.getName());

        final EditText editor = new EditText(this);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setPadding(32, 24, 32, 24);

        try {
            String content = engine.readProject(bantuFile.getName());
            editor.setText(content);
        } catch (Exception e) {
            editor.setText("# Error reading file");
        }

        // Make dialog larger
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            800
        );
        editor.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 0, 24, 0);
        container.addView(editor);

        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            try {
                engine.createProject(bantuFile.getName(), editor.getText().toString());
                Toast.makeText(this, "Saved " + bantuFile.getName(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────────────────────────────────────────────────────────
    // Delete file confirmation
    // ──────────────────────────────────────────────────────────────

    private void showDeleteDialog(BantuEngine.BantuFile bantuFile) {
        new AlertDialog.Builder(this)
            .setTitle("Delete " + bantuFile.getName() + "?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                engine.deleteProject(bantuFile.getName());
                refreshFiles();
                Toast.makeText(this, "Deleted " + bantuFile.getName(), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
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
            BantuEngine.BantuFile file = files.get(position);

            holder.tvName.setText(file.getName());
            holder.tvSize.setText(file.getSizeFormatted());

            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());
            holder.tvModified.setText(sdf.format(new Date(file.getLastModified())));

            // File icon based on type
            if (file.getName().contains("server") || file.getName().contains("ddns")) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_share);
            } else if (file.getName().contains("db") || file.getName().contains("data")) {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda);
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_edit);
            }

            // Run button
            holder.btnRun.setOnClickListener(v -> {
                Intent intent = new Intent(
                    FileManagerActivity.this, TerminalActivity.class);
                intent.putExtra("run_file", file.getName());
                startActivity(intent);
            });

            // Edit button
            holder.btnEdit.setOnClickListener(v -> showEditDialog(file));

            // Delete button
            holder.btnDelete.setOnClickListener(v -> showDeleteDialog(file));

            // Long press to show options
            holder.itemView.setOnLongClickListener(v -> {
                showEditDialog(file);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
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
}
