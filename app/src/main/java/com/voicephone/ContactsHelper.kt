package com.voicephone

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

data class Contact(val name: String, val number: String)

/**
 * Loads the device contact list and provides fuzzy name matching.
 *
 * Family adds contacts to Android Contacts normally; this app reads them.
 * Cache is loaded once and held in memory for fast repeated lookups.
 */
class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsHelper"
    }

    private var cachedContacts: List<Contact> = emptyList()

    /** Load (or reload) all contacts with a phone number. Call once at startup. */
    fun loadContacts() {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        val result = mutableListOf<Contact>()
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: continue
                val number = it.getString(numIdx) ?: continue
                result += Contact(name, number.replace(Regex("\\s"), ""))
            }
        }
        cachedContacts = result
        Log.d(TAG, "Loaded ${cachedContacts.size} contacts")
    }

    /**
     * Find contacts matching [query].
     * Returns:
     * - Exact match (case-insensitive) first
     * - Then prefix matches
     * - Then contains matches
     * For MVP: returns first match only (disambiguation is Phase 2).
     */
    fun findContacts(query: String): List<Contact> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()

        val exact = cachedContacts.filter { it.name.lowercase() == q }
        if (exact.isNotEmpty()) return exact

        val prefix = cachedContacts.filter { it.name.lowercase().startsWith(q) }
        if (prefix.isNotEmpty()) return prefix

        return cachedContacts.filter { it.name.lowercase().contains(q) }
    }

    /** Returns the single best contact for [query], or null if none found. */
    fun findBestContact(query: String): Contact? = findContacts(query).firstOrNull()

    /** Returns all contact names — passed to Claude for context. */
    fun getContactNames(): List<String> = cachedContacts.map { it.name }.distinct()

    /** Look up a contact by phone number. Strips formatting and normalises to last 9 digits. */
    fun findByNumber(number: String): Contact? {
        val stripped = number.replace(Regex("[\\s\\-+()]"), "")
        val normalised = stripped.takeLast(9)
        return cachedContacts.firstOrNull { contact ->
            val contactStripped = contact.number.replace(Regex("[\\s\\-+()]"), "")
            val contactNormalised = contactStripped.takeLast(9)
            contactNormalised == normalised
        }
    }

    /** True if [input] looks like a dialable phone number (digits, +, spaces, dashes). */
    fun isPhoneNumber(input: String): Boolean =
        input.replace(Regex("[\\s\\-+()]"), "").all { it.isDigit() } &&
                input.replace(Regex("[\\s\\-+()]"), "").length >= 5
}
