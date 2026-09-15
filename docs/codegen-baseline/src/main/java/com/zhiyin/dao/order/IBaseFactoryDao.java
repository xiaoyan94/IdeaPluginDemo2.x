package com.zhiyin.dao.order;

import java.util.List;
import java.util.Map;

public interface IBaseFactoryDao {

    List<Map> queryBaseFactoryList(Map para);

    int importBaseFactory(Map<String, Object> params);

    int deleteBaseFactory(Map<String, Object> params);
}