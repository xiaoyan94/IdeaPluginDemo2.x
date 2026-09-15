# DataModelGenerator 回归基线

> 生成时间：2026-09-15 · 插件版本：**2.0.24**（commit 698c6c1）· 用途：后续每版生成器改动后 diff 对比，产物格式不得有计划外变化。

## 生成参数（复现口径）

| 项 | 值 |
|----|----|
| 项目 / 模块 | DengqiMes `E:\view\DengqiMes\webproj\com.zhiyin.mes.dengqi.project` / `com.zhiyin.mes.app.dengqi.order` |
| 输入方式 | 「从建表DDL解析字段」粘贴 `input/biz_base_factory.sql`（29 字段，等价于 DB 直读——`DatabaseMetadataUtil.getTableMetadata` 内部同走 `TableParser.parseCreateTable`） |
| GridName / 页面名称 | `BaseFactory` / `BaseFactory`（默认） |
| 页面功能类型 | 记录查询（数据维护被禁用，2.0.24 现状） |
| 文件类型复选框 | 7 项全勾（Moc / Layout / Html / Controller / Service / Dao / MyBatisMapper） |
| 字段表 | 保持启发式默认值不动（isQueryField/isDialogField/isEditHidden/easyuiClass 自动计算结果） |
| simpleModuleName 映射 | order → 无特殊分支 → folder = `Order`（MyPropertiesUtil.java:608-627） |

## 产物路径清单（相对模块 src/main，模块 content root 即 `...app.dengqi.order`）

| 产物 | 模块内相对路径 | 基线快照 |
|------|----------------|----------|
| Moc | `webapp/WEB-INF/etc/business/model/Order/BaseFactory.xml` | 同路径镜像于本目录 `src/main/` 下 |
| Layout | `webapp/WEB-INF/etc/business/layout/Order/BaseFactory.xml` | 同上 |
| Html | `webapp/WEB-INF/view/MesRoot/Order/BaseFactory.html` | 同上 |
| Controller | `java/com/zhiyin/controller/mes/order/BaseFactoryController.java` | 同上 |
| Service | `java/com/zhiyin/service/order/BaseFactoryService.java` | 同上 |
| Dao | `java/com/zhiyin/dao/order/IBaseFactoryDao.java`（前缀 I） | 同上 |
| Mapper | `java/com/zhiyin/maps/order/BaseFactoryMapper.xml` | 同上 |

## 每版回归方法

```bash
# runIde 沙箱按上表参数重新生成后，将模块产物覆盖到临时目录，与基线 diff：
diff -r docs/codegen-baseline/src/main <order模块>/src/main --exclude=input
# 预期：仅本次改动项允许差异，其余 7 件零 diff
```

## 采集时的已知现象（属 2.0.24 现状，基线如实锁定，不由基线修复）

1. **EDT 慢操作断言报错**：生成全程弹「Internal error: Slow operations are prohibited on EDT」——按钮点击（EDT）→ `generateBaseQueryTypeFile:312` → `MyPropertiesUtil.findModuleDataGridI18nPropertiesByValue:475-476` 的 `FilenameIndex/FileTypeIndex` 查询触发断言（:474 的 `SlowOperations.allowSlowOperations` 包装被注释）。断言只记录不中断，7 件产物正常生成。→ 已立 P1-7 修复。
2. **i18n 反查部分命中**：`备注` 命中既有键 `com.zhiyin.mes.app.order.ordergrid.note`（Title value 落键名）；`工厂编号` 等无同值键字段 Title 退化为裸中文 `value="工厂编号"`，eng 回退为字段名。→ P2-1/P2-2 的改进对象。
