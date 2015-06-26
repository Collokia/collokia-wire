package com.collokia.wire.server

import io.vertx.core.AbstractVerticle

class MessageServer() : AbstractVerticle() {

    override fun start() {
        val server = vertx.createHttpServer()
        server.websocketHandler({ websocket ->
            websocket.handler { buffer ->
                val message = buffer.toString("UTF-8")
                println(message)
                if (message == "ping") {
                    websocket.writeFinalTextFrame("pong")
                } else {
                    websocket.writeFinalTextFrame(message)
                }
            }
        }).listen(8080)
    }
}