package com.zhiyin.plugins.service;

import com.intellij.openapi.components.Service;
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

    private final Project project;
    private final Map<String, Map<String, Set<String>>> cache = new ConcurrentHashMap<>();

    public ComboboxUrlService(Project project) {
        this.project = project;
        searchAndCacheXmlTags("ComboxUrl", "value");
        searchAndCacheXmlTags("Field", "easyuiClass");
    }

    public Set<String> getCachedResults(String tagName, String attr) {
        Set<String> list = getCached(tagName, attr);
        if (list == null) {
            // 未命中才重扫；缓存的空集视为有效负缓存，避免补全热路径反复全项目扫描
            searchAndCacheXmlTags(tagName, attr);
            list = getCached(tagName, attr);
        }
        return new HashSet<>(list == null ? Collections.emptySet() : list);
    }

    private Set<String> getCached(String tagName, String attr) {
        Map<String, Set<String>> row = cache.get(tagName);
        return row == null ? null : row.get(attr);
    }

    public void searchAndCacheXmlTags(String tagName, String attr) {
        Set<String> results = searchXmlTags(tagName, attr);
        cache.computeIfAbsent(tagName, k -> new ConcurrentHashMap<>()).put(attr, results);
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
