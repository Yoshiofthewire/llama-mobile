package com.urlxl.mail.contacts

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.R
import com.urlxl.mail.applyDangerButtonTheme
import com.urlxl.mail.applyPrimaryButtonTheme
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyTopInsetWithHeader
import com.urlxl.mail.bindAvatar
import com.urlxl.mail.contacts.device.DeviceContactsRuntime
import com.urlxl.mail.data.DataRuntime
import kotlinx.coroutines.launch

/** Create/edit form. Only fn is required per Mobile_Contact_Sync.md's field table; everything
 *  else is optional. A contact may carry more than one email/phone (set elsewhere, e.g. the web
 *  UI or CardDAV) — this single-field editor preserves any entries beyond the first untouched
 *  rather than silently dropping them on save. */
class ContactEditActivity : AppCompatActivity() {

    private lateinit var avatarView: TextView
    private lateinit var fnField: EditText
    private lateinit var orgField: EditText
    private lateinit var emailField: EditText
    private lateinit var phoneField: EditText
    private lateinit var notesField: EditText
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button

    private var existingUid: String = ""
    private var existingRev: Long = 0
    private var extraEmails: List<ContactFieldDto> = emptyList()
    private var extraPhones: List<ContactFieldDto> = emptyList()

    /** The full contact as loaded from Room, including every field this single-screen editor has
     *  no UI for (structured name parts, addresses, ims, websites, relations, events, phonetic
     *  names, department, customFields, pronouns, photoRef, groupIDs, pgpKey, isSelf, ...). [save]
     *  must `.copy()` off this rather than building a fresh [ContactDto], or every field not shown
     *  here gets silently wiped — locally immediately, and on the server too, since both the local
     *  upsert and the server's PUT/push handlers fully replace the stored contact rather than
     *  merging. Stays at [ContactDto]'s all-default value for new (not-yet-existing) contacts,
     *  which is correct: there's nothing prior to preserve. */
    private var loadedDto: ContactDto = ContactDto()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_edit)
        applyThemeToActivity(this)
        applyTopInsetWithHeader(this, findViewById(R.id.contactEditRoot))
        setTitle(R.string.contacts_edit_title)

        avatarView = findViewById(R.id.contactEditAvatar)
        fnField = findViewById(R.id.editContactName)
        orgField = findViewById(R.id.editContactOrg)
        emailField = findViewById(R.id.editContactEmail)
        phoneField = findViewById(R.id.editContactPhone)
        notesField = findViewById(R.id.editContactNotes)
        saveButton = findViewById(R.id.btnSaveContact)
        deleteButton = findViewById(R.id.btnDeleteContact)

        applyPrimaryButtonTheme(this, saveButton)
        applyDangerButtonTheme(this, deleteButton)
        bindAvatar(this, avatarView, fnField.text.toString(), sizeDp = 52)
        fnField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                bindAvatar(this@ContactEditActivity, avatarView, s?.toString().orEmpty(), sizeDp = 52)
            }
        })

        existingUid = intent.getStringExtra(EXTRA_UID).orEmpty()
        if (existingUid.isBlank()) {
            deleteButton.visibility = View.GONE
        } else {
            loadExisting(existingUid)
        }

        saveButton.setOnClickListener { save() }
        deleteButton.setOnClickListener { delete() }
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyPrimaryButtonTheme(this, saveButton)
        applyDangerButtonTheme(this, deleteButton)
        bindAvatar(this, avatarView, fnField.text.toString(), sizeDp = 52)
    }

    private fun loadExisting(uid: String) {
        lifecycleScope.launch {
            val entity = DataRuntime.graph(this@ContactEditActivity).database.contactDao().getByUid(uid) ?: return@launch
            val dto = entity.toDto()
            loadedDto = dto
            existingRev = dto.rev
            fnField.setText(dto.fn)
            orgField.setText(dto.org.orEmpty())
            notesField.setText(dto.notes.orEmpty())
            emailField.setText(dto.emails.firstOrNull()?.value.orEmpty())
            phoneField.setText(dto.phones.firstOrNull()?.value.orEmpty())
            extraEmails = dto.emails.drop(1)
            extraPhones = dto.phones.drop(1)
        }
    }

    private fun save() {
        val fn = fnField.text.toString().trim()
        if (fn.isBlank()) {
            Toast.makeText(this, R.string.contacts_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val email = emailField.text.toString().trim()
        val phone = phoneField.text.toString().trim()
        val emails = (if (email.isNotBlank()) listOf(ContactFieldDto(value = email)) else emptyList()) + extraEmails
        val phones = (if (phone.isNotBlank()) listOf(ContactFieldDto(value = phone)) else emptyList()) + extraPhones

        val dto = mergedContactDto(
            loaded = loadedDto,
            uid = existingUid,
            rev = existingRev,
            fn = fn,
            org = orgField.text.toString().trim().ifBlank { null },
            notes = notesField.text.toString().trim().ifBlank { null },
            emails = emails,
            phones = phones,
        )

        lifecycleScope.launch {
            val graph = ContactsRuntime.graph(this@ContactEditActivity)
            if (existingUid.isBlank()) {
                graph.repository.queueCreate(dto)
            } else {
                graph.repository.queueUpdate(dto)
            }
            graph.coordinator.syncNowAsync()
            DeviceContactsRuntime.graph(this@ContactEditActivity).coordinator.syncNowAsync()
            Toast.makeText(this@ContactEditActivity, R.string.contacts_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun delete() {
        lifecycleScope.launch {
            val deviceGraph = DeviceContactsRuntime.graph(this@ContactEditActivity)
            deviceGraph.repository.deleteDeviceRawContact(existingUid)

            val graph = ContactsRuntime.graph(this@ContactEditActivity)
            graph.repository.queueDelete(existingUid, existingRev)
            graph.coordinator.syncNowAsync()
            finish()
        }
    }

    companion object {
        const val EXTRA_UID = "contact_uid"
    }
}

/** Pulled out of [ContactEditActivity.save] so the field-preservation behavior — every field this
 *  single-screen editor has no UI for must survive a save, since [loaded]'s `.copy()` is the only
 *  thing standing between an edit and a silent full-contact wipe (see [ContactEditActivity]'s
 *  `loadedDto` KDoc) — is unit-testable without a Context-backed Room/Activity. */
internal fun mergedContactDto(
    loaded: ContactDto,
    uid: String,
    rev: Long,
    fn: String,
    org: String?,
    notes: String?,
    emails: List<ContactFieldDto>,
    phones: List<ContactFieldDto>,
): ContactDto = loaded.copy(
    uid = uid,
    rev = rev,
    fn = fn,
    org = org,
    notes = notes,
    emails = emails,
    phones = phones,
)
