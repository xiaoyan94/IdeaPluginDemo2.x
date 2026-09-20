<#include "common.ftl">
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper     PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"     "classpath://mybatis-3-mapper.dtd">

<mapper namespace="com.zhiyin.dao.${packageName}.I${ObjectName}Dao">

    <select id="query${ObjectName}List" parameterType="Map" resultType="Map">
${dataGrids[0].sql}
        <where>
        <#list dataGrids[0].queryFields as field>
<#-- P2-7：仅查询面板实际勾选字段出条件（isQueryField 经 CodeGenerateService 归一为 String，与 Layout 查询区判断同口径）；
     未勾选字段不出死条件（真实规范 DataSyncMapper 仅为查询面板字段出 <if>） -->
        <#if field.isQueryField?? && field.isQueryField == "true">
        <#if field.name?lowerCase?endsWith("id") || field.name?lowerCase?endsWith("status") || field.name?lowerCase?endsWith("state")>
            <if test="${field.name?lowerCase} != null and ${field.name?lowerCase} != ''">
                and a.${field.name} = <#noParse>#{</#noParse>${field.name?lowerCase}}
            </if>
        <#elseIf field.name?lowerCase?endsWith("time") || field.name?lowerCase?endsWith("date")>
            <if test="${field.name?lowerCase}from != null and ${field.name?lowerCase}from != ''">
                and a.${field.name} >= <#noParse>concat(#{</#noParse>${field.name?lowerCase}from}, ' 00:00:00')
            </if>
            <if test="${field.name?lowerCase}to != null and ${field.name?lowerCase}to != ''">
                and a.${field.name} <![CDATA[ <= ]]> <#noParse>concat(#{</#noParse>${field.name?lowerCase}to}, ' 23:59:59')
            </if>
        <#else>
            <if test="${field.name?lowerCase} != null and ${field.name?lowerCase} != ''">
                and a.${field.name} like concat('%',<#noParse>#{</#noParse>${field.name?lowerCase}}, '%')
            </if>
        </#if>
        </#if>
        </#list>
        </where>
<#-- P2-7 修复：order by 移到 </where> 之后（真实规范 DataSyncMapper 排序在全部条件之后）；
     orderByIdDesc 由 CodeGenerateService 按「DB/DDL 自动拼装路径 + 表有 id 列」组合置位，
     缺省/旧调用方（false）零漂移；小写与缩进照现有产物风格 -->
        <#if dataGrids[0].orderByIdDesc!false>
        order by a.id desc
        </#if>
    </select>

    <update id="delete${ObjectName}" parameterType="Map">
        delete a
        from ${dataGrids[0].tableName} a
        <#noParse>
        where a.factoryid = #{factoryid}
        <choose>
            <when test="id != null and id != ''">
                and a.id = #{id}
            </when>
            <when test="idlist != null and idlist.size() > 0">
                and a.id in
                <foreach collection="idlist" item="item" open="(" close=")" separator=",">
                    #{item}
                </foreach>
            </when>
            <otherwise>
                and 1 = 0  <!-- 避免删除全表的保护条件 -->
            </otherwise>
        </choose>
        </#noParse>
    </update>
</mapper>