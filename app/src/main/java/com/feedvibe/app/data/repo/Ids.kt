package com.feedvibe.app.data.repo

import com.feedvibe.app.data.sources.SourceType
import java.security.MessageDigest

/** Identificadores deterministas: iguales en todos los dispositivos. */
object Ids {
    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun subscription(type: SourceType, key: String) = sha1("${type.name}|$key")
    fun episode(subscriptionId: String, guid: String) = sha1("$subscriptionId|$guid")
}
