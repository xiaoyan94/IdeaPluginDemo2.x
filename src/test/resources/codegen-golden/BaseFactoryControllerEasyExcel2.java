package com.zhiyin.controller.mes.order;

import com.alibaba.fastjson.JSONObject;
import com.zhiyin.aspect.SysLogger;
import com.zhiyin.controller.BaseController;
import com.zhiyin.i18n.I18nUtil;
import com.zhiyin.service.excel.EasyExcel2Utils;
import com.zhiyin.service.excel.ExcelExportService;
import com.zhiyin.service.order.BaseFactoryService;
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
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * BaseFactory Controller
 */
@Controller
@RequestMapping(value = "/Order")
public class BaseFactoryController extends BaseController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private BaseFactoryService baseFactoryService;

    @Resource
    private ExcelExportService excelExportService;

    @Resource
    private EasyExcel2Utils EasyExcel2Utils;

    @RequestMapping(value = "/BaseFactory", method = {RequestMethod.POST, RequestMethod.GET})
    public ModelAndView createBaseFactoryView(HttpServletRequest request) throws Exception {
        Map parameterMap = getParameterMap(request);
        return createMultiGridPatternView(parameterMap, this.getClass(), "MesRoot/Order/BaseFactory", request.getServletPath());
    }

    @RequestMapping(value = "/BaseFactory/queryBaseFactoryList", method = {RequestMethod.POST, RequestMethod.GET}, produces = "application/json; charset=utf-8")
    @ResponseBody
    public String queryBaseFactoryList(HttpServletRequest request) {
        JSONObject json = new JSONObject();
        Map<String, Object> params = getParameterMap(request);
        try {
            Map retMap = baseFactoryService.queryBaseFactoryList(params);
            return wrapperSuccess(retMap, json);
        } catch (Exception e) {
            logger.error("BaseFactoryController::queryBaseFactoryList catch exception:", e);
            return wrapperException(params, e, json);
        }
    }

    @RequestMapping(value = "/BaseFactory/exportBaseFactory", method = {RequestMethod.GET, RequestMethod.POST}, produces = "application/json;charset=utf-8")
    @ResponseBody
    public String exportBaseFactory(HttpServletResponse response, HttpServletRequest request) {
        JSONObject json = new JSONObject();
        Map<String, Object> params = getParameterMap(request);
        try {
            String paraStr = params.get("para").toString();
            Map<String, Object> paramap = JSONObject.parseObject(paraStr);
            params.putAll(paramap);
            String userCode = StringUtils.getStringFromMap(params, "usercode");
            Map<String, Object> columnMap = excelExportService.getMultiGridExcelColumns(params);
            String fileName = I18nUtil.getMessage(userCode, "BaseFactory");
            EasyExcel2Utils.writeExportExcel(response, fileName, (Object[]) columnMap.get("header"), (String[]) columnMap.get("field"), (String[]) columnMap.get("fieldtype"), fileName, baseFactoryService, "queryBaseFactoryList", params);
        } catch (Exception e) {
            logger.error("BaseFactoryController::exportBaseFactory catch exception:", e);
            return wrapperException(params, e, json);
        }
        return null;
    }

    @RequestMapping(value = "/BaseFactory/importBaseFactory", method = {RequestMethod.GET, RequestMethod.POST}, produces = "application/json;charset=utf-8")
    @ResponseBody
    @SysLogger(OperationName = "importBaseFactory", OperationDescription = "importBaseFactory")
    public String importBaseFactory(HttpServletResponse response, HttpServletRequest request) {
        Map<String, Object> params = getParameterMap(request);
        JSONObject json = new JSONObject();
        try
        {
            String clientIp = getClientIp(request);

            Map<String, Object> retMap = new HashMap<>();
            String userCode = StringUtils.objToString(params.get("usercode"));
//            //1.上传Excel文件到临时目录
            FileInputStream inputStream = uploadFileToImportReIs(request,"TempDir/Order/");
            if (inputStream == null) {
                retMap.put("error", I18nUtil.getMessage(userCode,"com.zhiyin.mes.app.web.uploadFile_isEmpty"));
                return wrapperSuccess(retMap, json);
            }
            retMap = baseFactoryService.importBaseFactory(inputStream,clientIp,params);
            return wrapperSuccess(retMap, json);
        } catch (Exception e) {
            logger.error("BaseFactoryController::importBaseFactory catch exception:", e);
            return wrapperException(params, e, json);
        }
    }
}
