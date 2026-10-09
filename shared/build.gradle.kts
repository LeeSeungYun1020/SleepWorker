plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin {
    jvm()
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            api(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.kaml)
            api(libs.okio)
            api(libs.datetime)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.okio.fake)
        }
    }
}
tasks.withType<Test>().configureEach {
    systemProperty("sleepworker.fixtures", rootProject.file("scripts/fixtures/phase0").absolutePath)
    doFirst { systemProperty("sleepworker.testClasspath", classpath.asPath) }
}
