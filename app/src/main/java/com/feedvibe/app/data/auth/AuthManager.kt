package com.feedvibe.app.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class UserInfo(
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String?,
)

/** Inicio de sesión con Google (Credential Manager) + Firebase Auth. */
class AuthManager(private val appContext: Context) {

    /** false si la app se compiló sin google-services.json. */
    val isAvailable: Boolean = FirebaseApp.getApps(appContext).isNotEmpty()

    private val _user = MutableStateFlow<UserInfo?>(null)
    val user: StateFlow<UserInfo?> = _user.asStateFlow()

    init {
        if (isAvailable) {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                _user.value = auth.currentUser?.let {
                    UserInfo(
                        uid = it.uid,
                        name = it.displayName.orEmpty(),
                        email = it.email.orEmpty(),
                        photoUrl = it.photoUrl?.toString()?.replace("s96-c", "s400-c"),
                    )
                }
            }
        }
    }

    val currentUid: String? get() = if (isAvailable) FirebaseAuth.getInstance().currentUser?.uid else null

    private fun webClientId(): String? {
        val id = appContext.resources.getIdentifier("default_web_client_id", "string", appContext.packageName)
        return if (id != 0) appContext.getString(id) else null
    }

    /** @param activityContext debe ser una Activity (Credential Manager muestra su propia UI). */
    suspend fun signIn(activityContext: Context): Result<UserInfo> = runCatching {
        check(isAvailable) { "Firebase no está configurado en esta compilación (falta google-services.json)." }
        val clientId = webClientId() ?: error("Falta default_web_client_id en google-services.json")
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request)
        } catch (e: GetCredentialCancellationException) {
            throw IllegalStateException("Inicio de sesión cancelado")
        }
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Credencial no compatible"
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authResult = FirebaseAuth.getInstance()
            .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .await()
        val u = authResult.user ?: error("No se pudo iniciar sesión")
        UserInfo(u.uid, u.displayName.orEmpty(), u.email.orEmpty(), u.photoUrl?.toString())
    }

    suspend fun signOut() {
        if (!isAvailable) return
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
