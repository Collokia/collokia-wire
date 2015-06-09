package com.collokia.wire.client.vertx

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.UUID
import java.util.HashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

public class ResponseHandlingService(private val threadCount: Int = 2, private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(threadCount)) {

    private val responseMap = ConcurrentHashMap<UUID, ResponseHandler>()

    companion object {
        private data class ResponseHandler(val respondTimeoutFuture: ScheduledFuture<*>, val handle: ((Message) -> Unit))
    }

    init {
        executor.scheduleAtFixedRate({
            var keys: Set<UUID> = HashSet()
            synchronized(responseMap) {
                keys = HashSet(responseMap.keySet())
            }
            keys.forEach { key ->
                val handler = responseMap.get(key)
                if (handler != null) {
                    if (handler.respondTimeoutFuture.isDone() || handler.respondTimeoutFuture.isCancelled()) {
                        responseMap.remove(key)
                    }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)
    }

    fun putHandler(message: Message) {
        if (message.needsResponse) {
            putResponseHandler(message)
        }
    }


    private fun putResponseHandler(message: Message) {
        val respondTimeoutFuture = executor.schedule({
            responseMap.remove(message.id)
            message.onRespondFail(message)
        }, message.respondTimeout, TimeUnit.MILLISECONDS)
        responseMap.put(message.id, ResponseHandler(respondTimeoutFuture, { response: Message ->
            executor.submit {
                message.onResponse(response)
            }
        }))
    }

    fun handleResponse(response: Message): Boolean {
        val handler = responseMap.remove(response.envelope.respondId)
        if (handler != null) {
            handler.respondTimeoutFuture.cancel(true)
            handler.handle(response)
            return true
        }
        return false
    }
}
