package com.orchard.backend.pilot

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.pilotRoutes(service: PilotService) {
    get("/api/pilot/status") {
        call.respond(service.status())
    }
    get("/api/pilot/state-atlas") {
        call.respond(service.atlas())
    }
}
