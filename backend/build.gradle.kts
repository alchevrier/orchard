plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    jvm {
        mainRun {
            mainClass.set("com.orchard.backend.OrchardApplicationKt")
        }
    }
    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
            dependencies {
                implementation("io.ktor:ktor-server-core:3.1.3")
                implementation("io.ktor:ktor-server-netty:3.1.3")
                implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-cio:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
            }
        }
        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-server-test-host:3.1.3")
                implementation("io.ktor:ktor-client-mock:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}
