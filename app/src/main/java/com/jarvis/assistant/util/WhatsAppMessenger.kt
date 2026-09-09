package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.jarvis.assistant.service.JarvisAccessibilityService

/**
 * Automates sending WhatsApp messages and placing WhatsApp voice/video calls hands-free,
 * including dual app (cloned WhatsApp) selection and call confirmation.
 */
object WhatsAppMessenger {

    sealed class SendResult {
        data class Success(val contactName: String, val phoneNumber: String, val appName: String) : SendResult()
        data class RequiresConfirmation(val contactName: String, val phoneNumber: String, val message: String) : SendResult()
        data class MultipleAppsFound(val contactName: String, val count: Int) : SendResult()
        data class ContactNotFound(val name: String) : SendResult()
        data class MultipleContactsFound(val name: String, val matches: List<String>) : SendResult()
        object MissingPermission : SendResult()
        data class Error(val reason: String) : SendResult()
    }

    sealed class CallResult {
        data class Success(val contactName: String, val callType: String, val appName: String) : CallResult()
        data class RequiresConfirmation(val contactName: String, val callType: String) : CallResult()
        data class MultipleAppsFound(val contactName: String, val count: Int) : CallResult()
        data class ContactNotFound(val name: String) : CallResult()
        data class MultipleContactsFound(val name: String, val matches: List<String>) : CallResult()
        object MissingPermission : CallResult()
        data class Error(val reason: String) : CallResult()
    }

    /**
     * Attempts to send a WhatsApp message to [recipientName].
     */
    fun sendMessage(
        context: Context,
        recipientName: String,
        message: String,
        appNumber: Int? = null,
        confirmed: Boolean = false
    ): SendResult {
        // 1. Resolve contact
        val matches = ContactCaller.findMatches(context, recipientName)
        if (matches.isEmpty()) {
            return SendResult.ContactNotFound(recipientName)
        }
        if (matches.size > 1) {
            val exactMatch = matches.firstOrNull { it.name.equals(recipientName.trim(), ignoreCase = true) }
            if (exactMatch == null) {
                return SendResult.MultipleContactsFound(recipientName, matches.map { "${it.name} (${it.number})" })
            }
        }

        val targetContact = matches.first()
        val rawNumber = targetContact.number.filter { it.isDigit() || it == '+' }
        var phoneDigits = rawNumber.filter { it.isDigit() }

        if (phoneDigits.isEmpty()) {
            return SendResult.Error("No valid phone number digits for ${targetContact.name}.")
        }

        if (phoneDigits.length == 10) {
            phoneDigits = "91$phoneDigits"
        } else if (phoneDigits.startsWith("0") && phoneDigits.length == 11) {
            phoneDigits = "91" + phoneDigits.substring(1)
        }

        // 2. Check WhatsApp dual app availability
        val appMatches = AppLauncher.findMatchingApps(context, "WhatsApp")
        val isDual = appMatches.size >= 2 || (appMatches.isNotEmpty() && AppLauncher.isDualAppEnabled(context, appMatches[0].packageName))

        if (isDual && appNumber == null) {
            return SendResult.MultipleAppsFound(targetContact.name, if (appMatches.size >= 2) appMatches.size else 2)
        }

        // 3. Contact Identity Confirmation Step
        if (!confirmed) {
            return SendResult.RequiresConfirmation(targetContact.name, phoneDigits, message)
        }

        // 4. Construct WhatsApp deep-link URL
        val encodedMessage = Uri.encode(message)
        val deepLinkUrl = "https://api.whatsapp.com/send?phone=$phoneDigits&text=$encodedMessage"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val targetIndex = (appNumber ?: 1) - 1
        val targetApp = if (appMatches.isNotEmpty() && targetIndex in appMatches.indices) {
            appMatches[targetIndex]
        } else {
            appMatches.firstOrNull()
        }

        val chosenPackage = targetApp?.packageName ?: if (appNumber == 2) "com.whatsapp.w4b" else "com.whatsapp"
        intent.setPackage(chosenPackage)

        if ((appNumber ?: 1) == 2 || (targetApp != null && targetApp.isDualOrWorkProfile)) {
            intent.putExtra("multi_user_id", 999)
            intent.putExtra("userId", 999)
            intent.putExtra("clone_id", 1)
            intent.putExtra("vivo_clone_id", 1)
            intent.putExtra("is_clone_app", true)
            intent.putExtra("is_clone", true)
            intent.putExtra("clone_app", true)
        }

        return try {
            context.startActivity(intent)
            JarvisAccessibilityService.instance?.scheduleWhatsAppAutoSend()
            val targetAppName = targetApp?.label ?: if (chosenPackage.contains("w4b")) "WhatsApp Business" else "WhatsApp"
            SendResult.Success(targetContact.name, phoneDigits, targetAppName)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(fallbackIntent)
                JarvisAccessibilityService.instance?.scheduleWhatsAppAutoSend()
                SendResult.Success(targetContact.name, phoneDigits, "WhatsApp")
            } catch (e2: Exception) {
                SendResult.Error("Could not launch WhatsApp. Is WhatsApp installed on your device?")
            }
        }
    }

    /**
     * Attempts to place a WhatsApp Voice or Video Call to [recipientName].
     * If [confirmed] is false, requires user confirmation.
     */
    fun placeCall(
        context: Context,
        recipientName: String,
        callType: String = "voice", // "voice" or "video"
        appNumber: Int? = null,
        confirmed: Boolean = false
    ): CallResult {
        // 1. Resolve contact
        val matches = ContactCaller.findMatches(context, recipientName)
        if (matches.isEmpty()) {
            return CallResult.ContactNotFound(recipientName)
        }
        if (matches.size > 1) {
            val exactMatch = matches.firstOrNull { it.name.equals(recipientName.trim(), ignoreCase = true) }
            if (exactMatch == null) {
                return CallResult.MultipleContactsFound(recipientName, matches.map { "${it.name} (${it.number})" })
            }
        }

        val targetContact = matches.first()
        val rawNumber = targetContact.number.filter { it.isDigit() || it == '+' }
        var phoneDigits = rawNumber.filter { it.isDigit() }

        if (phoneDigits.isEmpty()) {
            return CallResult.Error("No valid phone number digits for ${targetContact.name}.")
        }

        if (phoneDigits.length == 10) {
            phoneDigits = "91$phoneDigits"
        } else if (phoneDigits.startsWith("0") && phoneDigits.length == 11) {
            phoneDigits = "91" + phoneDigits.substring(1)
        }

        // 2. Check WhatsApp dual app availability
        val appMatches = AppLauncher.findMatchingApps(context, "WhatsApp")
        val isDual = appMatches.size >= 2 || (appMatches.isNotEmpty() && AppLauncher.isDualAppEnabled(context, appMatches[0].packageName))

        if (isDual && appNumber == null) {
            return CallResult.MultipleAppsFound(targetContact.name, if (appMatches.size >= 2) appMatches.size else 2)
        }

        // 3. Confirmation Step
        val normalizedType = if (callType.lowercase().contains("video")) "video" else "voice"
        if (!confirmed) {
            return CallResult.RequiresConfirmation(targetContact.name, normalizedType)
        }

        val targetIndex = (appNumber ?: 1) - 1
        val targetApp = if (appMatches.isNotEmpty() && targetIndex in appMatches.indices) {
            appMatches[targetIndex]
        } else {
            appMatches.firstOrNull()
        }

        val chosenPackage = targetApp?.packageName ?: if (appNumber == 2) "com.whatsapp.w4b" else "com.whatsapp"
        val targetAppName = targetApp?.label ?: if (chosenPackage.contains("w4b")) "WhatsApp Business" else "WhatsApp"

        // 4. Try Direct Contacts Data MIME Intent
        val mimeType = if (normalizedType == "video") {
            "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        } else {
            "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        }

        val dataId = findWhatsAppContactDataId(context, phoneDigits, mimeType)
        if (dataId != null) {
            val directIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.contacts/data/$dataId"), mimeType)
                setPackage(chosenPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(directIntent)
                return CallResult.Success(targetContact.name, normalizedType, targetAppName)
            } catch (e: Exception) {
                // Fallback below
            }
        }

        // 5. Fallback: Chat Deep-Link + Accessibility Auto-Tap Voice/Video Call Icon
        val deepLinkUrl = "https://api.whatsapp.com/send?phone=$phoneDigits"
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
            setPackage(chosenPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return try {
            context.startActivity(fallbackIntent)
            JarvisAccessibilityService.instance?.scheduleWhatsAppCallAutoTap(normalizedType)
            CallResult.Success(targetContact.name, normalizedType, targetAppName)
        } catch (e: Exception) {
            CallResult.Error("Could not connect WhatsApp $normalizedType call to ${targetContact.name}.")
        }
    }

    private fun findWhatsAppContactDataId(context: Context, phoneDigits: String, mimeType: String): Long? {
        val resolver = context.contentResolver
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1)
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(mimeType)

        return try {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val data1Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val last10 = if (phoneDigits.length >= 10) phoneDigits.takeLast(10) else phoneDigits
                while (cursor.moveToNext()) {
                    val data1 = cursor.getString(data1Idx) ?: continue
                    val cleanData1 = data1.filter { it.isDigit() }
                    if (cleanData1.contains(last10)) {
                        return cursor.getLong(idIdx)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
