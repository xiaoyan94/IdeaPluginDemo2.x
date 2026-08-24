package com.zhiyin.plugins.i18n;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import com.zhiyin.plugins.resources.Constants;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class I18nFileListener {

    private final Project project;
    private final I18nCacheManager cacheManager;

    public I18nFileListener(Project project, I18nCacheManager cacheManager) {
        this.project = project;
        this.cacheManager = cacheManager;
    }

    public void register() {
        // 使用 MessageBus 注册 VFS 监听
        MessageBusConnection connection = project.getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile();
                    if (file == null) continue;

                    if (isI18nFile(file)) {
                        String module = resolveModule(project, file);
                        if (module == null) continue;

                        if (event instanceof VFileDeleteEvent) {
                            cacheManager.removeFile(module, file);
                        } else {
                            cacheManager.loadSingleFile(module, file);
                        }
                    }
                }
            }
        });
    }

    /**
     * 解析文件所属模块。
     * 优先 ModuleUtilCore（按模块内容根定位，传统 Java Web 项目的 i18n 文件不在 resources/i18n
     * 目录下也能正确归属，与启动扫描 scanI18nFiles 的归属口径一致），
     * 找不到再回退按各模块 resources/i18n 目录逐个匹配。
     * VFS 事件回调线程上 PSI/模块查询需自带 read-action（ReadAction 嵌套无害）。
     */
    private static String resolveModule(Project project, VirtualFile file) {
        Module module = ReadAction.compute(() -> ModuleUtilCore.findModuleForFile(file, project));
        if (module != null) return module.getName();
        return I18nCacheManager.findModuleForFile(project, file);
    }

    /**
     * 只认 i18n 形态的文件名（web_ 前缀或四语言后缀），避免项目里任意 .properties
     * （config/database 等）的变更都触发模块解析与缓存重载。
     */
    private boolean isI18nFile(VirtualFile file) {
        String name = file.getName();
        if (!(name.endsWith(".properties") || name.endsWith(".properties.txt"))) return false;
        return name.startsWith("web_")
                || name.endsWith(Constants.I18N_ZH_CN_SUFFIX)
                || name.endsWith(Constants.I18N_ZH_TW_SUFFIX)
                || name.endsWith(Constants.I18N_EN_US_SUFFIX)
                || name.endsWith(Constants.I18N_VI_VN_SUFFIX);
    }
}
