package com.collokia.wire.server

import com.typesafe.config.Config

class MessageServerConfig(
        val s3UploadBucket: String,
        val healthCheckKey: String,
        val host: String,
        val port: Int,
        val pingInterval: Long,
        val timeout: Long
) {
    companion object {
        fun fromConfig(rootConfig: Config): MessageServerConfig {
            val config = rootConfig.getConfig("msg.server")!!
            return MessageServerConfig(
                    s3UploadBucket = config.getString("s3UploadBucket")!!,
                    healthCheckKey = config.getString("healthCheckKey")!!,
                    host = config.getString("host")!!,
                    port = config.getInt("port"),
                    pingInterval = config.getLong("pingInterval"),
                    timeout = config.getLong("timeout")
            )
        }
    }
}