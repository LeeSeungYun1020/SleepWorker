import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}
compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "aiflow"
            packageVersion = "1.0.0"
            modules("java.instrument", "java.management", "jdk.unsupported")
            macOS { bundleID = "dev.aiflow.desktop" }
        }
    }
}
