package com.jarvis.assistant.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Cloud Firestore Manager for JARVIS AI user profiles.
 * Synchronizes user authentication data, profile details (name, phone, email, photo)
 * with the Firestore 'users' collection across devices and re-installs.
 */
object UserFirestoreHelper {

    private const val TAG = "UserFirestoreHelper"
    private const val USERS_COLLECTION = "users"
    private const val PREFS_NAME = "jarvis_prefs"

    data class JarvisUserProfile(
        val uid: String = "",
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val photoUrl: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val lastLoginAt: Long = System.currentTimeMillis()
    )

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    /**
     * Fetches existing profile from Firestore 'users' collection.
     * Searches primarily by UID, with fallback to email lookup.
     */
    fun fetchProfile(uid: String, email: String, onResult: (JarvisUserProfile?) -> Unit) {
        if (uid.isBlank() && email.isBlank()) {
            onResult(null)
            return
        }

        if (uid.isNotBlank()) {
            firestore.collection(USERS_COLLECTION)
                .document(uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot != null && snapshot.exists()) {
                        val profile = parseUserProfile(snapshot.data, uid)
                        Log.d(TAG, "User profile found by UID ($uid): name='${profile.name}', phone='${profile.phone}'")
                        onResult(profile)
                    } else if (email.isNotBlank()) {
                        // Fallback: search by email
                        searchByEmail(email, onResult)
                    } else {
                        onResult(null)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to get user by UID: ${e.message}. Checking email fallback...", e)
                    if (email.isNotBlank()) {
                        searchByEmail(email, onResult)
                    } else {
                        onResult(null)
                    }
                }
        } else {
            searchByEmail(email, onResult)
        }
    }

    private fun searchByEmail(email: String, onResult: (JarvisUserProfile?) -> Unit) {
        val cleanEmail = email.trim().lowercase()
        firestore.collection(USERS_COLLECTION)
            .whereEqualTo("email", cleanEmail)
            .limit(1)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val doc = querySnapshot.documents[0]
                    val profile = parseUserProfile(doc.data, doc.id)
                    Log.d(TAG, "User profile found by email ($cleanEmail): name='${profile.name}', phone='${profile.phone}'")
                    onResult(profile)
                } else {
                    Log.d(TAG, "No existing user profile found in Firestore for email: $cleanEmail")
                    onResult(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to search user by email: ${e.message}", e)
                onResult(null)
            }
    }

    /**
     * Saves or updates a user profile in the Firestore 'users' collection.
     */
    fun saveProfile(profile: JarvisUserProfile, onComplete: ((Boolean) -> Unit)? = null) {
        if (profile.uid.isBlank()) {
            Log.e(TAG, "Cannot save profile: UID is blank")
            onComplete?.invoke(false)
            return
        }

        val now = System.currentTimeMillis()
        val userMap = hashMapOf<String, Any>(
            "uid" to profile.uid,
            "name" to profile.name.trim(),
            "email" to profile.email.trim().lowercase(),
            "phone" to profile.phone.trim(),
            "photoUrl" to profile.photoUrl,
            "updatedAt" to now,
            "lastLoginAt" to now
        )

        if (profile.createdAt > 0) {
            userMap["createdAt"] = profile.createdAt
        } else {
            userMap["createdAt"] = now
        }

        firestore.collection(USERS_COLLECTION)
            .document(profile.uid)
            .set(userMap, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "User profile saved successfully to Firestore: ${profile.uid}")
                onComplete?.invoke(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save user profile to Firestore: ${e.message}", e)
                onComplete?.invoke(false)
            }
    }

    /**
     * Updates only the display name in Firestore.
     */
    fun updateName(uid: String, newName: String) {
        if (uid.isBlank() || newName.isBlank()) return
        val updates = mapOf(
            "name" to newName.trim(),
            "updatedAt" to System.currentTimeMillis()
        )
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .update(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Updated name in Firestore for UID $uid: $newName")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update name in Firestore: ${e.message}")
            }
    }

    /**
     * Updates the last login timestamp in Firestore.
     */
    fun updateLastLogin(uid: String) {
        if (uid.isBlank()) return
        firestore.collection(USERS_COLLECTION)
            .document(uid)
            .update("lastLoginAt", System.currentTimeMillis())
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update last login: ${e.message}")
            }
    }

    /**
     * Saves user profile directly into local EncryptedSharedPreferences.
     */
    fun syncToLocalPrefs(context: Context, profile: JarvisUserProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPhone = profile.phone.isNotBlank()
        val hasName = profile.name.isNotBlank() && profile.name != "Jarvis User"

        prefs.edit()
            .putBoolean("is_authenticated", true)
            .putString("user_uid", profile.uid)
            .putString("user_name", profile.name)
            .putString("user_email", profile.email)
            .putString("user_phone", profile.phone)
            .putString("user_photo", profile.photoUrl)
            .putBoolean("is_profile_complete", hasPhone && hasName)
            .putLong("last_cloud_sync_timestamp", System.currentTimeMillis())
            .apply()

        Log.d(TAG, "Synchronized profile into local SharedPreferences (is_profile_complete = ${hasPhone && hasName})")
    }

    private fun parseUserProfile(data: Map<String, Any>?, fallbackUid: String): JarvisUserProfile {
        if (data == null) return JarvisUserProfile(uid = fallbackUid)

        val uid = (data["uid"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackUid
        val name = (data["name"] as? String) ?: ""
        val email = (data["email"] as? String) ?: ""
        val phone = (data["phone"] as? String) ?: ""
        val photoUrl = (data["photoUrl"] as? String) ?: ""
        val createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val lastLoginAt = (data["lastLoginAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

        return JarvisUserProfile(
            uid = uid,
            name = name,
            email = email,
            phone = phone,
            photoUrl = photoUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastLoginAt = lastLoginAt
        )
    }
}
