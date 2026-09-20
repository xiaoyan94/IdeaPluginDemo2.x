package com.zhiyin.service.basic;

// import com.zhiyin.aop.DataSetType;
import com.zhiyin.dao.basic.ISyncErpIqcFailLogDao;
import com.zhiyin.service.BaseService;
import com.zhiyin.service.dict.DictTransformBuilder;
import com.zhiyin.utils.StringHelper;
import com.zhiyin.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
public class SyncErpIqcFailLogService extends BaseService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Resource
    private ISyncErpIqcFailLogDao syncErpIqcFailLogDao;

    public Map querySyncErpIqcFailLogList(Map<String, Object> params) throws Exception {
        // TODO: 字典 pcode 按实际字典确认（默认 PascalCase 字段名），字典缺条目时 dsp 列空白无害
        DictTransformBuilder dictTransformBuilder = new DictTransformBuilder()
                .setLanguage(StringUtils.getStringFromMap(params, "language"))
                .addDictTransform("Status", "status", "statusdsp");
        return queryDaoDataT(ISyncErpIqcFailLogDao.class, syncErpIqcFailLogDao, "querySyncErpIqcFailLogList", params, dictTransformBuilder);
    }
}
