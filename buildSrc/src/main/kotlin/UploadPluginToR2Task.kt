import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * Upload plugin artifact and updatePlugins.xml to CF R2 (Amazon S3 API compatible)
 */
abstract class UploadPluginToR2Task : DefaultTask() {

    @get:Input
    abstract val region: Property<String>

    @get:Input
    abstract val bucketName: Property<String>

    @get:Input
    abstract val accessKey: Property<String>

    @get:Input
    abstract val secretKey: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val pluginName: Property<String>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val updateXmlFile: RegularFileProperty

    @TaskAction
    fun upload() {
        val s3Client = S3Client.builder()
            .region(Region.of(region.get()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey.get(), secretKey.get())
                )
            )
            .endpointOverride(URI.create(endpoint.get()))
            .build()

        // 上传插件 ZIP
        val pluginZip = pluginFile.get().asFile
        val key = "${pluginName.get()}-${pluginVersion.get()}.zip"

        println("Uploading plugin to R2: ${pluginZip.absolutePath} -> $key")
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName.get())
                .key(key)
                .build(),
            RequestBody.fromFile(pluginZip)
        )

        // 上传 updatePlugins.xml
        val xmlFile = updateXmlFile.get().asFile
        println("Uploading update XML: ${xmlFile.absolutePath} -> ${xmlFile.name}")
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName.get())
                .key(xmlFile.name)
                .build(),
            RequestBody.fromFile(xmlFile)
        )

        println("✅ Upload completed successfully.")
    }
}


//import org.gradle.api.DefaultTask
//import org.gradle.api.tasks.Input
//import org.gradle.api.tasks.TaskAction
//import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
//import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
//import software.amazon.awssdk.core.sync.RequestBody
//import software.amazon.awssdk.regions.Region
//import software.amazon.awssdk.services.s3.S3Client
//import software.amazon.awssdk.services.s3.model.PutObjectRequest
//import java.io.File
//import java.net.URI
//
///**
// * Upload plugin artifact and updatePlugins.xml to CF R2 (Amazon S3 API compatible)
// */
//abstract class UploadPluginToR2Task : DefaultTask() {
//
//    @Input
//    val region: String = project.findProperty("r2.s3.region")?.toString() ?: "auto"
//
//    @Input
//    val bucketName: String = project.findProperty("r2.bucketName")?.toString() ?: ""
//
//    @Input
//    val accessKey: String = project.findProperty("r2.s3.accessKeyId")?.toString() ?: ""
//
//    @Input
//    val secretKey: String = project.findProperty("r2.s3.secretAccessKey")?.toString() ?: ""
//
//    @Input
//    val endpoint: String = project.findProperty("r2.s3.endpoint")?.toString() ?: ""
//
//    @Input
//    val updatePluginXmlFileName: String = project.findProperty("updatePluginXmlFileName")?.toString() ?: "updatePlugins.xml"
//
//    @TaskAction
//    fun upload() {
//        val key = "${project.rootProject.name}-${project.version}.zip"
//        val file = File("${project.rootDir}/build/distributions/$key")
//
//        println("Uploading file to R2: ${file.absolutePath}")
//
//        val awsCredentials = AwsBasicCredentials.create(accessKey, secretKey)
//
//        val s3Client = S3Client.builder()
//            .region(Region.of(region))
//            .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
//            .endpointOverride(URI.create(endpoint))
//            .build()
//
//        // 上传 zip 包
//        val putObjectRequest = PutObjectRequest.builder()
//            .bucket(bucketName)
//            .key(key)
//            .build()
//
//        s3Client.putObject(putObjectRequest, RequestBody.fromFile(file))
//        println("✅ Plugin zip uploaded to bucket=$bucketName, key=$key")
//
//        // 上传 updatePlugins.xml
//        val updateXmlFile = File("${project.rootDir}/$updatePluginXmlFileName")
//        val putUpdateXmlFileRequest = PutObjectRequest.builder()
//            .bucket(bucketName)
//            .key(updateXmlFile.name)
//            .build()
//
//        s3Client.putObject(putUpdateXmlFileRequest, RequestBody.fromFile(updateXmlFile))
//        println("✅ updatePlugins.xml uploaded to bucket=$bucketName, key=${updateXmlFile.name}")
//    }
//}

