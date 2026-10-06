package com.feedvibe.app.data.repo

import com.feedvibe.app.data.sources.SourceType
import java.security.MessageDigest

/** Identificadores deterministas: iguales en todos los dispositivos. */
object Ids {
    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /**
     * RSS y Podcast son la misma fuente (un feed): se agrupan para que un mismo feed añadido
     * a mano o importado de un OPML no se duplique.
     */
    fun subscription(type: SourceType, key: String): String {
        val family = if (type == SourceType.RSS || type == SourceType.PODCAST) "FEED" else type.name
        return sha1("$family|$key")
    }
    fun episode(subscriptionId: String, guid: String) = sha1("$subscriptionId|$guid")
}
