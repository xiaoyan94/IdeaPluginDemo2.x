<#include "common.ftl">
package com.zhiyin.service.${packageName};

// import com.zhiyin.aop.DataSetType;
<#if generateImport>
import com.zhiyin.dao.DaoResultBuilder;
</#if>
import com.zhiyin.dao.${packageName}.I${ObjectName}Dao;
<#if generateImport>
import com.zhiyin.i18n.I18nUtil;
</#if>
import com.zhiyin.service.BaseService;
<#if generateImport>
import com.zhiyin.service.BizCommonService;
</#if>
import com.zhiyin.service.dict.DictTransformBuilder;
<#if generateImport>
import com.zhiyin.service.excel.DataImportParseResult;
import com.zhiyin.service.excel.EasyExcelUtils;
import com.zhiyin.service.excel.ExcelImportService;
import com.zhiyin.service.excel.mapper.Mapper;
import com.zhiyin.service.utils.CollectorsUtils;
</#if>
import com.zhiyin.utils.StringHelper;
<#-- P2-7：字典块（setLanguage 用 getStringFromMap）也依赖 StringUtils，无导入功能时同样需要该 import -->
<#if generateImport || (dictTransformFields?? && dictTransformFields?has_content)>
import com.zhiyin.utils.StringUtils;
</#if>
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
<#if generateImport>
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
</#if>
import java.util.Map;

@Service
public class ${ObjectName}Service extends BaseService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private I${ObjectName}Dao ${objectName}Dao;
<#if generateImport>

    @Resource
    private ExcelImportService excelImportService;

    @Resource
    private BizCommonService bizCommonService;
</#if>

    public Map query${ObjectName}List(Map<String, Object> params) throws Exception {
<#if dictTransformFields?? && dictTransformFields?has_content>
<#-- P2-7：有 state/status 字典字段时走 5 参 queryDaoDataT + DictTransformBuilder（framework BaseService 5 参重载），
     pcode 默认 PascalCase 字段名，按实际字典确认；language 由框架 getParameterMap 注入，字典缺条目 dsp 列空白无害 -->
        // TODO: 字典 pcode 按实际字典确认（默认 PascalCase 字段名），字典缺条目时 dsp 列空白无害
        DictTransformBuilder dictTransformBuilder = new DictTransformBuilder()
                .setLanguage(StringUtils.getStringFromMap(params, "language"))
<#list dictTransformFields as dictField>
                .addDictTransform("${dictField.pcode}", "${dictField.name}", "${dictField.name}dsp")<#if !dictField?has_next>;</#if>
</#list>
        return queryDaoDataT(I${ObjectName}Dao.class, ${objectName}Dao, "query${ObjectName}List", params, dictTransformBuilder);
<#else>
        return queryDaoDataT(I${ObjectName}Dao.class, ${objectName}Dao, "query${ObjectName}List", params);
</#if>
    }
<#if generateImport>

    public Map import${ObjectName}(FileInputStream fis, String clientIp, Map<String, Object> params) throws Exception {
        // TODO: 需执行 sql/${ObjectName}_import_draft.sql（建临时表+注册导入定义）并补 temp→biz upsert（dao import 调用当前被注释）后方可启用
        String userCode = StringUtils.getStringFromMap(params, "usercode");
        String factoryId = StringUtils.getStringFromMap(params, "factoryid");
        params.put("clientid", clientIp);
        Mapper importMapper = excelImportService.getImportMapper(${ObjectName}Service.class, factoryId, "${ObjectName}");
        DataImportParseResult dataResult = EasyExcelUtils.readBaseDataExcel(fis, importMapper, factoryId, clientIp);

        List<Map<String, Object>> filterList = new ArrayList<>();
        StringBuilder stringBuilder = new StringBuilder();
        String msgRowNo = "Excel" + I18nUtil.getMessage(userCode, "com.zhiyin.mes.app.web.product_rowno") + ":%s ";
        String msgPryKeyRepeat = I18nUtil.getMessage(userCode, "com.zhiyin.mes.app.web.product.repeat") + ":%s ";

        List<String> errorMessages = new ArrayList<>(dataResult.getErrorMessages());

        for (Map<String, Object> row : dataResult.getRows()) {
            stringBuilder.setLength(0);

            String rowNo = row.get("rowno").toString();
            stringBuilder.append(String.format(msgRowNo, rowNo));
            boolean isEmpty = false;

            // 字段校验

            if (isEmpty) {
                filterList.add(row);
                errorMessages.add(stringBuilder.toString());
            } else {
                row.put("maintainer", params.get("maintainer"));
                row.put("maintaintime", params.get("maintaintime"));
            }
        }

        dataResult.getRows().removeAll(filterList);
        filterList = CollectorsUtils.filterList(dataResult.getRows(), "code");
        for (Map<String, Object> map : filterList) {
            stringBuilder.delete(0, stringBuilder.length());
            stringBuilder.append(String.format(msgRowNo, map.get("rowno")));
            stringBuilder.append(String.format(msgPryKeyRepeat, map.get("code")));
            errorMessages.add(stringBuilder.toString());
        }
        dataResult.getRows().removeAll(filterList);
        excelImportService.insertTempDataByExcel(dataResult);

        // 插入业务表
        // ${objectName}Dao.import${ObjectName}(params);
        bizCommonService.resetSeq("${tableName}");
        Map retMap;
        if (!errorMessages.isEmpty()) {
            DaoResultBuilder resultBuilder = new DaoResultBuilder();
            resultBuilder.setError(String.join("\n", errorMessages));
            retMap = resultBuilder.retValue();
        } else {
            return wrapAffectedResult(1);
        }
        return retMap;
    }
</#if>
}
