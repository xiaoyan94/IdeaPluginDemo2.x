-- 菜单注册 SQL 草稿（由插件生成）：父菜单 pid/seq 已按父菜单名自动反查，执行前人工复核；幂等可重复执行
-- 口径：DengqiMes dengqimesv3 实证，一条查询页菜单 = sys_menu 1 行 + sys_menu_fun N 行 + sys_res_i18n 1 行 + sys_res_i18n_type 3 行
-- ID 取号：MySQL 用户变量查当前最大 ID（i18n 与 type 共享全局号段，i18n 占 @i18n_id、type 三行占 +1/+2/+3）；
-- fun 行 PID 经 sys_menu 按 code 反查、type 行 PID 经 sys_res_i18n 按 KEY 反查，判重不依赖新号，重跑不插孤儿行
SET @menu_id = (SELECT IFNULL(MAX(id), 0) + 1 FROM sys_menu);
SET @i18n_id = (SELECT GREATEST(IFNULL((SELECT MAX(ID) FROM sys_res_i18n), 0), IFNULL((SELECT MAX(ID) FROM sys_res_i18n_type), 0)) + 1);
SET @fun_id  = (SELECT IFNULL(MAX(ID), 0) + 1 FROM sys_menu_fun);
<#-- P2-7 修复：pid/seq 按父菜单名原子替换（用户变量反查，不再留 TODO 占位）——@menu_pid 反查父菜单 id，
     @seq 取该父菜单下子菜单最大 id+1（无子菜单兜底 1）；父菜单名在 DataModelGenerator 输入（必填校验） -->
SET @menu_pid = (SELECT id FROM sys_menu WHERE name = '${parentMenuZh?replace("'", "''")}'); -- 挂在哪个父菜单下必填
SET @seq = (SELECT IFNULL(MAX(id)+1, 1) FROM sys_menu WHERE pid = @menu_pid);

INSERT INTO sys_menu (id, pid, name, code, useflag, functionid, functionurl, icon, seq, padimg, i18nid, i18ntype)
SELECT @menu_id, @menu_pid, '${menuNameZh?replace("'", "''")}', '${dataGrids[0].ObjectName}', 1, NULL, './${menuFolder}/${dataGrids[0].ObjectName}', 'icon-blank', @seq, NULL, @i18n_id, 'zh_CN'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE code = '${dataGrids[0].ObjectName}');

INSERT INTO sys_res_i18n (ID, `KEY`, TYPE, maintainer, MAINTAINTIME)
SELECT @i18n_id, 'com.zhiyin.mes.menu.${menuSnakeKey}', 'menu', 'zhiyin', NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_res_i18n WHERE `KEY` = 'com.zhiyin.mes.menu.${menuSnakeKey}');

INSERT INTO sys_res_i18n_type (ID, PID, TYPE, value)
SELECT t.ID, i.ID, t.TYPE, t.value FROM sys_res_i18n i
JOIN (
  SELECT @i18n_id + 1 AS ID, 'zh_CN' AS TYPE, '${menuNameZh?replace("'", "''")}' AS value UNION ALL
  SELECT @i18n_id + 2, 'zh_TW', <#if menuNameTw?has_content>'${menuNameTw?replace("'", "''")}'<#else>'/* TODO: zh_TW */'</#if> UNION ALL
  SELECT @i18n_id + 3, 'en_US', <#if menuNameEn?has_content>'${menuNameEn?replace("'", "''")}'<#else>'/* TODO: en_US */'</#if>
) t
WHERE i.`KEY` = 'com.zhiyin.mes.menu.${menuSnakeKey}'
  AND NOT EXISTS (SELECT 1 FROM sys_res_i18n_type x WHERE x.PID = i.ID AND x.TYPE = t.TYPE);

-- 每个按钮一行（PID 经 sys_menu 按 code 反查真实菜单 id，NOT EXISTS 按 PID+CODE 判重；公共按钮 i18n id 复用全局既有值，不生成按钮 i18n 行）
<#list menuButtons as btn>
INSERT INTO sys_menu_fun (ID, PID, FID, FNAME, ICON, CODE, GROUPNAME, SEQ, USEFLAG, I18NID, I18NTYPE, pagei18nid, pageName)
SELECT @fun_id + ${btn?index}, m.id, @fun_id + ${btn?index}, '${btn.fname}', '${btn.icon}', '${btn.code}', 'ToolBar', ${btn.seq}, 1, ${btn.i18nid}, NULL, 661, NULL
FROM sys_menu m WHERE m.code = '${dataGrids[0].ObjectName}'
  AND NOT EXISTS (SELECT 1 FROM sys_menu_fun f WHERE f.PID = m.id AND f.CODE = '${btn.code}');
</#list>
