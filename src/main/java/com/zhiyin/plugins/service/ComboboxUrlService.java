package com.zhiyin.plugins.service;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class ComboboxUrlService {

    /**
     * 空结果的有效期：空集大概率是“扫描时机不对”的产物（项目刚打开、索引刚从 dumb mode 恢复、
     * Gradle import 进行中模块根不全），不能永久负缓存锁死；过期后下次补全触发重扫自愈。
     * 非空结果不参与过期（与旧行为一致，靠手动 Action 强制刷新）。
     */
    private static final long EMPTY_RESULT_TTL_MS = 10_000L;

    private final Project project;
    private final Map<String, Map<String, CacheEntry>> cache = new ConcurrentHashMap<>();

    /** 扫描结果 + 扫描时间；scannedAtMs 仅用于空结果的 TTL 判定 */
    private static final class CacheEntry {
        final Set<String> values;
        final long scannedAtMs;

        CacheEntry(Set<String> values, long scannedAtMs) {
            this.values = values;
            this.scannedAtMs = scannedAtMs;
        }
    }

    public ComboboxUrlService(Project project) {
        this.project = project;
        searchAndCacheXmlTags("ComboxUrl", "value");
        searchAndCacheXmlTags("Field", "easyuiClass");
    }

    public Set<String> getCachedResults(String tagName, String attr) {
        CacheEntry entry = getCached(tagName, attr);
        // 未命中、或空结果超过 TTL（可能来自不完整扫描）才重扫；
        // 补全热路径最坏每 TTL 一次全项目扫描，避免旧行为“空集每次补全都重扫”的卡顿
        if (entry == null || isExpiredEmpty(entry)) {
            searchAndCacheXmlTags(tagName, attr);
            entry = getCached(tagName, attr);
        }
        return new HashSet<>(entry == null || entry.values == null ? Collections.emptySet() : entry.values);
    }

    private static boolean isExpiredEmpty(CacheEntry entry) {
        return entry.values.isEmpty()
               && System.currentTimeMillis() - entry.scannedAtMs > EMPTY_RESULT_TTL_MS;
    }

    private CacheEntry getCached(String tagName, String attr) {
        Map<String, CacheEntry> row = cache.get(tagName);
        return row == null ? null : row.get(attr);
    }

    public void searchAndCacheXmlTags(String tagName, String attr) {
        // dumb mode（项目打开/更新索引中）下 FileTypeIndex 返回不完整结果，扫到的空集一旦
        // 入缓存会被当成有效负缓存锁死，补全从此一直为空——宁可不扫，等 smart 后由 getCachedResults 重试
        if (DumbService.isDumb(project)) {
            return;
        }
        Set<String> results = ReadAction.compute(() -> searchXmlTags(tagName, attr));
        cache.computeIfAbsent(tagName, k -> new ConcurrentHashMap<>())
             .put(attr, new CacheEntry(results, System.currentTimeMillis()));
    }

    private Set<String> searchXmlTags(String tagName, String attr) {
        Set<String> results = new HashSet<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);

        Collection<VirtualFile> virtualFiles = FileTypeIndex.getFiles(XmlFileType.INSTANCE, scope);

        for (VirtualFile virtualFile : virtualFiles) {
            PsiFile psiFile = psiManager.findFile(virtualFile);
            if (psiFile instanceof XmlFile) {
                XmlFile xmlFile = (XmlFile) psiFile;
                if (xmlFile.getRootTag() == null) continue;
                if (!(xmlFile.getRootTag().getName().equals("ViewDefine") || xmlFile.getRootTag().getName().equals("DataGrid"))) continue;
                Collection<XmlTag> tags = PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class);
                for (XmlTag tag : tags) {
                    if (tag.getName().equals(tagName) && tag.getAttributeValue(attr) != null) {
                        results.add(tag.getAttributeValue(attr));
                    }
                }
            }
        }

        return results;
    }
}
