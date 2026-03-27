package com.zhiyin.plugins.microservices;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressManager;
import com.zhiyin.plugins.notification.MyPluginMessages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project-level service that holds a cache of URL -> SmartPsiElementPointer<PsiMethod>
 * Use startScan(...) to trigger a (background) scan that will populate the cache.
 */
@Service(Service.Level.PROJECT)
public final class ControllerMappingService {
    private final Project project;

    /**
     * Map URL -> list of method pointers (multiple methods may map same URL across modules)
     */
    // private final ConcurrentHashMap<String, List<SmartPsiElementPointer<PsiMethod>>> urlToMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<MethodInfo>> urlToMethods = new ConcurrentHashMap<>();


    /**
     * simple flag to avoid overlapping scans
     */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    public ControllerMappingService(Project project) {
        this.project = project;
    }

    public static ControllerMappingService getInstance(@NotNull Project project) {
        return project.getService(ControllerMappingService.class);
    }

    /**
     * Trigger a background scan. If a scan is in progress and `force`==false, skip.
     * This method returns immediately; scan runs in background and updates cache when done.
     * <p>
     * The scanning logic respects IDEA thread model: it schedules work to run when project is smart,
     * and the heavy PSI reading is performed inside ReadAction.nonBlocking within ControllerScanner.
     *
     * @param force if true, re-scan even if another scan in progress
     */
    public void startScan(boolean force, @Nullable Runnable callback) {
        if (!force && scanning.get()) {
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            return;
        }

        DumbService.getInstance(project).runWhenSmart(() -> {
            // Create progress task to show cancel if needed; scanning runs in pooled thread and uses ReadAction.nonBlocking inside.
            ProgressManager.getInstance().runProcess(() -> {
                try {
                    ControllerScanner.scheduleScan(project, result -> {
                        // replace cache atomically (we swap reference entries)
                        System.out.println("collectControllerUrls: Replacing cache with " + result.size() + " entries");
                        replaceCache(result);
                        // System.out.println("collectControllerUrls: Done, found " + urlToMethods.size() + " URLs, " + getMethodCount() + " methods");
                        MyPluginMessages.showInfo("Controller scan", "Controller scan completed. Found " + getUrlCount() + " URLs, " + getMethodCount() + " methods");

                        if (callback != null) {
                            callback.run();
                        }
                    });
                } finally {
                    scanning.set(false);
                }
            }, null);
        });

    }

    public void startScan(boolean force) {
        startScan(force, null);
    }

    private void replaceCache(Map<String, List<MethodInfo>> newMap) {
        // replace references in a thread-safe manner
        urlToMethods.clear();
        urlToMethods.putAll(newMap);
    }

    /**
     * Get a snapshot (immutable copy) of current mapping.
     */
    @NotNull
    public Map<String, List<MethodInfo>> getMappingSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(urlToMethods));
    }

    /**
     * Convenience: how many URL entries currently cached
     */
    public int getUrlCount() {
        return urlToMethods.size();
    }

    /**
     * Convenience: total methods cached (sum of list sizes)
     */
    public int getMethodCount() {
        return urlToMethods.values().stream().mapToInt(List::size).sum();
    }


}
