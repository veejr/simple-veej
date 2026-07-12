package org.veejr.core.model

object Protocol {
    const val API_VERSION = 1
    const val PAYLOAD_VERSION = 1
}

enum class MessageKind(val wireValue: String) {
    MESSAGE("message"),
    LOCATION("location"),
    NOTE("note"),
}
