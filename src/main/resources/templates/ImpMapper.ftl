<?xml version="1.0" encoding="UTF-8"?>
<mapper version="v1">
    <table name="${tempTableName}">
        <#list importColumns as column>
        <column name="${column.name}" col="${column.col}"<#if column.description??> description="${column.description}"</#if><#if column.required??> required="true"</#if><#if column.i18nKey??> i18n="${column.i18nKey}"</#if>/>
        </#list>
    </table>
</mapper>
