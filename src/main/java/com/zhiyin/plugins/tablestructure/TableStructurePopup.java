package com.zhiyin.plugins.tablestructure;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.zhiyin.plugins.notification.MyPluginMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 表结构结果弹窗：光标处弹出，字段/索引/建表 SQL 三个页签 + 数据源切换 + 复制按钮。
 * 必须在 EDT 调用。
 */
public final class TableStructurePopup {

    /**
     * 切换数据源回调：参数为当前结果弹窗。
     * 切换成功后由回调方负责关闭该弹窗并重新查询展示；用户取消选择则弹窗保留。
     */
    public interface ConnectionSwitcher {
        void onSwitchRequested(@NotNull JBPopup resultPopup);
    }

    private static final int MAX_COMMENT_IN_TITLE = 60;

    private TableStructurePopup() {
    }

    public static void show(@NotNull Editor editor, @NotNull TableStructure structure,
                            @Nullable String currentConnectionName, @Nullable ConnectionSwitcher switcher) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        // content 构建在 createPopup 之前，按钮回调经 ref 拿到 popup 实例
        final JBPopup[] popupRef = new JBPopup[1];
        JComponent content = buildContent(structure, currentConnectionName, switcher, popupRef);
        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, content)
                .setTitle(buildTitle(structure))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setMinSize(new Dimension(720, 360))
                .createPopup();
        popupRef[0] = popup;
        // 后台查询期间文件可能已关闭
        if (editor.isDisposed()) {
            popup.showInFocusCenter();
        } else {
            popup.showInBestPositionFor(editor);
        }
    }

    private static String buildTitle(TableStructure structure) {
        TableStructure.TableInfo info = structure.tableInfo;
        StringBuilder title = new StringBuilder(info == null ? "?" : info.table + "@" + info.schema);
        String comment = info == null ? "" : info.comment;
        if (!comment.isEmpty()) {
            title.append("  ").append(comment, 0, Math.min(comment.length(), MAX_COMMENT_IN_TITLE));
            if (comment.length() > MAX_COMMENT_IN_TITLE) {
                title.append("…");
            }
        }
        return title.toString();
    }

    private static JComponent buildContent(TableStructure structure, @Nullable String currentConnectionName,
                                           @Nullable ConnectionSwitcher switcher, JBPopup[] popupRef) {
        JPanel root = new JPanel(new BorderLayout(0, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        root.add(buildInfoLine(structure), BorderLayout.NORTH);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("字段 (" + structure.columns.size() + ")", buildColumnsTab(structure.columns));
        tabs.addTab("索引 (" + structure.indexes.size() + ")", buildIndexesTab(structure.indexes));
        if (structure.ddl != null) {
            tabs.addTab("建表 SQL", buildDdlTab(structure.ddl));
        }
        root.add(tabs, BorderLayout.CENTER);

        root.add(buildButtonBar(structure, currentConnectionName, switcher, popupRef), BorderLayout.SOUTH);
        return root;
    }

    private static JComponent buildInfoLine(TableStructure structure) {
        TableStructure.TableInfo info = structure.tableInfo;
        String text;
        if (info == null) {
            text = "";
        } else {
            text = "引擎: " + info.engine
                    + " ｜ 排序规则: " + info.collation
                    + " ｜ 行数(约): " + info.rows
                    + " ｜ 创建时间: " + info.createTime;
        }
        JBLabel label = new JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        return label;
    }

    private static JComponent buildColumnsTab(List<TableStructure.Column> columns) {
        String[] headers = {"字段", "类型", "NULL", "键", "默认值", "额外", "注释"};
        Object[][] data = new Object[columns.size()][];
        for (int i = 0; i < columns.size(); i++) {
            TableStructure.Column c = columns.get(i);
            data[i] = new Object[]{
                    c.name,
                    c.type,
                    c.nullable ? "YES" : "NO",
                    c.key,
                    c.defaultValue == null ? "NULL" : c.defaultValue,
                    c.extra,
                    c.comment
            };
        }
        JBTable table = new JBTable(readonlyModel(data, headers));
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        installDoubleClickCopy(table, 0, "字段名");
        int[] widths = {170, 150, 50, 45, 110, 130, 340};
        applyColumnWidths(table, widths);
        return wrap(table, widths, Math.min(columns.size(), 16));
    }

    private static JComponent buildIndexesTab(List<TableStructure.IndexColumn> indexes) {
        String[] headers = {"索引名", "唯一", "序号", "列", "类型"};
        Object[][] data = new Object[indexes.size()][];
        for (int i = 0; i < indexes.size(); i++) {
            TableStructure.IndexColumn idx = indexes.get(i);
            data[i] = new Object[]{idx.indexName, idx.unique ? "是" : "否", idx.seq, idx.column, idx.indexType};
        }
        JBTable table = new JBTable(readonlyModel(data, headers));
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        installDoubleClickCopy(table, 3, "索引列名");
        int[] widths = {220, 50, 50, 220, 90};
        applyColumnWidths(table, widths);
        return wrap(table, widths, Math.min(indexes.size(), 14));
    }

    private static JComponent buildDdlTab(String ddl) {
        JBTextArea area = new JBTextArea(ddl);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(new Dimension(940, JBUI.scale(300)));
        return scroll;
    }

    private static JComponent buildButtonBar(TableStructure structure, @Nullable String currentConnectionName,
                                             @Nullable ConnectionSwitcher switcher, JBPopup[] popupRef) {
        JPanel bar = new JPanel(new BorderLayout());
        if (switcher != null && currentConnectionName != null) {
            JButton switchButton = new JButton("切换数据源", AllIcons.Actions.Refresh);
            switchButton.setToolTipText("当前: " + currentConnectionName + "，点击切换后重新查询");
            switchButton.addActionListener(e -> switcher.onSwitchRequested(popupRef[0]));
            JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            west.add(switchButton);
            bar.add(west, BorderLayout.WEST);
        }

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton copyColumns = new JButton("复制字段清单", AllIcons.Actions.Copy);
        copyColumns.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (TableStructure.Column c : structure.columns) {
                sb.append(c.name).append('\t').append(c.type).append('\t').append(c.comment).append('\n');
            }
            CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
        });
        east.add(copyColumns);
        if (structure.ddl != null) {
            JButton copyDdl = new JButton("复制建表语句", AllIcons.Actions.Copy);
            copyDdl.addActionListener(e ->
                    CopyPasteManager.getInstance().setContents(new StringSelection(structure.ddl)));
            east.add(copyDdl);
        }
        bar.add(east, BorderLayout.EAST);
        return bar;
    }

    /** 双击行复制指定列的值（字段表复制字段名、索引表复制列名），经 row sorter 转回模型行号 */
    private static void installDoubleClickCopy(JBTable table, int valueColumnIndex, String label) {
        table.setToolTipText("双击复制" + label);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }
                int viewRow = table.getSelectedRow();
                if (viewRow < 0) {
                    return;
                }
                int modelRow = table.convertRowIndexToModel(viewRow);
                Object value = table.getModel().getValueAt(modelRow, valueColumnIndex);
                if (value != null) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(String.valueOf(value)));
                    MyPluginMessages.showInfo("查看表结构", "已复制" + label + ": " + value, null);
                }
            }
        });
    }

    private static DefaultTableModel readonlyModel(Object[][] data, String[] headers) {
        return new DefaultTableModel(data, headers) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static void applyColumnWidths(JBTable table, int[] widths) {
        TableColumnModel columnModel = table.getColumnModel();
        for (int i = 0; i < widths.length && i < columnModel.getColumnCount(); i++) {
            columnModel.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private static JComponent wrap(JBTable table, int[] widths, int maxVisibleRows) {
        JBScrollPane scroll = new JBScrollPane(table);
        int totalWidth = 0;
        for (int width : widths) {
            totalWidth += width;
        }
        int height = table.getTableHeader().getPreferredSize().height
                + Math.max(1, maxVisibleRows) * table.getRowHeight() + 6;
        scroll.setPreferredSize(new Dimension(totalWidth + 30, height));
        return scroll;
    }
}
