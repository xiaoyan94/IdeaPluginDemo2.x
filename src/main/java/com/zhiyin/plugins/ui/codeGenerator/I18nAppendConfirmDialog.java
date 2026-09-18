package com.zhiyin.plugins.ui.codeGenerator;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.zhiyin.plugins.service.I18nGenerateService.I18nConfirmedAppend;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2-2：i18n 缺失清单可编辑确认对话框（P2-1 只读报告 I18nMissingReportDialog 升级替换，弹窗时机
 * 前置到生成之前、由 DataModelGenerator 在翻译后台任务 onSuccess 的 EDT 上弹出）。
 *
 * <p>表格列：字段名(只读) / 拟生成 key(只读) / zh_CN(可编辑，预填 comment) / zh_TW(可编辑，预填
 * 百度翻译) / en_US(可编辑，预填百度翻译)；翻译失败的格用户可手填。两个动作按钮：
 * 「追加并生成」（OK 动作——写三语言 properties + 布局 i18nKey 用新 key）与「直接生成」
 * （额外动作——不写 properties，保持裸中文回退）。</p>
 */
public class I18nAppendConfirmDialog extends DialogWrapper {

    /** 「直接生成」用户退出码（OK=0 / CANCEL=1 之外的自定义码） */
    public static final int DIRECT_GENERATE_EXIT_CODE = 2;

    /** 确认行数据（翻译后台任务产出，预填三语言值；zh_TW/en_US 翻译失败为空串） */
    public record I18nConfirmRow(String fieldName, String proposedKey, String zhCn, String zhTw, String enUs) {
    }

    private final String moduleI18nPrefix;
    private final String moduleName;
    private final int hitCount;
    private final int missCount;
    private final List<I18nConfirmRow> rows;
    private DefaultTableModel tableModel;
    private JBTable table;

    public I18nAppendConfirmDialog(String moduleI18nPrefix, String moduleName,
                                   int hitCount, int missCount, List<I18nConfirmRow> rows) {
        super(false);
        this.moduleI18nPrefix = moduleI18nPrefix;
        this.moduleName = moduleName;
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.rows = rows;
        setTitle("i18n 缺失确认（追加生成）");
        init();
        setOKButtonText("追加并生成");
    }

    // 「追加并生成」= OK 动作；「直接生成」= 额外动作（不写 properties，保持现有裸中文回退）
    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), myDirectGenerateAction};
    }

    private final AbstractAction myDirectGenerateAction = new DialogWrapperAction("直接生成") {
        @Override
        protected void doAction(ActionEvent e) {
            close(DIRECT_GENERATE_EXIT_CODE);
        }
    };

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setPreferredSize(new Dimension(920, 420));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(new JBLabel("模块 i18n 前缀：" + moduleI18nPrefix + "（模块 " + moduleName + "）"));
        header.add(new JBLabel("命中 " + hitCount + " 个 / 缺失 " + missCount
                + " 个——「追加并生成」将把下表 key 写入模块三语言 datagrid properties（已存在的 key 跳过）"));
        header.add(new JBLabel("zh_TW / en_US 预填百度翻译，失败留空可手填；确认追加时仍为空的 key 在对应语言文件写 TODO 注释行"));

        // zh_CN/zh_TW/en_US 三列可编辑（JTextField 默认编辑器），字段名与拟生成 key 只读
        tableModel = new DefaultTableModel(
                new Object[]{"字段名", "拟生成 key", "zh_CN", "zh_TW", "en_US"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column >= 2;
            }
        };
        for (I18nConfirmRow row : rows) {
            tableModel.addRow(new Object[]{row.fieldName(), row.proposedKey(),
                    nullSafe(row.zhCn()), nullSafe(row.zhTw()), nullSafe(row.enUs())});
        }
        JBTable table = new JBTable(tableModel);
        this.table = table;
        // 长 key 列给足宽度，超宽横向滚动（不挤压其余列）
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(400);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(120);

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JBScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    /** 读回编辑后的三语言值（「追加并生成」确认后调用；值 trim，空串原样保留走 TODO 降级）。
     *  读回前先停掉编辑中的单元格编辑器——否则用户改完不按回车直接点按钮时 getValueAt 拿到旧值 */
    public List<I18nConfirmedAppend> readConfirmedAppends() {
        if (table != null && table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
        List<I18nConfirmedAppend> confirmed = new ArrayList<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            confirmed.add(new I18nConfirmedAppend(
                    textAt(row, 0), textAt(row, 1), textAt(row, 2), textAt(row, 3), textAt(row, 4)));
        }
        return confirmed;
    }

    /** 便捷读回：字段 name（生成器小写口径）→ 确认追加值 */
    public Map<String, I18nConfirmedAppend> readConfirmedByField() {
        Map<String, I18nConfirmedAppend> result = new LinkedHashMap<>();
        for (I18nConfirmedAppend append : readConfirmedAppends()) {
            result.put(append.fieldName(), append);
        }
        return result;
    }

    private String textAt(int row, int column) {
        Object value = tableModel.getValueAt(row, column);
        return value == null ? "" : value.toString().trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
