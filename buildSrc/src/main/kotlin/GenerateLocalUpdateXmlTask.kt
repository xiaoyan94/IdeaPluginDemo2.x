import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Generate local updatePlugins.xml
 */
abstract class GenerateLocalUpdateXmlTask : DefaultTask() {
    @get:Input abstract val pluginName: Property<String>
    @get:Input abstract val pluginVersion: Property<String>
    @get:Input abstract val pluginGroup: Property<String>
    @get:Input abstract val sinceBuild: Property<String>
    @get:Input abstract val untilBuild: Property<String>
    @get:Input abstract val downloadUrl: Property<String>
    @get:Input abstract val pluginDescription: Property<String>
    @get:Input abstract val changeNotes: Property<String>
    @get:Input abstract val extraPluginXml: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val pluginId = "${pluginGroup.get()}.${pluginName.get()}"

        val xmlContent = """
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
    <plugin id="$pluginId"
            url="${downloadUrl.get()}"
            version="${pluginVersion.get()}">
        <idea-version since-build="${sinceBuild.get()}" until-build="${untilBuild.get()}"/>
        <name>${pluginName.get()}</name>
        <description><![CDATA[
            ${pluginDescription.get()}
        ]]>
        </description>
        <change-notes><![CDATA[
            ${changeNotes.get()}
        ]]>
        </change-notes>
        <date>${LocalDate.now()}</date>
        <vendor>
            <name>${pluginGroup.get()}</name>
        </vendor>
        <updateTime>${LocalDateTime.now()}</updateTime>
    </plugin>
${extraPluginXml.get()}
</plugins>
        """.trimIndent()

        outputFile.get().asFile.writeText(xmlContent, Charsets.UTF_8)
        println("✅ Local updatePlugins.xml generated at: ${outputFile.get().asFile.absolutePath}")
    }
}
