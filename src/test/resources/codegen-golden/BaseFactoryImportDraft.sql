-- Excel 导入定义 SQL 草稿（由插件生成，插件不执行任何 SQL）：人工确认临时表名/DataModelName（可改中文）后执行，执行后方接通 Excel 导入链路
-- 链路口径（DengqiMes dengqimesv3 实证）：excelImportService.getImportMapper 按 DataModelCode 查 utils_base_data_import_define
-- （UNIQUE KEY DataModelCode），再按 TemplateMould/TemplateName 读 classpath WEB-INF/etc/business/<TemplateMould>/<TemplateName> 的 Imp mapper XML；
-- 临时表列 = Imp mapper 列定义（name 小写）+ clientid/factoryid/rowno 框架列（insertTempDataByExcel 直接 insert，SQL 自带 delete+insert+commit）
CREATE TABLE IF NOT EXISTS `temp_imp_base_factory` (
  `clientid` varchar(255) DEFAULT NULL,
  `factoryid` int(11) NOT NULL COMMENT '生产中心ID',
  `rowno` varchar(255) DEFAULT NULL COMMENT '行号',
  `code` varchar(255) DEFAULT NULL COMMENT '工厂编号',
  `name` varchar(255) DEFAULT NULL COMMENT '工厂名称',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `note` varchar(1024) DEFAULT NULL COMMENT '备注',
  `defaultlang` varchar(255) DEFAULT NULL,
  `3rdflag` varchar(255) DEFAULT NULL COMMENT '第三方推送工厂',
  `price` varchar(255) DEFAULT NULL COMMENT '单价'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 注册导入定义（id 无自增动态取号 + UNIQUE KEY DataModelCode 判重，幂等可重复执行；
-- tablename 与 Imp<ObjectName>Mapper.xml 的 table name 同源渲染，两件产物出自同一 tempTableName 变量）
INSERT INTO utils_base_data_import_define (id, DataModelCode, DataModelName, TemplateMould, TemplateName, tablename)
SELECT (SELECT IFNULL(MAX(id), 0) + 1 FROM utils_base_data_import_define),
       'BaseFactory', '工厂导入', 'Order', 'ImpBaseFactoryMapper.xml', 'temp_imp_base_factory'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM utils_base_data_import_define WHERE DataModelCode = 'BaseFactory');
