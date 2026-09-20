package com.zhiyin.plugins.service;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zhiyin.plugins.i18n.I18nCacheManager;
import com.zhiyin.plugins.manager.HtmlFoldingManager;
import com.zhiyin.plugins.resources.Constants;
import com.zhiyin.plugins.utils.MyPropertiesUtil;
import com.zhiyin.plugins.utils.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * P2-2：i18n 三语言 properties 追加生成——把查询页生成器缺失的 i18n key 真正追加写入模块
 * 三个 datagrid properties 文件（zh_CN / zh_TW / en_US，vi_VN 不在本项范围）。
 *
 * <p>两阶段线程纪律（照 {@link MyPropertiesUtil#findModuleDataGridI18nPropertiesByValueBatch}）：
 * 后台阶段 {@link #prepareAppendContext}（翻译 Task 内调用）定位三语言文件 + 读现有 key 集合；
 * EDT 阶段 {@link #appendConfirmedEntries}（确认对话框「追加并生成」后调用）在 WriteCommandAction
 * 里逐文件写入。</p>
 *
 * <p>写入铁律：只追加绝不修改——按 key 判重（updateIfExist=false 语义），空翻译值在该语言文件
 * 追加 {@code # TODO: translate: <key>} 注释行（按文件现有文本判重，幂等重跑 +0 行）。</p>
 */
@Service(Service.Level.PROJECT)
public final class I18nGenerateService {
    private static final Logger LOG = Logger.getInstance(I18nGenerateService.class);

    /** 翻译快速失败阈值：开头连续 3 个字段翻译都抛异常则放弃剩余翻译（断网时不让用户等几十次超时） */
    static final int FAST_FAIL_TRANSLATION_THRESHOLD = 3;

    /**
     * P2-7 修复：Layout 查询时间范围 to 字段的跨页面共享 key（DengqiMes basic 模块 datagrid 三语言
     * properties 已存在该 key，值 到/到/To；其他模块缺该 key 时生成器追加，判重幂等）。
     * 与 BaseQueryTypeLayout.ftl to 分支 label 字面量保持一致。
     */
    public static final String TIME_RANGE_TO_I18N_KEY = "com.zhiyin.mes.app.order.ordergrid.to";

    private final Project project;

    public I18nGenerateService(Project project) {
        this.project = project;
    }

    // ==================== 数据载体 ====================

    /** 确认追加的单字段值（确认对话框「追加并生成」读回），fieldName 为生成器字段 name（小写口径） */
    public record I18nConfirmedAppend(String fieldName, String key, String zhCn, String zhTw, String enUs) {
    }

    /** 后台阶段产物：三语言 datagrid 文件（null = 未定位到）+ 各文件现有 key 集合（判重与幂等用） */
    public record I18nDatagridAppendContext(VirtualFile zhCnFile, VirtualFile zhTwFile, VirtualFile enUsFile,
                                            Set<String> zhCnExistingKeys, Set<String> zhTwExistingKeys,
                                            Set<String> enUsExistingKeys) {
    }

    /** 追加结果统计（并入 P1-1 生成汇总通知） */
    public record I18nAppendResult(int zhCnAdded, int zhTwAdded, int enUsAdded, int skippedExisting) {
        public String toSummarySuffix() {
            return buildAppendSummary(zhCnAdded, zhTwAdded, enUsAdded, skippedExisting);
        }
    }

    /** 三语言值选择器（en_US 值不 unicode 转义，照 EditableHtmlFoldingRenderer 写入口径） */
    private enum I18nLang {
        ZH_CN, ZH_TW, EN_US;

        String select(I18nConfirmedAppend append) {
            switch (this) {
                case ZH_CN:
                    return append.zhCn();
                case ZH_TW:
                    return append.zhTw();
                case EN_US:
                default:
                    return append.enUs();
            }
        }
    }

    // ==================== 后台阶段（翻译 Task 内调用） ====================

    /**
     * 定位模块三语言 datagrid properties 文件并读取现有 key 集合。
     * 线程纪律照 {@link MyPropertiesUtil#findModuleDataGridI18nPropertiesByValueBatch}：
     * EDT 用 runReadAction（不阻塞等 smart mode），后台线程在 smart mode 下执行。
     */
    public I18nDatagridAppendContext prepareAppendContext(Module module) {
        I18nDatagridAppendContext[] holder = new I18nDatagridAppendContext[1];
        Runnable collector = () -> holder[0] = doPrepareAppendContext(module);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().runReadAction(collector);
        } else {
            DumbService.getInstance(project).runReadActionInSmartMode(collector);
        }
        return holder[0];
    }

    private I18nDatagridAppendContext doPrepareAppendContext(Module module) {
        VirtualFile zhCnFile = locateDatagridFile(module, Constants.I18N_DATAGRID_ZH_CN_SUFFIX);
        VirtualFile zhTwFile = locateDatagridFile(module, Constants.I18N_DATAGRID_ZH_TW_SUFFIX);
        VirtualFile enUsFile = locateDatagridFile(module, Constants.I18N_DATAGRID_EN_US_SUFFIX);
        if (zhCnFile == null && zhTwFile == null && enUsFile == null) {
            LOG.warn("i18n 追加：未在模块 " + module.getName() + " 定位到任何 datagrid properties 文件");
        }
        return new I18nDatagridAppendContext(zhCnFile, zhTwFile, enUsFile,
                readExistingKeys(zhCnFile), readExistingKeys(zhTwFile), readExistingKeys(enUsFile));
    }

    /**
     * 单语言文件定位，口径照 {@link MyPropertiesUtil#addPropertyByFileNames}：
     * 先 FilenameIndex 按精确文件名（模块 scope，{@code <模块简单名>.datagrid_xx_XX.properties}），
     * 为空再 FileTypeIndex 全量 properties 兜底 endsWith + datagrid 一致性过滤。
     * 多命中只取第一个写入（避免同 key 重复写多份），记 warn。
     */
    private VirtualFile locateDatagridFile(Module module, String fileSuffix) {
        String fileName = MyPropertiesUtil.getSimpleModuleName(module) + fileSuffix;
        List<VirtualFile> files = new ArrayList<>(
                FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.moduleScope(module)));
        if (files.isEmpty()) {
            Collection<VirtualFile> allProperties = FileTypeIndex.getFiles(
                    FileTypeManager.getInstance().getFileTypeByExtension("properties"), GlobalSearchScope.moduleScope(module));
            files = allProperties.stream()
                    .filter(vf -> vf.getName().endsWith(fileName))
                    .filter(vf -> vf.getName().contains("datagrid"))
                    .collect(Collectors.toList());
        }
        if (files.isEmpty()) {
            return null;
        }
        if (files.size() > 1) {
            LOG.warn("i18n 追加：模块内定位到多个 " + fileName + "，只写第一个：" + files.get(0).getPath());
        }
        return files.get(0);
    }

    private Set<String> readExistingKeys(@Nullable VirtualFile file) {
        if (file == null || !file.isValid()) {
            return Collections.emptySet();
        }
        PropertiesFile propertiesFile = (PropertiesFile) PsiManager.getInstance(project).findFile(file);
        if (propertiesFile == null) {
            return Collections.emptySet();
        }
        Set<String> keys = new HashSet<>();
        for (IProperty property : propertiesFile.getProperties()) {
            keys.add(property.getKey());
        }
        return keys;
    }

    // ==================== EDT 阶段（确认对话框「追加并生成」后调用） ====================

    /**
     * 确认后的写入入口（EDT，WriteCommandAction 内逐文件追加）：
     * 正常条目走 PSI {@code PropertiesFile.addProperty}；空翻译值走 TODO 注释行追加（文本判重幂等）。
     * dsp 派生：字段名精确匹配 state/status/type 的条目顺带补 {@code <field>dsp} key（三语言值与原字段相同）。
     * 返回各文件追加行数统计（含 dsp 条目）。
     */
    public I18nAppendResult appendConfirmedEntries(@Nullable Module module,
                                                   @Nullable I18nDatagridAppendContext context,
                                                   @Nullable Collection<I18nConfirmedAppend> confirmedAppends) {
        if (context == null || confirmedAppends == null || confirmedAppends.isEmpty()) {
            return new I18nAppendResult(0, 0, 0, 0);
        }
        List<I18nConfirmedAppend> expanded = new ArrayList<>();
        for (I18nConfirmedAppend append : confirmedAppends) {
            expanded.add(append);
            String dspKey = deriveDspKey(append.fieldName(), append.key());
            if (dspKey != null) {
                expanded.add(new I18nConfirmedAppend(append.fieldName() + "dsp", dspKey,
                        append.zhCn(), append.zhTw(), append.enUs()));
            }
        }
        AtomicInteger skippedExisting = new AtomicInteger();
        int[] added = new int[3];
        WriteCommandAction.runWriteCommandAction(project, () -> {
            added[0] = appendToFile(context.zhCnFile(), context.zhCnExistingKeys(), expanded, I18nLang.ZH_CN, skippedExisting);
            added[1] = appendToFile(context.zhTwFile(), context.zhTwExistingKeys(), expanded, I18nLang.ZH_TW, skippedExisting);
            added[2] = appendToFile(context.enUsFile(), context.enUsExistingKeys(), expanded, I18nLang.EN_US, skippedExisting);
        });
        refreshAfterAppend(module, context);
        return new I18nAppendResult(added[0], added[1], added[2], skippedExisting.get());
    }

    /**
     * P2-7 修复：Layout 查询时间范围 from/to 派生 key 追加（确定性派生不进确认弹窗，同 dsp 派生口径）。
     * 与 {@link #appendConfirmedEntries} 共用 {@link #appendToFile} 幂等只追加管道，但不做 dsp 展开
     * （条目 fieldName 为派生名 createtimefrom/to，不匹配 state/status/type 约定）；空翻译值同样走
     * TODO 注释行降级。返回各文件追加行数统计（并入生成汇总通知）。
     */
    public I18nAppendResult appendDerivedEntries(@Nullable Module module,
                                                 @Nullable I18nDatagridAppendContext context,
                                                 @Nullable Collection<I18nConfirmedAppend> derivedAppends) {
        if (context == null || derivedAppends == null || derivedAppends.isEmpty()) {
            return new I18nAppendResult(0, 0, 0, 0);
        }
        List<I18nConfirmedAppend> entries = new ArrayList<>(derivedAppends);
        AtomicInteger skippedExisting = new AtomicInteger();
        int[] added = new int[3];
        WriteCommandAction.runWriteCommandAction(project, () -> {
            added[0] = appendToFile(context.zhCnFile(), context.zhCnExistingKeys(), entries, I18nLang.ZH_CN, skippedExisting);
            added[1] = appendToFile(context.zhTwFile(), context.zhTwExistingKeys(), entries, I18nLang.ZH_TW, skippedExisting);
            added[2] = appendToFile(context.enUsFile(), context.enUsExistingKeys(), entries, I18nLang.EN_US, skippedExisting);
        });
        refreshAfterAppend(module, context);
        return new I18nAppendResult(added[0], added[1], added[2], skippedExisting.get());
    }

    /** 单语言文件写入；skippedExisting 只在主语言 zh_CN 上按 key 计数一次（跨语言不重复计） */
    private int appendToFile(@Nullable VirtualFile file, Set<String> existingKeys, List<I18nConfirmedAppend> appends,
                             I18nLang lang, AtomicInteger skippedExisting) {
        if (file == null || !file.isValid()) {
            LOG.warn("i18n 追加：" + lang + " datagrid properties 文件未定位到，跳过该语言追加");
            return 0;
        }
        PropertiesFile propertiesFile = (PropertiesFile) PsiManager.getInstance(project).findFile(file);
        if (propertiesFile == null) {
            LOG.warn("i18n 追加：无法解析 " + lang + " 文件 " + file.getPath());
            return 0;
        }
        boolean native2Ascii = MyPropertiesUtil.isNative2AsciiForPropertiesFiles();
        int added = 0;
        for (I18nConfirmedAppend append : appends) {
            String key = append.key();
            // 幂等只追加绝不修改：后台预读 key 集合或 PSI 现查任一命中即跳过（updateIfExist=false 语义）
            if (isKeySkipped(existingKeys, key) || propertiesFile.findPropertyByKey(key) != null) {
                if (lang == I18nLang.ZH_CN) {
                    skippedExisting.incrementAndGet();
                }
                continue;
            }
            String value = lang.select(append);
            if (value == null || value.trim().isEmpty()) {
                // 空值降级：该语言文件追加 TODO 注释行（读文件现有文本判重，幂等重跑 +0 行）
                if (!containsTodoLine(propertiesFile.getText(), key)) {
                    appendTextLine(propertiesFile, buildTodoTranslateLine(key));
                    added++;
                }
            } else {
                // 转义口径照 EditableHtmlFoldingRenderer：native2ascii 开启写原值、关闭预转 unicode 转义；en 值不转义
                propertiesFile.addProperty(key, lang == I18nLang.EN_US ? value : escapePropertyValueForWrite(value, native2Ascii));
                added++;
            }
        }
        return added;
    }

    /** TODO 注释行走 Document 追加（properties PSI 无公开注释工厂，Document 追加版本稳妥且幂等可判重）。
     *  本平台版本 PropertiesFile 为门面接口（非 PsiFile 子类型），取 getContainingFile() 再换 Document */
    private void appendTextLine(PropertiesFile propertiesFile, String line) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(propertiesFile.getContainingFile());
        if (document == null) {
            LOG.warn("i18n 追加 TODO 注释行失败：无法获取文档 " + propertiesFile.getName());
            return;
        }
        String text = document.getText();
        String separator = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
        document.insertString(document.getTextLength(), separator + line + "\n");
    }

    /** 写后善后：标脏 + VFS 刷新 + I18nCacheManager 缓存重载 + inlay 刷新（照 addPropertyByFileNames 收尾） */
    private void refreshAfterAppend(@Nullable Module module, I18nDatagridAppendContext context) {
        List<VirtualFile> touched = new ArrayList<>();
        for (VirtualFile file : new VirtualFile[]{context.zhCnFile(), context.zhTwFile(), context.enUsFile()}) {
            if (file != null && file.isValid()) {
                touched.add(file);
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        for (VirtualFile file : touched) {
            VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            file.refresh(false, false);
        }
        I18nCacheManager cacheManager = project.getService(I18nCacheManager.class);
        for (VirtualFile file : touched) {
            String moduleName = I18nCacheManager.findModuleForFile(project, file);
            if (moduleName == null) {
                moduleName = module == null ? "" : MyPropertiesUtil.getSimpleModuleName(module);
            }
            cacheManager.loadSingleFile(moduleName, file);
        }
        HtmlFoldingManager.refreshAllEditorsInlays(project);
    }

    // ==================== 纯逻辑（包级静态，供单测锁定） ====================

    /**
     * P2-2 dsp 派生（口径已确认）：字段名精确匹配 state / status / type（生成器字段 name 为小写）
     * 时顺带补 {@code <field>dsp} key；routingtype / statetype / types 等后缀匹配与其他字段不派生
     * （真实项目实证 state/statedsp、type/typedsp、status/statusdsp 三对，RoutingTypeDsp 复用原 key 属反例）。
     */
    static boolean isDspField(String fieldName) {
        return "state".equals(fieldName) || "status".equals(fieldName) || "type".equals(fieldName);
    }

    /**
     * dsp 约定字段的默认标题（真实项目惯例实证：state/status 的 chs 均为「状态」、type 为「类型」，
     * 且模板对 endsWith('state'/'status') 字段无条件生成 dsp 显示列）。comment 为空时以默认标题
     * 进确认清单，用户可在对话框改；非 dsp 约定字段返回 null（无 comment 仍不进清单）。
     */
    static String defaultDspFieldTitle(String fieldName) {
        if ("state".equals(fieldName) || "status".equals(fieldName)) {
            return "状态";
        }
        return "type".equals(fieldName) ? "类型" : null;
    }

    /** dsp key = 拟生成 key 追加 dsp 后缀（proposedKey 以 "." + fieldName 结尾）；非 dsp 字段返回 null */
    static String deriveDspKey(String fieldName, String proposedKey) {
        return isDspField(fieldName) ? proposedKey + "dsp" : null;
    }

    /** TODO 降级行格式：# TODO: translate: &lt;key&gt; */
    static String buildTodoTranslateLine(String key) {
        return "# TODO: translate: " + key;
    }

    /**
     * P2-7 修复：时间范围 from 派生条目（DengqiMes RcsOrderSyncRecord 实证：from 的 label =
     * &lt;列 key&gt;_from）——key = 列 key 追加 _from，值 = 列 zhCn+「从」/ zhTw+「從」/
     * enUs+「 From」（某语言基值缺失留空 → 追加时走 TODO 注释行机制）。
     */
    static I18nConfirmedAppend deriveTimeRangeFromEntry(String fieldName, String columnKey,
                                                        @Nullable String zhCn, @Nullable String zhTw, @Nullable String enUs) {
        return new I18nConfirmedAppend(fieldName + "from", columnKey + "_from",
                concatDerivedSuffix(zhCn, "从"), concatDerivedSuffix(zhTw, "從"), concatDerivedSuffix(enUs, " From"));
    }

    /** 时间范围 to 派生条目：固定跨页面共享 key，三值 到/到/To（DengqiMes basic datagrid 实证值） */
    static I18nConfirmedAppend deriveTimeRangeToEntry() {
        return new I18nConfirmedAppend("to", TIME_RANGE_TO_I18N_KEY, "到", "到", "To");
    }

    /** 派生值拼接：基值空白留空（走 TODO 注释行降级），否则基值+后缀（en 后缀自带前导空格） */
    static String concatDerivedSuffix(@Nullable String base, String suffix) {
        return base == null || base.trim().isEmpty() ? "" : base + suffix;
    }

    /** TODO 注释行文本判重：文件现有文本已含该行则不重复追加（幂等重跑 +0 行） */
    static boolean containsTodoLine(@Nullable String fileText, String key) {
        return fileText != null && fileText.contains(buildTodoTranslateLine(key));
    }

    /** 幂等过滤：现有 key 集合含该 key 时条目跳过（updateIfExist=false 语义，绝不修改已有行） */
    static boolean isKeySkipped(@Nullable Set<String> existingKeys, String key) {
        return existingKeys != null && existingKeys.contains(key);
    }

    /**
     * 值转义口径（照 EditableHtmlFoldingRenderer 写入侧）：native2ascii 开启时写原值
     * （IDE 透明转换）；关闭时预转 unicode 转义序列（{@code StringUtil.stringToUnicode} 全字符转义）。
     * en_US 值不走本方法（照渲染器口径写原样）。
     */
    static String escapePropertyValueForWrite(String value, boolean native2AsciiEnabled) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return native2AsciiEnabled ? value : StringUtil.stringToUnicode(value);
    }

    /** 正常行组装：key=value（值中的空格/等号/冒号在 properties 语义下属值的一部分，可正常解析） */
    static String buildPropertyLine(String key, @Nullable String value) {
        return key + "=" + (value == null ? "" : value);
    }

    /** 翻译快速失败判定：开头连续 N 个字段翻译全抛异常（N ≥ 3）→ 放弃剩余翻译 */
    public static boolean shouldFastFailTranslations(int leadingConsecutiveFailures) {
        return leadingConsecutiveFailures >= FAST_FAIL_TRANSLATION_THRESHOLD;
    }

    /** 追加统计摘要（拼在 notifyGenerateSummary 消息末尾） */
    static String buildAppendSummary(int zhCnAdded, int zhTwAdded, int enUsAdded, int skippedExisting) {
        return "；i18n 追加：zh_CN +" + zhCnAdded + "、zh_TW +" + zhTwAdded + "、en_US +" + enUsAdded
                + "、跳过已存在 " + skippedExisting;
    }
}
