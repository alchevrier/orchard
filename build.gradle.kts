plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
