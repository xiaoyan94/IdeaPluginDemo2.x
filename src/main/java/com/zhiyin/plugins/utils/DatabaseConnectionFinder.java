package com.zhiyin.plugins.utils;

import com.intellij.lang.properties.PropertiesFileType;
import com.intellij.lang.properties.psi.Property;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@Service(Service.Level.PROJECT)
public final class DatabaseConnectionFinder {

    public List<Map<String, String>> findDatabaseConnections(@NotNull Project project) {
        List<Map<String, String>> connectionInfoList = new ArrayList<>();

        collectPropertiesFiles(project, connectionInfoList);

        return connectionInfoList;
    }

    private void collectPropertiesFiles(Project project, List<Map<String, String>> connectionInfoList) {
        // FileTypeIndex / findPropertiesByKey 走 stub 索引，后台线程必须在 smart mode 下查询，
        // 否则启动期与索引器竞态触发 "Outdated stub in index"；
        // EDT 调用方（DataModelGenerator 对话框）不能阻塞等待 smart mode，保持原 read action 行为
        Runnable collector = () -> {
            Collection<VirtualFile> files = FileTypeIndex.getFiles(PropertiesFileType.INSTANCE, GlobalSearchScope.projectScope(project));
            for (VirtualFile file : files) {
                extractDatabaseConnectionInfo(file, connectionInfoList, project);
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().runReadAction(collector);
        } else {
            DumbService.getInstance(project).runReadActionInSmartMode(collector);
        }
    }

    private void extractDatabaseConnectionInfo(VirtualFile file, List<Map<String, String>> connectionInfoList, Project project) {
        if (file.getFileType() instanceof PropertiesFileType){
            List<Property> properties = new ArrayList<>();
            MyPropertiesUtil.getProperties(project, Collections.singletonList(file), "database.url", properties);
            if (!properties.isEmpty()){
                String url = properties.get(0).getValue();
                List<Property> username = MyPropertiesUtil.getProperties(project, Collections.singletonList(file), "database.username", new ArrayList<>());
                List<Property> password = MyPropertiesUtil.getProperties(project, Collections.singletonList(file), "database.password", new ArrayList<>());
                if (url != null && !url.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                    Map<String, String> connectionInfo = new HashMap<>();
                    connectionInfo.put("fileName", file.getName());
                    connectionInfo.put("url", url);
                    connectionInfo.put("username", username.get(0).getValue());
                    connectionInfo.put("password", password.get(0).getValue());
                    connectionInfoList.add(connectionInfo);
                }
            }
        }

    }
}
