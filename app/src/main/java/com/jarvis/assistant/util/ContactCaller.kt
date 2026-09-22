package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * Lets JARVIS place phone calls by spoken contact name or raw phone number.
 */
object ContactCaller {

    data class Contact(val name: String, val number: String)

    sealed class CallResult {
        data class Success(val contact: Contact) : CallResult()
        data class NoMatch(val query: String) : CallResult()
        data class MultipleMatches(val query: String, val matches: List<Contact>) : CallResult()
        object MissingPermission : CallResult()
        object CallFailed : CallResult()
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun cleanQueryString(raw: String): String {
        var q = raw.trim().lowercase()
        val prefixes = listOf(
            "message ", "send message to ", "send whatsapp to ", "whatsapp ", "open contact ", "open ",
            "call ", "phone ", "dial ", "contact ", "to ", "my ", "please ", "karo ", "lagao ", "bhejo "
        )
        val suffixes = listOf(
            " ko message bhejo", " ko whatsapp karo", " ko call karo", " ko call lagao", " ko phone karo",
            " ko message karo", " ko message", " ko whatsapp", " ko call", " ko phone", " ke paas",
            " ko", " ji", " bhai", " bhaiya", " sir", " beta", " madam", " didi", " uncle", " aunty"
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (q.startsWith(prefix)) {
                    q = q.removePrefix(prefix).trim()
                    changed = true
                }
            }
            for (suffix in suffixes) {
                if (q.endsWith(suffix)) {
                    q = q.removeSuffix(suffix).trim()
                    changed = true
                }
            }
        }
        return q
    }

    /**
     * Looks up contacts stored on the device, matching case-insensitively and flexibly.
     */
    fun findMatches(context: Context, spokenName: String): List<Contact> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()

        val cleanQuery = cleanQueryString(spokenName)
        if (cleanQuery.isEmpty()) return emptyList()

        val rawCleanQuery = cleanQuery.replace(Regex("[^a-z0-9]"), "")

        val allContacts = mutableListOf<Contact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            // Fetch all phone contacts to do accurate in-memory case-insensitive & prefix-free matching
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    if (name.isNotBlank() && number.isNotBlank()) {
                        allContacts.add(Contact(name.trim(), number.trim()))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Contact query failed", e)
        }

        if (allContacts.isEmpty()) return emptyList()

        val distinctContacts = allContacts.distinctBy { it.number.filter(Char::isDigit) }

        // 1. Exact match (case-insensitive)
        val exactMatches = distinctContacts.filter { it.name.lowercase() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        // 2. Exact alphanumeric match (ignoring spaces & punctuation)
        val alphaMatches = distinctContacts.filter {
            it.name.lowercase().replace(Regex("[^a-z0-9]"), "") == rawCleanQuery
        }
        if (alphaMatches.isNotEmpty()) return alphaMatches

        // 3. Contact full name starts with cleanQuery (query must be at least 2 chars)
        if (cleanQuery.length >= 2) {
            val startsWithMatches = distinctContacts.filter {
                it.name.lowercase().startsWith(cleanQuery)
            }
            if (startsWithMatches.isNotEmpty()) return startsWithMatches
        }

        // 4. Any word in contact name exactly equals cleanQuery
        val exactWordMatches = distinctContacts.filter { c ->
            val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            words.any { word -> word == cleanQuery }
        }
        if (exactWordMatches.isNotEmpty()) return exactWordMatches

        // 5. Any word in contact name starts with cleanQuery (query must be at least 3 chars)
        if (cleanQuery.length >= 3) {
            val wordStartsWithMatches = distinctContacts.filter { c ->
                val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                words.any { word -> word.startsWith(cleanQuery) }
            }
            if (wordStartsWithMatches.isNotEmpty()) return wordStartsWithMatches
        }

        // 6. Query contains exact word matching a contact name word (words must be at least 3 chars)
        val queryWords = cleanQuery.split(Regex("\\s+")).filter { it.length >= 3 }
        if (queryWords.isNotEmpty()) {
            val queryWordMatches = distinctContacts.filter { c ->
                val contactWords = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
                contactWords.any { cWord -> queryWords.contains(cWord) }
            }
            if (queryWordMatches.isNotEmpty()) return queryWordMatches
        }

        return emptyList()
    }

    /**
     * Places a call to whichever saved contact best matches [spokenName].
     */
    fun callContact(context: Context, spokenName: String): CallResult {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS) ||
            !hasPermission(context, Manifest.permission.CALL_PHONE)
        ) {
            return CallResult.MissingPermission
        }

        val rawInput = spokenName.trim()
        if (rawInput.isEmpty()) return CallResult.NoMatch(spokenName)

        val digitsOnly = rawInput.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && rawInput.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return placeCall(context, Contact(rawInput, digitsOnly))
        }

        val matches = findMatches(context, spokenName)
        if (matches.isEmpty()) {
            return CallResult.NoMatch(spokenName)
        }

        val distinctNames = matches.map { it.name.lowercase() }.distinct()
        if (distinctNames.size > 1) {
            val cleanQ = cleanQueryString(spokenName)
            val singleExact = matches.firstOrNull { it.name.lowercase() == cleanQ }
            if (singleExact != null) {
                return placeCall(context, singleExact)
            }
            return CallResult.MultipleMatches(spokenName, matches)
        }

        return placeCall(context, matches.first())
    }

    private fun placeCall(context: Context, target: Contact): CallResult {
        return try {
            val sanitizedNumber = target.number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitizedNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val preferredHandle = SimManager.getPreferredSim(context)
                val preferredIndex = SimManager.getPreferredSimIndex(context)
                val preferredSubId = SimManager.getPreferredSimSubId(context)

                if (preferredIndex >= 0) {
                    if (preferredHandle != null) {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, preferredHandle)
                        putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", preferredHandle)
                    }

                    // Slot index extras (0 for SIM 1, 1 for SIM 2)
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("com.android.phone.extra.slot", preferredIndex)
                    putExtra("simSlot", preferredIndex)
                    putExtra("slot", preferredIndex)
                    putExtra("sim_slot", preferredIndex)
                    putExtra("phone_type", preferredIndex)
                    putExtra("phone", preferredIndex)

                    // Subscription ID extras (subId)
                    val effectiveSubId = if (preferredSubId > 0) preferredSubId else (preferredIndex + 1)
                    putExtra("subscription", effectiveSubId)
                    putExtra("sub_id", effectiveSubId)
                    putExtra("Subscription", effectiveSubId)
                    putExtra("com.android.phone.dial_sub_id", effectiveSubId)
                }
            }
            context.startActivity(intent)
            CallResult.Success(target)
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Failed to place call to ${target.name}", e)
            CallResult.CallFailed
        }
    }
}

