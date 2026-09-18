-- 菜单注册 SQL 草稿（由插件生成）：人工确认父菜单 pid / seq 后执行；幂等可重复执行
-- 口径：DengqiMes dengqimesv3 实证，一条查询页菜单 = sys_menu 1 行 + sys_menu_fun N 行 + sys_res_i18n 1 行 + sys_res_i18n_type 3 行
-- ID 取号：MySQL 用户变量查当前最大 ID（i18n 与 type 共享全局号段，i18n 占 @i18n_id、type 三行占 +1/+2/+3）；
-- fun 行 PID 经 sys_menu 按 code 反查、type 行 PID 经 sys_res_i18n 按 KEY 反查，判重不依赖新号，重跑不插孤儿行
SET @menu_id = (SELECT IFNULL(MAX(id), 0) + 1 FROM sys_menu);
SET @i18n_id = (SELECT GREATEST(IFNULL((SELECT MAX(ID) FROM sys_res_i18n), 0), IFNULL((SELECT MAX(ID) FROM sys_res_i18n_type), 0)) + 1);
SET @fun_id  = (SELECT IFNULL(MAX(ID), 0) + 1 FROM sys_menu_fun);

INSERT INTO sys_menu (id, pid, name, code, useflag, functionid, functionurl, icon, seq, padimg, i18nid, i18ntype)
SELECT @menu_id, /* TODO: 父菜单 pid */ NULL, '工厂', 'BaseFactory', 1, NULL, './Order/BaseFactory', 'icon-blank', /* TODO: seq */ 0, NULL, @i18n_id, 'zh_CN'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE code = 'BaseFactory');

INSERT INTO sys_res_i18n (ID, `KEY`, TYPE, maintainer, MAINTAINTIME)
SELECT @i18n_id, 'com.zhiyin.mes.menu.base_factory', 'menu', 'zhiyin', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_res_i18n WHERE `KEY` = 'com.zhiyin.mes.menu.base_factory');

INSERT INTO sys_res_i18n_type (ID, PID, TYPE, value)
SELECT t.ID, i.ID, t.TYPE, t.value FROM sys_res_i18n i
JOIN (
  SELECT @i18n_id + 1 AS ID, 'zh_CN' AS TYPE, '工厂' AS value UNION ALL
  SELECT @i18n_id + 2, 'zh_TW', '工廠' UNION ALL
  SELECT @i18n_id + 3, 'en_US', 'Factory'
) t
WHERE i.`KEY` = 'com.zhiyin.mes.menu.base_factory'
  AND NOT EXISTS (SELECT 1 FROM sys_res_i18n_type x WHERE x.PID = i.ID AND x.TYPE = t.TYPE);

-- 每个按钮一行（PID 经 sys_menu 按 code 反查真实菜单 id，NOT EXISTS 按 PID+CODE 判重；公共按钮 i18n id 复用全局既有值，不生成按钮 i18n 行）
INSERT INTO sys_menu_fun (ID, PID, FID, FNAME, ICON, CODE, GROUPNAME, SEQ, USEFLAG, I18NID, I18NTYPE, pagei18nid, pageName)
SELECT @fun_id + 0, m.id, @fun_id + 0, '刷新', 'icon-reload', 'Refresh', 'ToolBar', 0, 1, 75, NULL, 661, NULL
FROM sys_menu m WHERE m.code = 'BaseFactory'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'Refresh');
INSERT INTO sys_menu_fun (ID, PID, FID, FNAME, ICON, CODE, GROUPNAME, SEQ, USEFLAG, I18NID, I18NTYPE, pagei18nid, pageName)
SELECT @fun_id + 1, m.id, @fun_id + 1, '导出(模板)', 'icon-export', 'downloadTemplate', 'ToolBar', 1, 1, 84, NULL, 661, NULL
FROM sys_menu m WHERE m.code = 'BaseFactory'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'downloadTemplate');
INSERT INTO sys_menu_fun (ID, PID, FID, FNAME, ICON, CODE, GROUPNAME, SEQ, USEFLAG, I18NID, I18NTYPE, pagei18nid, pageName)
SELECT @fun_id + 2, m.id, @fun_id + 2, '导入', 'icon-import', 'Import', 'ToolBar', 2, 1, 454, NULL, 661, NULL
FROM sys_menu m WHERE m.code = 'BaseFactory'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'Import');
INSERT INTO sys_menu_fun (ID, PID, FID, FNAME, ICON, CODE, GROUPNAME, SEQ, USEFLAG, I18NID, I18NTYPE, pagei18nid, pageName)
SELECT @fun_id + 3, m.id, @fun_id + 3, '导出', 'icon-export', 'Export', 'ToolBar', 3, 1, 80, NULL, 661, NULL
FROM sys_menu m WHERE m.code = 'BaseFactory'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = 'Export');
