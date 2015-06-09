package com.collokia.wire.client.websock

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.UUID
import java.util.HashSet
import java.util.concurrent.TimeUnit

public class MessageHandlingService(private val threadCount: Int = 2, private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(threadCount)) {

    private val responseMap = java.util.concurrent.ConcurrentHashMap<UUID, ResponseHandler>()

    companion object {
        private data class ResponseHandler(val sendTimeoutFuture: java.util.concurrent.ScheduledFuture<*>, val respondTimeoutFuture: java.util.concurrent.ScheduledFuture<*>?, val handle: ((Message) -> Unit)?)
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
                    if ((handler.sendTimeoutFuture.isDone() || handler.sendTimeoutFuture.isCancelled())) {
                        if (handler.respondTimeoutFuture == null) {
                            responseMap.remove(key)
                        } else if (handler.respondTimeoutFuture.isDone() || handler.respondTimeoutFuture.isCancelled()) {
                            responseMap.remove(key)
                        }
                    }
                }
            }
        }, 10, 10, TimeUnit.SECONDS)
    }

    fun putHandler(message: Message) {
        if (message.needsResponse) {
            putResponseHandler(message)
        } else {
            putSendHandler(message)
        }
    }


    private fun putSendHandler(message: Message) {
        val sendTimeoutFuture = executor.schedule({
            responseMap.remove(message.id)
            message.onSendFail(message)
        }, message.sendTimeout, TimeUnit.MILLISECONDS)
        responseMap.put(message.id, ResponseHandler(sendTimeoutFuture, null, null))
    }

    private fun putResponseHandler(message: Message) {
        val sendTimeoutFuture = executor.schedule({
            val handler = responseMap.remove(message.id)
            if (handler != null) {
                handler.respondTimeoutFuture?.cancel(true)
            }
            message.onSendFail(message)
        }, message.sendTimeout, TimeUnit.MILLISECONDS)
        val respondTimeoutFuture = executor.schedule({
            val handler = responseMap.remove(message.id)
            if (handler != null) {
                handler.sendTimeoutFuture.cancel(true)
            }
            message.onRespondFail(message)
        }, message.respondTimeout, TimeUnit.MILLISECONDS)
        responseMap.put(message.id, ResponseHandler(sendTimeoutFuture, respondTimeoutFuture, { response: Message ->
            executor.submit {
                message.onResponse(response)
            }
        }))
    }

    fun markSent(id: java.util.UUID) {
        responseMap.get(id)?.sendTimeoutFuture?.cancel(true)
    }

    fun handleResponse(response: Message): Boolean {
        val handler = responseMap.remove(response.envelope.respondId)
        if (handler != null) {
            handler.sendTimeoutFuture.cancel(true)
            handler.respondTimeoutFuture?.cancel(true)
            val handle = handler.handle
            if (handle != null) {
                handle(response)
            }
            return true
        }
        return false
    }
}
