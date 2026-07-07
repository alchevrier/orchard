plugins {
    kotlin("multiplatform") version "2.1.20-RC2"
    id("dev.autumn.plugin") version "1.0.2"
}

kotlin {
    jvm()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("dev.autumn:autumn-core:1.6.1")
            }
        }
    }
}
