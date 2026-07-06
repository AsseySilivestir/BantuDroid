package com.bantu.droid;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java implementation of the `tree` command.
 * Output format matches GNU tree with ├── └── │ connectors.
 */
public class TreeRenderer {

    private final File root;
    private final boolean showHidden;
    private final int maxDepth;
    private final boolean dirsOnly;
    private int dirCount = 0;
    private int fileCount = 0;

    public TreeRenderer(File root) { this(root, false, Integer.MAX_VALUE, false); }
    public TreeRenderer(File root, boolean showHidden, int maxDepth, boolean dirsOnly) {
        this.root = root; this.showHidden = showHidden; this.maxDepth = maxDepth; this.dirsOnly = dirsOnly;
    }

    public String render() {
        if (root == null || !root.exists()) return "(no such file or directory)\n";
        StringBuilder sb = new StringBuilder();
        sb.append(root.getAbsolutePath()).append('\n');
        if (root.isDirectory()) renderDir(root, "", 1, sb);
        sb.append('\n').append(dirCount).append(" director").append(dirCount == 1 ? "y" : "ies")
          .append(", ").append(fileCount).append(" file").append(fileCount == 1 ? "" : "s").append('\n');
        return sb.toString();
    }

    private void renderDir(File dir, String prefix, int depth, StringBuilder sb) {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return;
        List<File> filtered = new ArrayList<>();
        for (File f : children) {
            if (!showHidden && f.getName().startsWith(".")) continue;
            if (dirsOnly && !f.isDirectory()) continue;
            filtered.add(f);
        }
        if (filtered.isEmpty()) return;
        Collections.sort(filtered, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < filtered.size(); i++) {
            File f = filtered.get(i);
            boolean isLast = (i == filtered.size() - 1);
            String connector = isLast ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ";
            String childPrefix = prefix + (isLast ? "    " : "\u2502   ");
            String name = f.getName();
            if (f.isDirectory()) {
                sb.append(prefix).append(connector).append(name).append("/\n");
                dirCount++;
                if (depth < maxDepth) renderDir(f, childPrefix, depth + 1, sb);
            } else {
                String sizeStr = formatSize(f.length());
                sb.append(prefix).append(connector).append(name);
                int nameLen = name.length();
                if (nameLen < 30) { char[] spaces = new char[30 - nameLen]; Arrays.fill(spaces, ' '); sb.append(spaces); }
                else sb.append("  ");
                sb.append(sizeStr).append('\n');
                fileCount++;
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fM", bytes / (1024.0 * 1024));
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }
}
