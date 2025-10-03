plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()

    google()
}

dependencies {
    // AWS S3 SDK
    implementation("software.amazon.awssdk:s3:2.20.64")
    implementation("software.amazon.awssdk:auth:2.20.64")
    implementation("software.amazon.awssdk:aws-core:2.20.64")
}