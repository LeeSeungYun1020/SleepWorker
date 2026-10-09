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
    implementation(compose.material3)
}
compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "SleepWorker"
            packageVersion = "1.0.0"
            modules("java.instrument", "java.management", "jdk.unsupported")
            macOS { bundleID = "dev.local.aiflow"; iconFile.set(project.file("src/main/resources/sleepworker.icns")) }
        }
    }
}

tasks.register<JavaExec>("acceptance") {
    group = "verification"
    description = "Explicit live read-only preflight; pass --args='preflight workflow.yaml settings.json'"
    mainClass.set("AcceptanceMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.processResources {
    from(rootProject.file("sleepworker.md")) { rename { "plan.md" } }
}
