package com.zhiyin.plugins.ui.codeGenerator;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.zhiyin.plugins.service.CodeGenerateService.I18nMissingEntry;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * P2-1：i18n 缺失清单只读报告——生成完成后弹出（缺失列表为空时调用方不弹，避免噪音）。
 * 纯 EDT UI + 预查结果消费，不做任何索引/PSI 查询；本版只报告不写文件（写入闭环是 P2-2）。
 */
public class I18nMissingReportDialog extends DialogWrapper {

    private final String moduleI18nPrefix;
    private final String moduleName;
    private final int hitCount;
    private final int missCount;
    private final List<I18nMissingEntry> missingEntries;

    public I18nMissingReportDialog(String moduleI18nPrefix, String moduleName,
                                   int hitCount, int missCount, List<I18nMissingEntry> missingEntries) {
        super(false);
        this.moduleI18nPrefix = moduleI18nPrefix;
        this.moduleName = moduleName;
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.missingEntries = missingEntries;
        setTitle("i18n 缺失清单（只读报告）");
        init();
        setOKButtonText("关闭");
    }

    // 只保留一个「关闭」按钮（OK 动作改文案，不创建 Cancel/Help 动作）
    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(760, 420));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(new JBLabel("模块 i18n 前缀：" + moduleI18nPrefix + "（模块 " + moduleName + "）"));
        header.add(new JBLabel("命中 " + hitCount + " 个 / 缺失 " + missCount
                + " 个——以下为缺失字段的拟生成 key（本版只报告，不写文件）"));

        // 只读展示模型：isCellEditable 恒 false（照 P1-5 SelectDatabaseConnectionDialog），
        // 单元格仍可选中复制
        DefaultTableModel tableModel = new DefaultTableModel(
                new Object[]{"字段名", "拟生成 key", "拟中文值"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (I18nMissingEntry entry : missingEntries) {
            tableModel.addRow(new Object[]{entry.fieldName(), entry.proposedKey(), entry.chs()});
        }
        JBTable table = new JBTable(tableModel);
        // 长 key 列给足宽度，超宽横向滚动（不挤压字段名/中文值列）
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JBScrollPane(table), BorderLayout.CENTER);
        return panel;
    }
}
