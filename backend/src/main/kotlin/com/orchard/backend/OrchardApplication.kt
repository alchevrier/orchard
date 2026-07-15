package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.config.OrchardPaths
import com.orchard.backend.vector.OllamaClient
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    OrchardPaths.initialize()
    val workspace = WorkspaceStore()
    val modelProvider = OllamaClient()
    val architect = ArchitectService(workspace, modelProvider)
    val workspaceServer = embeddedServer(Netty, host = "127.0.0.1", port = 8085) {
        workspaceApi(workspace)
    }
    val architectServer = embeddedServer(Netty, host = "127.0.0.1", port = 8086) {
        architectApi(architect)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        workspaceServer.stop()
        architectServer.stop()
        modelProvider.close()
    })
    workspaceServer.start(wait = false)
    architectServer.start(wait = true)
}

fun Application.workspaceApi(workspace: WorkspaceStore) {
    configureJson()
    routing {
        get("/api/workspace") {
            call.respond(workspace.snapshot(MESSAGE_READY))
        }
    }
}

fun Application.architectApi(architect: ArchitectService) {
    configureJson()
    routing {
        post("/api/architect/chat") {
            val request = runCatching { call.receive<ArchitectChatRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = architect.submit(request)
            call.respond(HttpStatusCode.fromValue(result.statusCode), result.snapshot)
        }
    }
}

private fun Application.configureJson() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}