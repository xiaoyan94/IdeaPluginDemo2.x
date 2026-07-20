package com.zhiyin.plugins.settings;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.zhiyin.plugins.settings.ui.MySearchScopeItem;

import javax.swing.*;
import java.awt.*;

/**
 * Supports creating and managing a {@link JPanel} for the Settings Dialog.
 */
public class AppSettingsComponent {

    private final JPanel myMainPanel;

    private final JBCheckBox defaultCollapseI18nStatus = new JBCheckBox("I18n资源串默认折叠显示");

    private final ComboBox<MySearchScopeItem> mapperToDaoSearchScope = new ComboBox<>();

    // Add a new text field for the commit template
    private final JBTextArea commitTemplateText = new JBTextArea();

    // 多项目SVN路径配置
    private final JBTextArea svnProjectPathsText = new JBTextArea();

    // SVN统计是否包含代码量（默认开启）
    private final JBCheckBox svnIncludeCodeVolumeCheck = new JBCheckBox("统计SVN提交日志时包含代码量（svn diff，较慢）");

    // 重试失败项时单条 svn diff 超时（秒），默认 3
    private final JTextField svnRetryTimeoutField = new JTextField("3");

    // 主分析时单条 svn diff 超时（秒），默认 3
    private final JTextField svnDiffTimeoutField = new JTextField("3");

    // 统计时排除的作者列表（多人），逗号/分号/空格/换行分隔
    private final JBTextArea svnExcludeAuthorsText = new JBTextArea();

    public AppSettingsComponent() {
        mapperToDaoSearchScope.addItem(MySearchScopeItem.MODULE);
        mapperToDaoSearchScope.addItem(MySearchScopeItem.PROJECT);
        commitTemplateText.setRows(5);
        commitTemplateText.setLineWrap(true);
        commitTemplateText.setWrapStyleWord(true);
        commitTemplateText.setBorder(BorderFactory.createTitledBorder("Commit message template"));
        // 设置为微软雅黑
        commitTemplateText.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));

        // SVN项目路径配置
        svnProjectPathsText.setRows(6);
        svnProjectPathsText.setLineWrap(false);
        svnProjectPathsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        svnProjectPathsText.setToolTipText("每行一个项目: 项目名=SVN本地工作目录绝对路径\n例如: CloudMES=D:/workspace/cloudmes\n       Fobrite=D:/workspace/fobrite");

        // 排除作者配置
        svnExcludeAuthorsText.setRows(3);
        svnExcludeAuthorsText.setLineWrap(true);
        svnExcludeAuthorsText.setWrapStyleWord(true);
        svnExcludeAuthorsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        svnExcludeAuthorsText.setToolTipText("统计时排除的作者（多人），以逗号/分号/空格/换行分隔\n例如: zhangsan,lisi,wangwu\n这些作者的提交在提交数、代码量、图表、明细中均会被忽略");

        myMainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("I18n", new JSeparator())
                .addComponent(defaultCollapseI18nStatus)
                .addVerticalGap(20)
                .addLabeledComponent("Search scope", new JSeparator())
                .addLabeledComponent("Mapper to dao scope", mapperToDaoSearchScope)
                .addVerticalGap(20)
                // Add the new text field with a label
                .addLabeledComponent("SVN提交模板自定义前缀", commitTemplateText)
                .addVerticalGap(10)
                .addLabeledComponent("SVN项目路径配置 (项目名=路径, 每行一个)", svnProjectPathsText)
                .addComponent(svnIncludeCodeVolumeCheck)
                .addLabeledComponent("主分析超时(秒)", svnDiffTimeoutField)
                .addLabeledComponent("重试失败项超时(秒)", svnRetryTimeoutField)
                .addLabeledComponent("排除作者(多人, 逗号/分号/空格分隔)", svnExcludeAuthorsText)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getPanel() {
        return myMainPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return defaultCollapseI18nStatus;
    }

    public boolean getDefaultCollapseI18nStatus() {
        return defaultCollapseI18nStatus.isSelected();
    }

    public void setDefaultCollapseI18nStatus(boolean newStatus) {
        defaultCollapseI18nStatus.setSelected(newStatus);
    }

    public MySearchScopeItem getMapperToDaoSearchScope() {
        return mapperToDaoSearchScope.getItem();
    }

    public void setMapperToDaoSearchScope(MySearchScopeItem newScope) {
        mapperToDaoSearchScope.setItem(newScope);
    }

    // Add getter and setter for the new text field
    public String getCommitTemplateText() {
        if (commitTemplateText.getText() == null || commitTemplateText.getText().isEmpty()) {
            setCommitTemplateText("问题单号：DTS20151135000\n修改人：\n修改描述：\n");
        }
        return commitTemplateText.getText();
    }

    public void setCommitTemplateText(String newText) {
        commitTemplateText.setText(newText);
    }

    // SVN项目路径配置 getter/setter
    public String getSvnProjectPathsText() {
        return svnProjectPathsText.getText();
    }

    public void setSvnProjectPathsText(String paths) {
        svnProjectPathsText.setText(paths != null ? paths : "");
    }

    // SVN代码量统计开关 getter/setter
    public boolean getSvnIncludeCodeVolume() {
        return svnIncludeCodeVolumeCheck.isSelected();
    }

    public void setSvnIncludeCodeVolume(boolean enabled) {
        svnIncludeCodeVolumeCheck.setSelected(enabled);
    }

    // 重试失败项超时（秒）getter/setter，非法输入回退为 3
    public int getSvnRetryTimeout() {
        try {
            int v = Integer.parseInt(svnRetryTimeoutField.getText().trim());
            return v > 0 ? v : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    public void setSvnRetryTimeout(int seconds) {
        svnRetryTimeoutField.setText(String.valueOf(seconds > 0 ? seconds : 3));
    }

    // 主分析超时（秒）getter/setter，非法输入回退为 3
    public int getSvnDiffTimeout() {
        try {
            int v = Integer.parseInt(svnDiffTimeoutField.getText().trim());
            return v > 0 ? v : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    public void setSvnDiffTimeout(int seconds) {
        svnDiffTimeoutField.setText(String.valueOf(seconds > 0 ? seconds : 3));
    }

    // 排除作者列表 getter/setter（多人，逗号/分号/空格/换行分隔）
    public String getSvnExcludeAuthorsText() {
        return svnExcludeAuthorsText.getText();
    }

    public void setSvnExcludeAuthorsText(String authors) {
        svnExcludeAuthorsText.setText(authors != null ? authors : "");
    }

}