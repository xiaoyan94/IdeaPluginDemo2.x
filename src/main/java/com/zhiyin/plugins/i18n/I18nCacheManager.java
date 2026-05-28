package com.zhiyin.plugins.i18n;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * 管理每个模块的 I18n 缓存
 */
@Service(Service.Level.PROJECT)
public final class I18nCacheManager {

    private static final Logger LOG = Logger.getInstance(I18nCacheManager.class);

    @SuppressWarnings("FieldCanBeLocal")
    private final Project project;

    /**
     * Project Service 构造函数
     */
    public I18nCacheManager(Project project) {
        this.project = project;
        LOG.info("I18nCacheManager initialized for project: " + project.getName());
    }

    /**
     * Module 名 -> 模块缓存
     */
    private final Map<String, ModuleI18nCache> i18nCacheByModule = new ConcurrentHashMap<>();

    public ModuleI18nCache getModuleCache(String moduleName) {
        return i18nCacheByModule.computeIfAbsent(moduleName, k -> new ModuleI18nCache());
    }

    public Map<String, ModuleI18nCache> getAllModuleCaches() {
        return i18nCacheByModule;
    }

    /**
     * 扫描指定 Module 的 i18n 目录
     */
    public void scanI18nDir(String moduleName, VirtualFile i18nDir) {
        ModuleI18nCache moduleCache = getModuleCache(moduleName);

        if (i18nDir == null || !i18nDir.isDirectory()) return;

        for (VirtualFile file : i18nDir.getChildren()) {
            if (file.isDirectory() && "datagrid".equals(file.getName())) {
                // datagrid 目录下
                for (VirtualFile dataFile : file.getChildren()) {
                    String lang = extractLanguage(dataFile.getName());
                    Map<String, String> langMap = moduleCache.datagridResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
                    loadProperties(langMap, dataFile);
                }
            } else {
                // 主资源目录
                String lang = extractLanguage(file.getName());
                if (file.getName().startsWith("web_")) {
                    Map<String, String> langMap = moduleCache.webResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
                    loadProperties(langMap, file);
                } else {
                    Map<String, String> langMap = moduleCache.otherResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
                    loadProperties(langMap, file);
                }
            }
        }
    }

    private void loadProperties(Map<String, String> cacheMap, VirtualFile file) {
        try {
            String text = VfsUtilCore.loadText(file); // 通过 VFS 读取文件文本
            Properties props = new Properties();
            props.load(new java.io.StringReader(text)); // 用 StringReader 加载 Properties

            props.forEach((k, v) -> cacheMap.put((String) k, (String) v));

            LOG.info("Loaded " + file.getPath() + " (" + cacheMap.size() + " entries)");
        } catch (Exception ex) {
            LOG.warn("Failed to load properties from " + file.getPath(), ex);
        }
    }

    /**
     * 提取语言标识
     */
    private String extractLanguage(String fileName) {
        // 匹配最后一个 _ 后面到 .properties 的内容
        // 例如：web_zh_CN.properties -> zh_CN
        //          wms.datagrid_en_US.properties -> en_US
        // 正则：_([a-z]{2}_[A-Z]{2})\.properties$
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("_([a-z]{2}_[A-Z]{2})\\.properties$");
        java.util.regex.Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            String languageSuffix = matcher.group(1);
            for (String supportedLanguage : SUPPORTED_LANGUAGES) {
                if (supportedLanguage.equalsIgnoreCase(languageSuffix)) {
                    return supportedLanguage; // 统一标识
                }
            }
            return languageSuffix; // 返回 zh_CN, en_US 等
        }

        LOG.warn("Failed to extract language from " + fileName + " (use default) ");
        return "default";
    }

    /**
     * 每个模块的缓存
     */
    public static class ModuleI18nCache {
        public final Map<String, Map<String, String>> webResources = new ConcurrentHashMap<>();
        public final Map<String, Map<String, String>> otherResources = new ConcurrentHashMap<>();
        public final Map<String, Map<String, String>> datagridResources = new ConcurrentHashMap<>();
    }

    public enum ResourceType {
        WEB,
        OTHER,
        DATAGRID
    }

    /**
     * 获取指定模块、类型、语言、键的值
     */
    public String getValue(String moduleName, ResourceType type, String language, String key) {
        if (moduleName == null || moduleName.isEmpty()) {
            // 从jar打开的文件没有指定模块，尝试从所有模块中查找。各模块已加载对应依赖库jar里的资源串文件。
            for (Map.Entry<String, ModuleI18nCache> moduleI18nCacheEntry : i18nCacheByModule.entrySet()) {
                ModuleI18nCache moduleCache = moduleI18nCacheEntry.getValue();

                Map<String, Map<String, String>> targetMap = getResourceMap(moduleCache, type);
                if (targetMap == null) continue;

                Map<String, String> langMap = targetMap.get(language);
                if (langMap == null) continue;

                if (langMap.containsKey(key)) {
                    return langMap.get(key);
                }
            }
        }

        if (moduleName == null || moduleName.isEmpty()) {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            for (Module m : modules) {
                // 如果从jar包打开，则module默认取模块名包含basic的模块
                /*if (m.getName().toLowerCase().contains("basic")) {
                    moduleName = m.getName();
                    break;
                }*/
                if (m.getName() != null && !m.getName().isBlank()) {
                    String valRecursive = getValue(m.getName(), type, language, key);
                    if (valRecursive != null) return valRecursive;
                }

            }
            // 所有模块都没有找到
            return null;
        }

        ModuleI18nCache moduleCache = i18nCacheByModule.get(moduleName);
        if (moduleCache == null) return null;

        Map<String, Map<String, String>> targetMap = getResourceMap(moduleCache, type);
        if (targetMap == null) return null;

        Map<String, String> langMap = targetMap.get(language);
        if (langMap == null) return null;

        return langMap.getOrDefault(key, null);
    }

    public static final String ZH_CN = "zh_CN";
    public static final String EN_US = "en_US";
    public static final String ZH_TW = "zh_TW";
    public static final String VI_VN = "vi_VN";

    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList(ZH_CN, EN_US, ZH_TW, VI_VN);

    /**
     * 获取指定模块、类型、键下所有语言的值
     */
    public Map<String, String> getValuesByKey(String moduleName, ResourceType type, String key) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();

        for (String language : SUPPORTED_LANGUAGES) {
            String value = getValue(moduleName, type, language, key);
            if (value != null) {
                map.put(language, value);
            }
        }

        return map;
    }

    /**
     * 获取所有模块的类型、键下所有语言的值
     */
    public Map<String, String> getValuesByKey(ResourceType type, String key) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();

        for (String language : SUPPORTED_LANGUAGES) {
            String value = getValue(null, type, language, key);
            if (value != null) {
                map.put(language, value);
            }
        }

        return map;
    }

    /**
     * 获取指定模块、类型、语言下所有键值对
     */
    public Map<String, String> getAll(String moduleName, ResourceType type, String language) {
        ModuleI18nCache moduleCache = i18nCacheByModule.get(moduleName);
        if (moduleCache == null) return Map.of();

        Map<String, Map<String, String>> targetMap = getResourceMap(moduleCache, type);
        if (targetMap == null) return Map.of();

        return targetMap.getOrDefault(language, Map.of());
    }

    /**
     * 内部方法：根据类型获取对应缓存 Map
     */
    private Map<String, Map<String, String>> getResourceMap(ModuleI18nCache moduleCache, ResourceType type) {
        return switch (type) {
            case WEB -> moduleCache.webResources;
            case OTHER -> moduleCache.otherResources;
            case DATAGRID -> moduleCache.datagridResources;
        };
    }

    /**
     * 根据单个文件更新缓存
     */
    public void loadSingleFile(String moduleName, VirtualFile file) {
        if (file == null || file.isDirectory()) return;

        ModuleI18nCache moduleCache = getModuleCache(moduleName);
        String lang = extractLanguage(file.getName());

        try {
            String text = VfsUtilCore.loadText(file);
            Properties props = new Properties();
            props.load(new java.io.StringReader(text));

            Map<String, String> targetMap;
            if (file.getParent() != null && "datagrid".equals(file.getParent().getName())) {
                targetMap = moduleCache.datagridResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
            } else if (file.getName().startsWith("web")) {
                targetMap = moduleCache.webResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
            } else {
                targetMap = moduleCache.otherResources.computeIfAbsent(lang, k -> new ConcurrentHashMap<>());
            }

            // 清空旧值，加载新值
            targetMap.clear();
            props.forEach((k, v) -> targetMap.put((String) k, (String) v));

            System.out.println("Loaded single file: " + file.getPath() + " (" + targetMap.size() + " entries)");
        } catch (Exception ex) {
            LOG.warn("Failed to load single file: " + file.getPath(), ex);
        }
    }

    public void removeFile(String moduleName, VirtualFile file) {
        ModuleI18nCache moduleCache = i18nCacheByModule.get(moduleName);
        if (moduleCache == null) return;

        String lang = extractLanguage(file.getName());
        ResourceType type = detectResourceType(file);

        Map<String, Map<String, String>> targetMap = getResourceMap(moduleCache, type);

        if (targetMap != null) {
            Map<String, String> langMap = targetMap.get(lang);
            if (langMap != null) {
                // 清空整个语言缓存也行，但更合理的是只清除与该文件相关的 key
                // 因为我们没记录文件来源，只能全清
                targetMap.remove(lang);
                System.out.println("Removed cache for " + file.getPath());
                LOG.info("Removed cache for " + file.getPath());
            }
        }
    }

    /**
     * 根据文件路径判断资源类型（web、other、datagrid）
     */
    private ResourceType detectResourceType(VirtualFile file) {
        if (file.getParent() == null) return ResourceType.OTHER;
        if ("datagrid".equals(file.getParent().getName())) {
            return ResourceType.DATAGRID;
        } else if (file.getName().startsWith("web")) {
            return ResourceType.WEB;
        } else {
            return ResourceType.OTHER;
        }
    }

    /**
     * 查找文件所属的模块名
     * 通过检查文件是否位于模块的 i18n 目录下来确定
     */
    public static String findModuleForFile(Project project, VirtualFile file) {
        if (project == null || file == null) return null;

        com.intellij.openapi.module.ModuleManager moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project);
        com.intellij.openapi.module.Module[] modules = moduleManager.getModules();

        for (com.intellij.openapi.module.Module module : modules) {
            com.intellij.openapi.roots.ModuleRootManager rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module);
            VirtualFile[] roots = rootManager.getSourceRoots(false);
            for (VirtualFile root : roots) {
                if (!root.getPath().contains("/resources")) continue;
                VirtualFile i18nDir = root.findFileByRelativePath("i18n");
                if (i18nDir != null && i18nDir.isDirectory()) {
                    if (com.intellij.openapi.vfs.VfsUtilCore.isAncestor(i18nDir, file, true)) {
                        return module.getName();
                    }
                }
            }
        }
        return null;
    }

}
