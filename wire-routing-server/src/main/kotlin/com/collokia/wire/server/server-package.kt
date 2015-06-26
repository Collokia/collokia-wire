package com.collokia.wire.server

import io.vertx.core.Vertx

public fun main(args: Array<String>) {
    Vertx.vertx().deployVerticle(MessageServer())
}