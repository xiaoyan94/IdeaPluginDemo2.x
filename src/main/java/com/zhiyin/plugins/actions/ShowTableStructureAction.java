package com.zhiyin.plugins.actions;

import com.github.xiaoyan94.ideaplugindemo2x.MyBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.util.ExceptionUtil;
import com.zhiyin.plugins.notification.MyPluginMessages;
import com.zhiyin.plugins.resources.MyIcons;
import com.zhiyin.plugins.tablestructure.TableStructure;
import com.zhiyin.plugins.tablestructure.TableStructurePopup;
import com.zhiyin.plugins.tablestructure.TableStructureQuery;
import com.zhiyin.plugins.utils.DatabaseConnectionFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 选中表名（如 biz_erp_sale_order）右键 -> 连库查看表结构。
 * 连接信息复用 {@link DatabaseConnectionFinder}（项目 .properties 里的 database.url/username/password），
 * 查询全走 information_schema（只读），JDBC 在后台线程执行。
 * 上次使用的数据源按项目记忆，下次直达；结果弹窗内可切换数据源重查。
 */
public class ShowTableStructureAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(ShowTableStructureAction.class);

    /** 选区允许的字符：表名、库名、点号、反引号 */
    private static final Pattern SELECTION_PATTERN = Pattern.compile("^[`0-9A-Za-z_$.]+$");

    private static final String LAST_CONNECTION_KEY = "com.zhiyin.plugins.tablestructure.lastConnection";

    /** 一次查询流程的上下文（弹窗里切换数据源重查时复用） */
    private static final class QueryContext {
        final Project project;
        final Editor editor;
        final List<Map<String, String>> connections;
        final String schemaHint;
        final String table;

        QueryContext(Project project, Editor editor, List<Map<String, String>> connections,
                     String schemaHint, String table) {
            this.project = project;
            this.editor = editor;
            this.connections = connections;
            this.schemaHint = schemaHint;
            this.table = table;
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // update 里读取选区文本，EDT 才线程安全
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        String[] parsed = editor == null ? null : parseSelection(editor.getSelectionModel().getSelectedText());
        boolean visible = parsed != null;
        e.getPresentation().setVisible(visible);
        e.getPresentation().setEnabled(visible);
        // 动态显示解析出的表名，避免误触发；
        // mayContainMnemonic=false：表名里的下划线若按默认 mnemonic 规则解析会被吞掉导致错位显示
        String text = MyBundle.message("action.com.zhiyin.plugins.actions.ShowTableStructureAction.text");
        e.getPresentation().setText(visible ? text + ": " + parsed[1] : text, false);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        String[] parsed = parseSelection(editor.getSelectionModel().getSelectedText());
        if (parsed == null) {
            MyPluginMessages.showWarning("查看表结构", "请选中一个表名（如 biz_erp_sale_order）", project);
            return;
        }
        String schemaHint = parsed[0];
        String table = parsed[1];

        List<Map<String, String>> connections = new ArrayList<>();
        try {
            connections.addAll(project.getService(DatabaseConnectionFinder.class).findDatabaseConnections(project));
        } catch (Exception ex) {
            LOG.warn("扫描项目数据库配置失败", ex);
            MyPluginMessages.showError("查看表结构", "扫描项目 .properties 数据库配置失败: " + ExceptionUtil.getMessage(ex), project);
            return;
        }
        if (connections.isEmpty()) {
            MyPluginMessages.showWarning("查看表结构",
                    "未在项目 .properties 文件中找到 database.url / database.username / database.password 配置", project);
            return;
        }
        QueryContext ctx = new QueryContext(project, editor, connections, schemaHint, table);
        Map<String, String> remembered = rememberedConnection(project, connections);
        if (remembered != null) {
            queueQuery(ctx, remembered, null);
        } else {
            showConnectionChooser(ctx);
        }
    }

    /**
     * 选区归一化：去反引号与空白，支持 schema.table 形式。
     *
     * @return [schema 或 null, table]；不合法返回 null
     */
    @Nullable
    private static String[] parseSelection(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().replace("`", "");
        if (text.isEmpty() || !SELECTION_PATTERN.matcher(text).matches()) {
            return null;
        }
        int dot = text.indexOf('.');
        if (dot < 0) {
            return new String[]{null, text};
        }
        String schema = text.substring(0, dot);
        String table = text.substring(dot + 1);
        if (schema.isEmpty() || table.isEmpty() || table.contains(".")) {
            return null;
        }
        return new String[]{schema, table};
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        int idx = url.indexOf("//");
        return idx >= 0 ? url.substring(idx + 2) : url;
    }

    /** 上次使用的数据源（url+username 匹配当前扫描到的连接，配置变更后自动失效） */
    @Nullable
    private static Map<String, String> rememberedConnection(Project project, List<Map<String, String>> connections) {
        String key = PropertiesComponent.getInstance(project).getValue(LAST_CONNECTION_KEY);
        if (key == null) {
            return null;
        }
        for (Map<String, String> conn : connections) {
            if (key.equals(connectionKey(conn))) {
                return conn;
            }
        }
        return null;
    }

    private static void rememberConnection(Project project, Map<String, String> conn) {
        PropertiesComponent.getInstance(project).setValue(LAST_CONNECTION_KEY, connectionKey(conn));
    }

    private static String connectionKey(Map<String, String> conn) {
        return conn.get("url") + "|" + conn.get("username");
    }

    private static SimpleListCellRenderer<Map<String, String>> connectionRenderer() {
        // 显式类型声明，避免 lambda 参数被推断成 Object
        return SimpleListCellRenderer.create((label, value, index) ->
                label.setText(value.get("fileName") + "  (" + shortUrl(value.get("url")) + ")"));
    }

    private void showConnectionChooser(QueryContext ctx) {
        showConnectionChooser(ctx, "选择数据源");
    }

    /** 数据源选择器；title 区分首次选择（选择数据源）和失败后重选（数据源不可用，请重新选择） */
    private static void showConnectionChooser(QueryContext ctx, String title) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(ctx.connections)
                .setTitle(title)
                .setRenderer(connectionRenderer())
                .setItemChosenCallback(conn -> {
                    rememberConnection(ctx.project, conn);
                    queueQuery(ctx, conn, null);
                })
                .createPopup()
                .showInBestPositionFor(ctx.editor);
    }

    /**
     * 后台查询并弹窗。schema 解析优先级：explicitSchema（库选择器二次进来）> schemaHint（选区里带库名）> URL 默认库；
     * 默认库查不到时按表名全库搜索，唯一命中直接用，多命中回 EDT 弹库选择器。
     */
    private static void queueQuery(QueryContext ctx, Map<String, String> conn, @Nullable String explicitSchema) {
        new Task.Backgroundable(ctx.project, "查询表结构: " + ctx.table, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (indicator.isCanceled()) {
                    return;
                }
                try (Connection connection = TableStructureQuery.open(conn)) {
                    indicator.setText("连接 " + shortUrl(conn.get("url")));
                    String schema = explicitSchema != null ? explicitSchema
                            : ctx.schemaHint != null ? ctx.schemaHint
                            : TableStructureQuery.defaultSchema(connection);
                    TableStructure structure = TableStructureQuery.load(connection, schema, ctx.table);

                    if (structure.columns.isEmpty() && explicitSchema == null) {
                        List<String> schemas = TableStructureQuery.findSchemas(connection, ctx.table);
                        if (schemas.isEmpty()) {
                            notifyReselect(ctx, "未找到表 " + ctx.table + "（数据源: " + describe(conn)
                                    + "，当前库: " + schema + "），请确认表名或换一个数据源重试", "切换数据源");
                            return;
                        }
                        if (schemas.size() > 1) {
                            ApplicationManager.getApplication().invokeLater(() ->
                                    showSchemaChooser(ctx, conn, schemas));
                            return;
                        }
                        schema = schemas.get(0);
                        structure = TableStructureQuery.load(connection, schema, ctx.table);
                    }
                    if (structure.columns.isEmpty()) {
                        notifyReselect(ctx, "库 " + schema + " 中不存在表 " + ctx.table
                                + "（数据源: " + describe(conn) + "）", "切换数据源");
                        return;
                    }
                    TableStructure result = structure;
                    ApplicationManager.getApplication().invokeLater(() -> showResult(ctx, conn, result));
                } catch (Exception ex) {
                    LOG.warn("查询表结构失败: " + ctx.table, ex);
                    if (isConnectionFailure(ex)) {
                        // 连不上/超时：该数据源地址或端口不对，遗忘记忆并给出"重新选择数据源"入口
                        forgetConnection(ctx.project);
                        notifyReselect(ctx, "连接数据源失败: " + describe(conn) + System.lineSeparator()
                                + ExceptionUtil.getMessage(ex), "重新选择数据源");
                    } else {
                        notifyError(ctx.project, "查询失败: " + ExceptionUtil.getMessage(ex));
                    }
                }
            }
        }.queue();
    }

    private static void showResult(QueryContext ctx, Map<String, String> conn, TableStructure structure) {
        // 仅多数据源项目提供弹窗内切换
        TableStructurePopup.ConnectionSwitcher switcher = ctx.connections.size() > 1
                ? resultPopup -> switchConnection(ctx, resultPopup)
                : null;
        TableStructurePopup.show(ctx.editor, structure, conn.getOrDefault("fileName", ""), switcher);
    }

    /** 结果弹窗里点"切换数据源"：弹选择器，选中后记住并关旧弹窗重查；取消则原弹窗保留 */
    private static void switchConnection(QueryContext ctx, JBPopup resultPopup) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(ctx.connections)
                .setTitle("切换数据源")
                .setRenderer(connectionRenderer())
                .setItemChosenCallback(conn -> {
                    resultPopup.cancel();
                    rememberConnection(ctx.project, conn);
                    queueQuery(ctx, conn, null);
                })
                .createPopup()
                .showInFocusCenter();
    }

    private static void showSchemaChooser(QueryContext ctx, Map<String, String> conn, List<String> schemas) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(schemas)
                .setTitle("表 " + ctx.table + " 存在于多个库，请选择")
                .setItemChosenCallback(schema -> queueQuery(ctx, conn, schema))
                .createPopup()
                .showInBestPositionFor(ctx.editor);
    }

    private static void notifyError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                MyPluginMessages.showError("查看表结构", message, project));
    }

    /** 数据源的可读描述：配置文件名 + 去协议前缀的 URL，便于确认刚才用的是哪一套配置 */
    private static String describe(Map<String, String> conn) {
        return conn.getOrDefault("fileName", "") + " (" + shortUrl(conn.get("url")) + ")";
    }

    private static void forgetConnection(Project project) {
        PropertiesComponent.getInstance(project).unsetValue(LAST_CONNECTION_KEY);
    }

    /**
     * 错误提示带一个"重新选择数据源/切换数据源"按钮：连不上或表不在该数据源时，
     * 无需重跑整个 Action（重新选表名）即可直接换源重查。
     * 通知回调在 EDT，可直接弹选择器。
     */
    private static void notifyReselect(QueryContext ctx, String message, @NotNull String actionText) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification notification = new Notification("ZhiyinOneClickNavigation", "查看表结构",
                    message, NotificationType.ERROR);
            notification.setIcon(MyIcons.pandaIconSVG16_2);
            notification.addAction(new NotificationAction(actionText) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    n.expire();
                    showConnectionChooser(ctx, actionText);
                }
            });
            Notifications.Bus.notify(notification, ctx.project);
        });
    }

    /**
     * 是否为"数据源本身不可用"类失败（地址/端口错、网络不通、握手失败），
     * 区别于表不存在、权限不足等 SQL 语义错误。
     * MySQL 连接类异常的 SQLState 统一是 08xxx（如 08S01 Communications link failure）。
     */
    private static boolean isConnectionFailure(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 10; depth++) {
            if (cur instanceof SQLException) {
                String sqlState = ((SQLException) cur).getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("communications link failure")
                        || lower.contains("connection refused")
                        || lower.contains("connect timed out")
                        || lower.contains("unknownhost")
                        || lower.contains("unable to open jdbc connection")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
