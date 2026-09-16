package com.zhiyin.plugins.ui.codeGenerator;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SelectDatabaseConnectionDialog extends JDialog {

    private Map<String, String> selectedConnectionInfo;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final List<Map<String, String>> connectionInfoList;

    public SelectDatabaseConnectionDialog(Frame parent, List<Map<String, String>> connectionInfoList) {
        super(parent, "Select Database Connection", true);
        this.connectionInfoList = connectionInfoList;

        // Full display paths (tooltip source): project-relative filePath with fileName fallback, null-safe
        List<String> fullPaths = new ArrayList<>();
        for (Map<String, String> connectionInfo : connectionInfoList) {
            String path = connectionInfo.get("filePath");
            if (path == null || path.isEmpty()) {
                path = connectionInfo.get("fileName");
            }
            fullPaths.add(path != null ? path : "");
        }
        String commonDirPrefix = longestCommonDirPrefix(fullPaths);

        // Create table model and JTable
        String[] columnNames = {"Path", "URL", "Username", "Password"};
        // Read-only display model: isCellEditable=false keeps a double-click from starting a
        // cell editor, so the second click reaches our confirmSelection handler instead
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        // Hovering the Path column shows the full uncompressed path of that row
        table = new JBTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int viewRow = rowAtPoint(e.getPoint());
                int viewColumn = columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewColumn < 0) {
                    return null;
                }
                if (convertColumnIndexToModel(viewColumn) != 0) {
                    return super.getToolTipText(e);
                }
                int modelRow = convertRowIndexToModel(viewRow);
                if (modelRow >= 0 && modelRow < fullPaths.size()) {
                    return fullPaths.get(modelRow);
                }
                return null;
            }
        };
        // Populate table with connection info (password column always masked, plaintext never enters the model)
        for (int i = 0; i < connectionInfoList.size(); i++) {
            Map<String, String> connectionInfo = connectionInfoList.get(i);
            tableModel.addRow(new Object[]{
                    compressDisplayPath(fullPaths.get(i), commonDirPrefix),
                    connectionInfo.get("url"),
                    connectionInfo.get("username"),
                    "******"
            });
        }
        // Give the Path column room for the compressed module path; cap the URL column so it cannot squeeze it out
        TableColumn pathColumn = table.getColumnModel().getColumn(0);
        pathColumn.setPreferredWidth(320);
        TableColumn urlColumn = table.getColumnModel().getColumn(1);
        urlColumn.setPreferredWidth(280);
        urlColumn.setMaxWidth(380);

        // Double-click on a row confirms the selection (same as the Select button);
        // clicks on the header or outside any row (rowAtPoint < 0) are ignored
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)
                        && table.rowAtPoint(e.getPoint()) >= 0) {
                    confirmSelection();
                }
            }
        });

        // Create buttons
        JButton selectButton = new JButton("Select");
        JButton closeButton = new JButton("Close");

        selectButton.addActionListener(e -> confirmSelection());

        closeButton.addActionListener(e -> dispose());

        // Layout components
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new FlowLayout());
        buttonPanel.add(selectButton);
        buttonPanel.add(closeButton);

        JScrollPane scrollPane = new JBScrollPane(table);

        setLayout(new BorderLayout());
        add(buttonPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        setSize(800, 600); // Set size to be large enough for the table
        setLocationRelativeTo(parent);
    }

    private void confirmSelection() {
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            // Take the connection info directly by row index;
            // convertRowIndexToModel is identity while the table has no sorter/filter
            int modelRow = table.convertRowIndexToModel(selectedRow);
            selectedConnectionInfo = connectionInfoList.get(modelRow);
            dispose(); // Close dialog and return selected connection info
        }
    }

    /**
     * Longest common directory prefix (with trailing '/') across the given paths, compared
     * segment-wise on '/'. Returns "" when there is nothing to strip (single row or no
     * common segment), which leaves every path displayed as-is.
     */
    private static String longestCommonDirPrefix(List<String> paths) {
        if (paths.size() < 2) {
            return "";
        }
        List<String[]> dirSegmentsList = new ArrayList<>();
        int minDirCount = Integer.MAX_VALUE;
        for (String path : paths) {
            String[] segments = path.split("/");
            String[] dirs = Arrays.copyOfRange(segments, 0, Math.max(segments.length - 1, 0));
            dirSegmentsList.add(dirs);
            minDirCount = Math.min(minDirCount, dirs.length);
        }
        int common = 0;
        while (common < minDirCount) {
            String segment = dirSegmentsList.get(0)[common];
            boolean allMatch = true;
            for (String[] dirs : dirSegmentsList) {
                if (!dirs[common].equals(segment)) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) {
                break;
            }
            common++;
        }
        if (common == 0) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < common; i++) {
            prefix.append(dirSegmentsList.get(0)[i]).append('/');
        }
        return prefix.toString();
    }

    /**
     * Display-only compression: strips the batch-common directory prefix, then folds fixed
     * source-layout segments ("/src/main/resources/", "/src/main/webapp/") into "/…/".
     * The tooltip keeps the original full path.
     */
    private static String compressDisplayPath(String fullPath, String commonDirPrefix) {
        String display = fullPath;
        if (!commonDirPrefix.isEmpty() && fullPath.startsWith(commonDirPrefix)) {
            display = display.substring(commonDirPrefix.length());
        }
        display = display.replace("/src/main/resources/", "/…/");
        display = display.replace("/src/main/webapp/", "/…/");
        return display;
    }

    public Map<String, String> getSelectedConnectionInfo() {
        return selectedConnectionInfo;
    }
}
