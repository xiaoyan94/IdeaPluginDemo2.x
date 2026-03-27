package com.zhiyin.plugins.service;

import com.intellij.openapi.components.Service;
import com.zhiyin.plugins.toolWindow.MyToolWindowUI;
import com.zhiyin.plugins.toolWindow.NewControllerToolWindowUI;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class MyToolWindowService {
    private NewControllerToolWindowUI ui;

    public void setUI(NewControllerToolWindowUI ui) {
        this.ui = ui;
    }

    @Nullable
    public NewControllerToolWindowUI getUI() {
        return ui;
    }
}
