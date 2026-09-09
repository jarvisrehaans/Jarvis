package com.jarvis.assistant.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication

/**
 * Lets JARVIS place calls on a specific SIM (SIM 1 or SIM 2) on dual-SIM phones without
 * Android popping up its own "Call with SIM 1 / SIM 2" chooser every time.
 */
object SimManager {

    private const val PREF_SIM_ID = "preferred_sim_id"
    private const val PREF_SIM_COMPONENT = "preferred_sim_component"
    private const val PREF_SIM_INDEX = "preferred_sim_index"
    private const val PREF_SIM_SUB_ID = "preferred_sim_sub_id"

    data class SimOption(
        val handle: PhoneAccountHandle?,
        val label: String,
        val slotIndex: Int,
        val subId: Int
    )

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Every SIM currently capable of placing a call, mapped to exact slot index and subId. */
    fun getCallCapableSims(context: Context): List<SimOption> {
        if (!hasPermission(context)) return emptyList()

        val results = mutableListOf<SimOption>()

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val telecomAccounts = try {
            telecomManager?.callCapablePhoneAccounts ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val activeSubs: List<SubscriptionInfo> = try {
            subManager?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (activeSubs.isNotEmpty()) {
            activeSubs.sortedBy { it.simSlotIndex }.forEachIndexed { idx, subInfo ->
                val slot = if (subInfo.simSlotIndex >= 0) subInfo.simSlotIndex else idx
                val subId = subInfo.subscriptionId
                val carrier = subInfo.displayName?.toString()?.trim()
                    ?.ifBlank { subInfo.carrierName?.toString()?.trim() ?: "" } ?: ""

                val displayLabel = when {
                    carrier.isEmpty() || carrier.equals("Phone", ignoreCase = true) || carrier.equals("SIM", ignoreCase = true) -> "SIM ${slot + 1}"
                    carrier.contains("SIM", ignoreCase = true) -> carrier
                    else -> "SIM ${slot + 1} ($carrier)"
                }

                val handle = telecomAccounts.firstOrNull { h ->
                    h.id == subId.toString() || h.id == subInfo.iccId
                } ?: telecomAccounts.getOrNull(slot) ?: PhoneAccountHandle(
                    ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService"),
                    subId.toString()
                )

                results.add(SimOption(handle, displayLabel, slot, subId))
            }
        } else if (telecomAccounts.isNotEmpty()) {
            telecomAccounts.forEachIndexed { idx, handle ->
                val account = try { telecomManager?.getPhoneAccount(handle) } catch (e: Exception) { null }
                val rawLabel = account?.label?.toString()?.trim() ?: ""
                val displayLabel = when {
                    rawLabel.isEmpty() || rawLabel.equals("Phone", ignoreCase = true) -> "SIM ${idx + 1}"
                    else -> "SIM ${idx + 1} ($rawLabel)"
                }
                val subId = handle.id.toIntOrNull() ?: (idx + 1)
                results.add(SimOption(handle, displayLabel, idx, subId))
            }
        }

        return results
    }

    /** True if the phone actually has more than one usable SIM. */
    fun isDualSim(context: Context): Boolean = getCallCapableSims(context).size > 1

    fun savePreferredSim(context: Context, handle: PhoneAccountHandle?, slotIndex: Int, subId: Int) {
        prefs(context).edit().apply {
            if (slotIndex < 0) {
                remove(PREF_SIM_ID)
                remove(PREF_SIM_COMPONENT)
                remove(PREF_SIM_INDEX)
                remove(PREF_SIM_SUB_ID)
            } else {
                if (handle != null) {
                    putString(PREF_SIM_ID, handle.id)
                    putString(PREF_SIM_COMPONENT, handle.componentName.flattenToString())
                }
                putInt(PREF_SIM_INDEX, slotIndex)
                putInt(PREF_SIM_SUB_ID, subId)
            }
            apply()
        }
    }

    fun getPreferredSimIndex(context: Context): Int = prefs(context).getInt(PREF_SIM_INDEX, -1)

    fun getPreferredSimSubId(context: Context): Int = prefs(context).getInt(PREF_SIM_SUB_ID, -1)

    fun getPreferredSimLabel(context: Context): String {
        val handle = getPreferredSim(context) ?: return "Always ask"
        val sims = getCallCapableSims(context)
        return sims.firstOrNull { it.handle == handle }?.label ?: "Always ask"
    }

    fun getPreferredSim(context: Context): PhoneAccountHandle? {
        val p = prefs(context)
        val savedId = p.getString(PREF_SIM_ID, null)
        val savedComponent = p.getString(PREF_SIM_COMPONENT, null)
        val savedIndex = p.getInt(PREF_SIM_INDEX, -1)
        val savedSubId = p.getInt(PREF_SIM_SUB_ID, -1)

        if (savedIndex < 0) return null

        val available = getCallCapableSims(context)
        if (available.isEmpty()) {
            return PhoneAccountHandle(
                ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService"),
                if (savedSubId > 0) savedSubId.toString() else (savedIndex + 1).toString()
            )
        }

        if (savedSubId > 0) {
            val bySub = available.firstOrNull { it.subId == savedSubId }
            if (bySub?.handle != null) return bySub.handle
        }

        if (savedId != null && savedComponent != null) {
            val byId = available.firstOrNull { it.handle != null && it.handle.id == savedId && it.handle.componentName.flattenToString() == savedComponent }
            if (byId?.handle != null) return byId.handle
        }

        val bySlot = available.firstOrNull { it.slotIndex == savedIndex } ?: available.getOrNull(savedIndex)
        return bySlot?.handle ?: PhoneAccountHandle(
            ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService"),
            if (savedSubId > 0) savedSubId.toString() else (savedIndex + 1).toString()
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(JarvisApplication.PREFS_NAME, Context.MODE_PRIVATE)
}


