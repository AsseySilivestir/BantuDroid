#!/usr/bin/env python3
"""
BantuDroid v2.2.2 - Complete IDE overhaul script.
Adds: real shell CWD, full Bantu CLI, project auto-registration,
      directory-based file explorer, sync between all views.
"""

import os

BASE = "/home/z/my-project/BantuDroid"

# ============================================================
# 1. BantuEngine.java - Add workspace root, BantuProject, executeInDir
# ============================================================

BANTU_ENGINE_PATCH = '''
    // -----------------------------------------------------------------
    // Workspace & Project Management (v2.2.2)
    // -----------------------------------------------------------------

    /**
     * Get the Bantu workspace root directory.
     * This is where `bantu init` creates projects.
     * We use the app's internal files directory as workspace.
     */
    public File getWorkspaceRoot() {
        return context.getFilesDir();
    }

    /**
     * Execute a Bantu command in a specific working directory.
     * This is critical for `bantu run` to work inside a project directory.
     */
    public BantuProcess executeInDir(File workingDir, String... args) throws IOException {
        ensureInstalled();
        String binaryPath = requireBinaryPath();
        Log.i(TAG, "Executing in " + workingDir.getAbsolutePath() + ": " + binaryPath + " " + String.join(" ", args));
        return executeBinaryInDir(binaryPath, workingDir, args);
    }

    /**
     * Execute a raw shell-style bantu command in a specific directory.
     * Parses "bantu <args>" and executes with the given CWD.
     */
    public BantuProcess runShellCommandInDir(File workingDir, String command) throws IOException {
        ensureInstalled();
        String binaryPath = requireBinaryPath();
        String trimmed = command.trim();

        if (trimmed.startsWith("bantu ")) {
            trimmed = trimmed.substring("bantu ".length()).trim();
        } else if (trimmed.equals("bantu")) {
            trimmed = "--help";
        }

        List<String> argList = parseArgs(trimmed);
        String[] args = argList.toArray(new String[0]);

        Log.i(TAG, "Shell command in " + workingDir.getAbsolutePath() + ": " + binaryPath + " " + String.join(" ", args));
        return executeBinaryInDir(binaryPath, workingDir, args);
    }

    private BantuProcess executeBinaryInDir(String binaryPath, File workingDir, String[] args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = binaryPath;
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);
        setupEnvironment(pb);

        Process process = pb.start();
        return new BantuProcess(process, String.join(" ", args));
    }

    /**
     * Scan the workspace for all Bantu projects (directories containing bantu.json).
     * Also includes standalone .b files in the projects directory.
     */
    public List<BantuProject> scanProjects() {
        List<BantuProject> projects = new ArrayList<>();
        File workspace = getWorkspaceRoot();

        // Scan workspace root for project directories
        File[] dirs = workspace.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory()) {
                    // Check if it's a Bantu project (has bantu.json or .b files)
                    boolean isProject = new File(d, "bantu.json").exists();
                    if (!isProject) {
                        // Check for any .b files
                        File[] files = d.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.getName().endsWith(".b")) {
                                    isProject = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (isProject) {
                        projects.add(new BantuProject(d));
                    }
                }
            }
        }

        // Also scan projects dir for standalone .b files
        File[] bFiles = projectsDir.listFiles();
        if (bFiles != null) {
            for (File f : bFiles) {
                if (f.getName().endsWith(".b")) {
                    projects.add(new BantuProject(f));
                }
            }
        }

        return projects;
    }

    /**
     * Create a new project directory. Equivalent to `bantu init <name>`.
     */
    public File initProjectDir(String name) {
        File projectDir = new File(getWorkspaceRoot(), name);
        projectDir.mkdirs();
        return projectDir;
    }

    /**
     * Represents a Bantu project (a directory with bantu.json or .b files).
     */
    public static class BantuProject {
        private final File dir;
        private final boolean isDirectory;

        public BantuProject(File dir) {
            this.dir = dir;
            this.isDirectory = dir.isDirectory();
        }

        public String getName() { return dir.getName(); }
        public String getPath() { return dir.getAbsolutePath(); }
        public File getDir() { return dir; }
        public boolean isDirectoryProject() { return isDirectory; }
        public long getLastModified() { return dir.lastModified(); }

        public boolean hasBantuJson() {
            return new File(dir, "bantu.json").exists();
        }

        public String getMainFile() {
            File main = new File(dir, "main.b");
            if (main.exists()) return main.getAbsolutePath();
            // Find first .b file
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".b")) return f.getAbsolutePath();
                    }
                }
            }
            return null;
        }

        public List<File> listAllFiles() {
            List<File> result = new ArrayList<>();
            if (dir.isDirectory()) {
                listRecursive(dir, result);
            } else {
                result.add(dir);
            }
            return result;
        }

        private void listRecursive(File d, List<File> result) {
            File[] children = d.listFiles();
            if (children == null) return;
            for (File f : children) {
                result.add(f);
                if (f.isDirectory()) {
                    listRecursive(f, result);
                }
            }
        }
    }
'''

print("Script loaded. This will be applied via direct file editing.")
print("Proceeding with file generation...")
