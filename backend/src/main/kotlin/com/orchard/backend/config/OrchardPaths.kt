package com.orchard.backend.config

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object OrchardPaths {
    val BASE_DIR: Path = Paths.get(System.getProperty("user.home"), ".orchard")
    val RAG_SHARED_DIR: Path = BASE_DIR.resolve("rag-shared")
    val PROJECTS_DIR: Path = BASE_DIR.resolve("projects")
    val WORKSPACE_DIR: Path = PROJECTS_DIR.resolve("workspace")
    val DB_DIR: Path = BASE_DIR.resolve("db")
    val POLICY_PACKS_DIR: Path = BASE_DIR.resolve("policy-packs")
    val TOOLCHAIN_POLICY_PACKS_DIR: Path = POLICY_PACKS_DIR.resolve("toolchains")

    fun initialize() {
        if (!BASE_DIR.exists()) BASE_DIR.createDirectories()
        if (!RAG_SHARED_DIR.exists()) RAG_SHARED_DIR.createDirectories()
        if (!PROJECTS_DIR.exists()) PROJECTS_DIR.createDirectories()
        if (!WORKSPACE_DIR.exists()) WORKSPACE_DIR.createDirectories()
        if (!DB_DIR.exists()) DB_DIR.createDirectories()
        if (!TOOLCHAIN_POLICY_PACKS_DIR.exists()) TOOLCHAIN_POLICY_PACKS_DIR.createDirectories()
        
        println("Orchard paths initialized at: $BASE_DIR")
    }
}
