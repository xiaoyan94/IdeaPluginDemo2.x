<?xml version="1.0" encoding="UTF-8"?>
<Moc name="${mocName}" table="${tableName}">
    <FieldDef>
        <#list fields as field>
        <Field name="${field.name}" type="${field.type}"<#if (field.type == "string" || field.type == "decimal") && field.length?? && field.length?string != ""> length="${field.length}"</#if><#if field.enumRef?? && field.enumRef != ""> enum="${field.enumRef}"</#if>/>
        </#list>
    </FieldDef>
</Moc>
