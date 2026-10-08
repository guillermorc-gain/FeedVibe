package com.feedvibe.app.data.access

import android.os.Build
import android.util.Log
import com.feedvibe.app.data.auth.AuthManager
import com.feedvibe.app.data.auth.UserInfo
import com.feedvibe.app.data.prefs.SettingsRepository
import com.feedvibe.app.data.sync.FirestoreHolder
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Cuenta que administra la app: autoriza a otros usuarios y decide si se publica para todos. */
const val ADMIN_EMAIL = "guillermo.rc82@gmail.com"

fun isAdmin(user: UserInfo?) = user?.email.equals(ADMIN_EMAIL, ignoreCase = true)

/** access/config: si la app está publicada para todos y los correos autorizados. */
data class AccessConfig(val public: Boolean, val allowed: Set<String>)

/** Usuario que ha iniciado sesión alguna vez (members/{uid}). */
data class Member(
    val uid: String,
    val email: String,
    val name: String,
    val version: String,
    val device: String,
    val lastSeen: Long,
)

enum class Access { CHECKING, ALLOWED, NEEDS_SIGN_IN, PENDING }

/**
 * Control de acceso. Mientras la app no esté publicada, solo la usan el administrador y las
 * cuentas de Google que él autorice. Si no hay conexión se usa la última decisión guardada.
 */
class AccessManager(
    private val auth: AuthManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val version: String,
) {
    private val _config = MutableStateFlow<AccessConfig?>(null)
    val config: StateFlow<AccessConfig?> = _config.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _access = MutableStateFlow(if (auth.isAvailable) Access.CHECKING else Access.ALLOWED)
    val access: StateFlow<Access> = _access.asStateFlow()

    private var configListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var registeredUid: String? = null

    fun start() {
        // Sin Firebase configurado (compilaciones de prueba) no hay control de acceso.
        if (!auth.isAvailable) return
        scope.launch {
            val fs = FirestoreHolder.get(settings)
            configListener = fs.collection("access").document("config").addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "config", err); return@addSnapshotListener }
                if (snap == null) return@addSnapshotListener
                @Suppress("UNCHECKED_CAST")
                val allowed = (snap.get("allowed") as? List<String>).orEmpty().map { it.lowercase() }.toSet()
                _config.value = AccessConfig(snap.getBoolean("public") == true, allowed)
            }
            combine(auth.user, _config) { u, c -> u to c }.collect { (u, c) -> update(u, c) }
        }
    }

    private suspend fun update(user: UserInfo?, config: AccessConfig?) {
        val email = user?.email?.lowercase()
        val decision = when {
            isAdmin(user) -> Access.ALLOWED
            config == null -> {
                // Aún sin respuesta de Firebase (o sin conexión): lo último que se supo.
                val cached = settings.accessCache()
                when {
                    cached == PUBLIC -> Access.ALLOWED
                    email != null && cached == email -> Access.ALLOWED
                    else -> Access.CHECKING
                }
            }
            config.public -> Access.ALLOWED
            email == null -> Access.NEEDS_SIGN_IN
            email in config.allowed -> Access.ALLOWED
            else -> Access.PENDING
        }
        if (config != null) {
            settings.setAccessCache(
                when {
                    config.public -> PUBLIC
                    decision == Access.ALLOWED -> email
                    else -> null
                }
            )
        }
        _access.value = decision
        if (user != null) register(user)
        watchMembers(isAdmin(user))
    }

    /** Se apunta (o actualiza) en la lista de usuarios: así el administrador ve quién usa la app. */
    private suspend fun register(user: UserInfo) {
        if (registeredUid == user.uid) return
        registeredUid = user.uid
        runCatching {
            FirestoreHolder.get(settings).collection("members").document(user.uid).set(
                mapOf(
                    "email" to user.email.lowercase(),
                    "name" to user.name,
                    "version" to version,
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    "lastSeen" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
        }.onFailure { Log.w(TAG, "register", it) }
    }

    private suspend fun watchMembers(admin: Boolean) {
        if (!admin) {
            membersListener?.remove()
            membersListener = null
            _members.value = emptyList()
            return
        }
        if (membersListener != null) return
        membersListener = FirestoreHolder.get(settings).collection("members")
            .orderBy("lastSeen", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "members", err); return@addSnapshotListener }
                _members.value = snap?.documents.orEmpty().map { d ->
                    Member(
                        uid = d.id,
                        email = d.getString("email").orEmpty(),
                        name = d.getString("name").orEmpty(),
                        version = d.getString("version").orEmpty(),
                        device = d.getString("device").orEmpty(),
                        lastSeen = d.getTimestamp("lastSeen")?.toDate()?.time ?: 0,
                    )
                }
            }
    }

    // ------------------ Acciones del administrador ------------------

    suspend fun setAllowed(email: String, allowed: Boolean) {
        val e = email.trim().lowercase()
        if (e.isBlank()) return
        FirestoreHolder.get(settings).collection("access").document("config").set(
            mapOf("allowed" to if (allowed) FieldValue.arrayUnion(e) else FieldValue.arrayRemove(e)),
            SetOptions.merge(),
        ).await()
    }

    suspend fun setPublic(public: Boolean) {
        FirestoreHolder.get(settings).collection("access").document("config")
            .set(mapOf("public" to public), SetOptions.merge()).await()
    }

    companion object {
        private const val TAG = "Access"
        private const val PUBLIC = "*"
    }
}
