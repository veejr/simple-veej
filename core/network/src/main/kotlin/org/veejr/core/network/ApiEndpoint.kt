package org.veejr.core.network

import java.net.URI

@JvmInline
value class ApiEndpoint private constructor(val uri: URI) {
    companion object {
        fun parse(value: String, allowHttp: Boolean = false): ApiEndpoint {
            val normalized = value.trim().trimEnd('/')
            val uri = URI(normalized)
            val permittedScheme = uri.scheme == "https" || (allowHttp && uri.scheme == "http")
            require(permittedScheme) { "A veejr instance must use HTTPS" }
            require(!uri.host.isNullOrBlank()) { "A veejr instance must include a host" }
            require(uri.userInfo == null) { "Credentials are not allowed in instance URLs" }
            require(uri.query == null && uri.fragment == null) {
                "Instance URLs cannot include a query or fragment"
            }
            return ApiEndpoint(uri.resolve("/api/v1/"))
        }
    }
}
