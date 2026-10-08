package com.feedvibe.app.data.sync

import android.util.Log
import com.feedvibe.app.data.prefs.SettingsRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * El cliente de Firestore compartido (sincronización y control de acceso).
 *
 * Las versiones 1.0.92 y anteriores dejaron en el móvil una cola con miles de escrituras
 * (una por episodio) que agotan el límite diario gratuito de Firebase cada vez que se
 * reenvían. Antes de usar el cliente por primera vez se descartan, una sola vez.
 */
object FirestoreHolder {
    private val mutex = Mutex()
    @Volatile private var instance: FirebaseFirestore? = null

    suspend fun get(settings: SettingsRepository): FirebaseFirestore =
        instance ?: mutex.withLock {
            instance ?: run {
                if (!settings.firestoreQueueDropped()) {
                    runCatching {
                        val old = FirebaseFirestore.getInstance()
                        old.terminate().await()
                        old.clearPersistence().await()
                    }.onFailure { Log.w("FirestoreHolder", "clearPersistence", it) }
                    settings.setFirestoreQueueDropped()
                }
                FirebaseFirestore.getInstance().also { instance = it }
            }
        }
}
