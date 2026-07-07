plugins {
    kotlin("jvm") version "1.9.23" apply false
    id("org.jetbrains.compose") version "1.6.10" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
