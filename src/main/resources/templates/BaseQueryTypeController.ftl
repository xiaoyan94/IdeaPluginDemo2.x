<#include "common.ftl">
<#-- P1-6：导出框架变体开关——缺省/旧值渲染旧写法（零字节漂移），easyexcel2 渲染新写法 -->
<#assign exportFramework2 = (exportFramework!'easyexcel') == 'easyexcel2'>
package com.zhiyin.controller<#if packageName?startsWith("basic")><#else>.mes</#if>.${packageName};

import com.alibaba.fastjson.JSONObject;
<#if generateImport>
import com.zhiyin.aspect.SysLogger;
</#if>
import com.zhiyin.controller.BaseController;
import com.zhiyin.i18n.I18nUtil;
<#if generateExport>
import com.zhiyin.service.excel.<#if exportFramework2>EasyExcel2Utils<#else>EasyExcelUtils</#if>;
import com.zhiyin.service.excel.ExcelExportService;
</#if>
import com.zhiyin.service.${packageName}.${ObjectName}Service;
<#if generateExport && !exportFramework2>
import com.zhiyin.utils.DateUtils;
</#if>
import com.zhiyin.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
<#if generateImport>
import java.io.FileInputStream;
</#if>
<#if generateExport && !exportFramework2>
import java.util.Date;
</#if>
<#if generateImport>
import java.util.HashMap;
</#if>
<#if generateExport && !exportFramework2>
import java.util.List;
</#if>
import java.util.Map;

/**
 * ${fileName} Controller
 */
@Controller
@RequestMapping(value = "/${appName}")
public class ${ObjectName}Controller extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ${ObjectName}Service ${objectName}Service;
<#if generateExport>

    @Resource
    private ExcelExportService excelExportService;
</#if>
<#if generateExport && exportFramework2>

    @Resource
    private EasyExcel2Utils EasyExcel2Utils;
</#if>

    @RequestMapping(value = "/${ObjectName}", method = {RequestMethod.POST, RequestMethod.GET})
    public ModelAndView create${ObjectName}View(HttpServletRequest request) throws Exception {
        Map parameterMap = getParameterMap(request);
        return createMultiGridPatternView(parameterMap, this.getClass(), "MesRoot/${appName}/${ObjectName}", request.getServletPath());
    }

    @RequestMapping(value = "/${ObjectName}/query${ObjectName}List", method = {RequestMethod.POST, RequestMethod.GET}, produces = "application/json; charset=utf-8")
    @ResponseBody
    public String query${ObjectName}List(HttpServletRequest request) {
        JSONObject json = new JSONObject();
        Map<String, Object> params = getParameterMap(request);
        try {
            Map retMap = ${objectName}Service.query${ObjectName}List(params);
            return wrapperSuccess(retMap, json);
        } catch (Exception e) {
            logger.error("${ObjectName}Controller::query${ObjectName}List catch exception:", e);
            return wrapperException(params, e, json);
        }
    }
<#if generateExport>

    @RequestMapping(value = "/${ObjectName}/export${ObjectName}", method = {RequestMethod.GET, RequestMethod.POST}, produces = "application/json;charset=utf-8")
    @ResponseBody
    public String export${ObjectName}(HttpServletResponse response, HttpServletRequest request) {
        JSONObject json = new JSONObject();
        Map<String, Object> params = getParameterMap(request);
        try {
            String paraStr = params.get("para").toString();
            Map<String, Object> paramap = JSONObject.parseObject(paraStr);
            params.putAll(paramap);
            String userCode = StringUtils.getStringFromMap(params, "usercode");
<#if exportFramework2>
            Map<String, Object> columnMap = excelExportService.getMultiGridExcelColumns(params);
            String fileName = I18nUtil.getMessage(userCode, "${fileName}");
            EasyExcel2Utils.writeExportExcel(response, fileName, (Object[]) columnMap.get("header"), (String[]) columnMap.get("field"), (String[]) columnMap.get("fieldtype"), fileName, ${objectName}Service, "query${ObjectName}List", params);
<#else>
            Map<String, Object> recordMap = ${objectName}Service.query${ObjectName}List(params);
            Map<String, Object> columnMap = excelExportService.getMultiGridExcelColumns(params);
            List<Map> rows = (List<Map>) recordMap.get("rows");
            String fileName = I18nUtil.getMessage(userCode, "${fileName}");
            EasyExcelUtils.writeExportExcel(response, DateUtils.formatDate(new Date()), (Object[]) columnMap.get("header"), (String[]) columnMap.get("field"), rows, fileName, params);
</#if>
        } catch (Exception e) {
            logger.error("${ObjectName}Controller::export${ObjectName} catch exception:", e);
            return wrapperException(params, e, json);
        }
        return null;
    }
</#if>
<#if generateImport>

    @RequestMapping(value = "/${ObjectName}/import${ObjectName}", method = {RequestMethod.GET, RequestMethod.POST}, produces = "application/json;charset=utf-8")
    @ResponseBody
    @SysLogger(OperationName = "import${ObjectName}", OperationDescription = "import${ObjectName}")
    public String import${ObjectName}(HttpServletResponse response, HttpServletRequest request) {
        Map<String, Object> params = getParameterMap(request);
        JSONObject json = new JSONObject();
        try
        {
            String clientIp = getClientIp(request);

            Map<String, Object> retMap = new HashMap<>();
            String userCode = StringUtils.objToString(params.get("usercode"));
//            //1.上传Excel文件到临时目录
            FileInputStream inputStream = uploadFileToImportReIs(request,"TempDir/${appName}/");
            if (inputStream == null) {
                retMap.put("error", I18nUtil.getMessage(userCode,"com.zhiyin.mes.app.web.uploadFile_isEmpty"));
                return wrapperSuccess(retMap, json);
            }
            retMap = ${objectName}Service.import${ObjectName}(inputStream,clientIp,params);
            return wrapperSuccess(retMap, json);
        } catch (Exception e) {
            logger.error("${ObjectName}Controller::import${ObjectName} catch exception:", e);
            return wrapperException(params, e, json);
        }
    }
</#if>
}
