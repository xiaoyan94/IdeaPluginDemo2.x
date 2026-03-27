package com.zhiyin.plugins.i18n;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class I18nFileListener {

    private final Project project;
    private final I18nCacheManager cacheManager;
    private final Map<String, VirtualFile> moduleI18nDirs = new HashMap<>();

    public I18nFileListener(Project project, I18nCacheManager cacheManager) {
        this.project = project;
        this.cacheManager = cacheManager;
    }

    public void register() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // 先收集所有模块的 i18n 目录
            Module[] modules = ModuleManager.getInstance(project).getModules();
            for (Module module : modules) {
                VirtualFile[] roots = ModuleRootManager.getInstance(module).getSourceRoots(false);
                for (VirtualFile root : roots) {
                    if (!root.getPath().contains("/resources")) continue;
                    VirtualFile i18nDir = root.findFileByRelativePath("i18n");
                    if (i18nDir != null && i18nDir.isDirectory()) {
                        moduleI18nDirs.put(module.getName(), i18nDir);
                    }
                }
            }
        });

        // 使用 MessageBus 注册 VFS 监听
        MessageBusConnection connection = project.getMessageBus().connect();
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile();
                    if (file == null) continue;

                    if (isI18nFile(file)) {
                        String module = findModuleForFile(file);
                        if (module == null) continue;

                        if (event instanceof VFileDeleteEvent) {
                            cacheManager.removeFile(module, file);
                            System.out.println("Removed cache for " + file.getPath());
                        } else {
                            cacheManager.loadSingleFile(module, file);
                            System.out.println("Updated cache for " + file.getPath());
                        }
                    }
                }
            }
        });

        System.out.println("Registered i18n file listener");
    }

    private boolean isI18nFile(VirtualFile file) {
        String name = file.getName();
        return name.endsWith(".properties") || name.endsWith(".properties.txt");
    }

    private String findModuleForFile(VirtualFile file) {
        for (Map.Entry<String, VirtualFile> entry : moduleI18nDirs.entrySet()) {
            if (VfsUtilCore.isAncestor(entry.getValue(), file, true)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
