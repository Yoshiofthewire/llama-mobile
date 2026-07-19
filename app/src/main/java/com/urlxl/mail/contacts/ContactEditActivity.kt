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
import com.google.android.material.chip.Chip
import com.urlxl.mail.R
import com.urlxl.mail.applyDangerButtonTheme
import com.urlxl.mail.applyPrimaryButtonTheme
import com.urlxl.mail.applyStatusBadgeTheme
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
    private lateinit var titleField: EditText
    private lateinit var departmentField: EditText
    private lateinit var notesField: EditText
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var givenNameField: EditText
    private lateinit var familyNameField: EditText
    private lateinit var middleNameField: EditText
    private lateinit var prefixField: EditText
    private lateinit var suffixField: EditText
    private lateinit var nicknameField: EditText
    private lateinit var phoneticGivenNameField: EditText
    private lateinit var phoneticFamilyNameField: EditText
    private lateinit var pronounsField: EditText
    private lateinit var selfBadge: Chip
    private lateinit var pgpBadge: Chip
    private lateinit var emailList: RepeatableFieldList<ContactFieldDto>
    private lateinit var phoneList: RepeatableFieldList<ContactFieldDto>
    private lateinit var addressList: RepeatableFieldList<ContactAddressDto>

    private var existingUid: String = ""
    private var existingRev: Long = 0

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
        titleField = findViewById(R.id.editContactTitle)
        departmentField = findViewById(R.id.editContactDepartment)
        notesField = findViewById(R.id.editContactNotes)
        givenNameField = findViewById(R.id.editContactGivenName)
        familyNameField = findViewById(R.id.editContactFamilyName)
        middleNameField = findViewById(R.id.editContactMiddleName)
        prefixField = findViewById(R.id.editContactPrefix)
        suffixField = findViewById(R.id.editContactSuffix)
        nicknameField = findViewById(R.id.editContactNickname)
        phoneticGivenNameField = findViewById(R.id.editContactPhoneticGivenName)
        phoneticFamilyNameField = findViewById(R.id.editContactPhoneticFamilyName)
        pronounsField = findViewById(R.id.editContactPronouns)
        selfBadge = findViewById(R.id.contactEditSelfBadge)
        pgpBadge = findViewById(R.id.contactEditPgpBadge)
        findViewById<ExpandableSectionView>(R.id.sectionName).setTitle(getString(R.string.contacts_section_name))
        findViewById<ExpandableSectionView>(R.id.sectionName).setExpanded(true)
        findViewById<ExpandableSectionView>(R.id.sectionWork).setTitle(getString(R.string.contacts_section_work))
        findViewById<ExpandableSectionView>(R.id.sectionContact).setTitle(getString(R.string.contacts_section_contact))
        emailList = RepeatableFieldList(
            container = findViewById(R.id.emailRowsContainer),
            addButton = findViewById(R.id.btnAddEmail),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_email_row_label_hint)
                valueField.hint = getString(R.string.contacts_email_row_value_hint)
                valueField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                // Both fields must read from each other's *live* text, not the bind-time item
                // snapshot — two separate listeners each doing item.copy(singleField = ...) would
                // silently drop whichever field was edited first the next time the other field
                // fires (each closes over the same stale item).
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactFieldDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(emailList.items().size + phoneList.items().size) },
        )
        phoneList = RepeatableFieldList(
            container = findViewById(R.id.phoneRowsContainer),
            addButton = findViewById(R.id.btnAddPhone),
            rowLayoutRes = R.layout.row_contact_two_field,
            removeButtonId = R.id.rowFieldRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowFieldA)
                val valueField = rowView.findViewById<EditText>(R.id.rowFieldB)
                labelField.hint = getString(R.string.contacts_phone_row_label_hint)
                valueField.hint = getString(R.string.contacts_phone_row_value_hint)
                valueField.inputType = android.text.InputType.TYPE_CLASS_PHONE
                labelField.setText(item.label.orEmpty())
                valueField.setText(item.value)
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            value = valueField.text.toString().trim(),
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                valueField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.value.isBlank() },
            default = { ContactFieldDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(emailList.items().size + phoneList.items().size) },
        )
        findViewById<ExpandableSectionView>(R.id.sectionAddresses).setTitle(getString(R.string.contacts_section_addresses))
        addressList = RepeatableFieldList(
            container = findViewById(R.id.addressRowsContainer),
            addButton = findViewById(R.id.btnAddAddress),
            rowLayoutRes = R.layout.row_contact_address,
            removeButtonId = R.id.rowAddressRemove,
            bind = { rowView, item, onItemChanged ->
                val labelField = rowView.findViewById<EditText>(R.id.rowAddressLabel)
                val streetField = rowView.findViewById<EditText>(R.id.rowAddressStreet)
                val cityField = rowView.findViewById<EditText>(R.id.rowAddressCity)
                val regionField = rowView.findViewById<EditText>(R.id.rowAddressRegion)
                val postalField = rowView.findViewById<EditText>(R.id.rowAddressPostalCode)
                val countryField = rowView.findViewById<EditText>(R.id.rowAddressCountry)
                labelField.hint = getString(R.string.contacts_address_label_hint)
                streetField.hint = getString(R.string.contacts_address_street_hint)
                cityField.hint = getString(R.string.contacts_address_city_hint)
                regionField.hint = getString(R.string.contacts_address_region_hint)
                postalField.hint = getString(R.string.contacts_address_postal_code_hint)
                countryField.hint = getString(R.string.contacts_address_country_hint)
                labelField.setText(item.label.orEmpty())
                streetField.setText(item.street.orEmpty())
                cityField.setText(item.city.orEmpty())
                regionField.setText(item.region.orEmpty())
                postalField.setText(item.postalCode.orEmpty())
                countryField.setText(item.country.orEmpty())
                val emit: () -> Unit = {
                    onItemChanged(
                        item.copy(
                            label = labelField.text.toString().trim().ifBlank { null },
                            street = streetField.text.toString().trim().ifBlank { null },
                            city = cityField.text.toString().trim().ifBlank { null },
                            region = regionField.text.toString().trim().ifBlank { null },
                            postalCode = postalField.text.toString().trim().ifBlank { null },
                            country = countryField.text.toString().trim().ifBlank { null },
                        ),
                    )
                }
                labelField.addTextChangedListener(SimpleTextWatcher(emit))
                streetField.addTextChangedListener(SimpleTextWatcher(emit))
                cityField.addTextChangedListener(SimpleTextWatcher(emit))
                regionField.addTextChangedListener(SimpleTextWatcher(emit))
                postalField.addTextChangedListener(SimpleTextWatcher(emit))
                countryField.addTextChangedListener(SimpleTextWatcher(emit))
            },
            isBlank = { it.label.isNullOrBlank() && it.street.isNullOrBlank() && it.city.isNullOrBlank() && it.region.isNullOrBlank() && it.postalCode.isNullOrBlank() && it.country.isNullOrBlank() },
            default = { ContactAddressDto() },
            onChanged = { findViewById<ExpandableSectionView>(R.id.sectionAddresses).setItemCount(addressList.items().size) },
        )
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
            titleField.setText(dto.title.orEmpty())
            departmentField.setText(dto.department.orEmpty())
            notesField.setText(dto.notes.orEmpty())
            givenNameField.setText(dto.givenName.orEmpty())
            familyNameField.setText(dto.familyName.orEmpty())
            middleNameField.setText(dto.middleName.orEmpty())
            prefixField.setText(dto.prefix.orEmpty())
            suffixField.setText(dto.suffix.orEmpty())
            nicknameField.setText(dto.nickname.orEmpty())
            phoneticGivenNameField.setText(dto.phoneticGivenName.orEmpty())
            phoneticFamilyNameField.setText(dto.phoneticFamilyName.orEmpty())
            pronounsField.setText(dto.pronouns.orEmpty())
            selfBadge.visibility = if (dto.isSelf) View.VISIBLE else View.GONE
            if (dto.isSelf) {
                selfBadge.text = getString(R.string.contact_self_label)
                applyStatusBadgeTheme(this@ContactEditActivity, selfBadge, active = true)
            }
            val hasKey = !dto.pgpKey.isNullOrBlank()
            pgpBadge.visibility = if (hasKey) View.VISIBLE else View.GONE
            if (hasKey) {
                pgpBadge.text = getString(R.string.contacts_pgp_badge_visible)
                applyStatusBadgeTheme(this@ContactEditActivity, pgpBadge, active = true)
            }
            findViewById<ExpandableSectionView>(R.id.sectionWork).setExpanded(
                dto.org != null || dto.title != null || dto.department != null,
            )
            emailList.setItems(dto.emails)
            phoneList.setItems(dto.phones)
            findViewById<ExpandableSectionView>(R.id.sectionContact).setExpanded(dto.emails.isNotEmpty() || dto.phones.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionContact).setItemCount(dto.emails.size + dto.phones.size)
            addressList.setItems(dto.addresses)
            findViewById<ExpandableSectionView>(R.id.sectionAddresses).setExpanded(dto.addresses.isNotEmpty())
            findViewById<ExpandableSectionView>(R.id.sectionAddresses).setItemCount(dto.addresses.size)
        }
    }

    private fun save() {
        val fn = fnField.text.toString().trim()
        if (fn.isBlank()) {
            Toast.makeText(this, R.string.contacts_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val dto = mergedContactDto(
            loaded = loadedDto,
            uid = existingUid,
            rev = existingRev,
            fn = fn,
            givenName = givenNameField.text.toString().trim().ifBlank { null },
            familyName = familyNameField.text.toString().trim().ifBlank { null },
            middleName = middleNameField.text.toString().trim().ifBlank { null },
            prefix = prefixField.text.toString().trim().ifBlank { null },
            suffix = suffixField.text.toString().trim().ifBlank { null },
            nickname = nicknameField.text.toString().trim().ifBlank { null },
            org = orgField.text.toString().trim().ifBlank { null },
            title = titleField.text.toString().trim().ifBlank { null },
            department = departmentField.text.toString().trim().ifBlank { null },
            notes = notesField.text.toString().trim().ifBlank { null },
            birthday = null,
            emails = emailList.items(),
            phones = phoneList.items(),
            addresses = addressList.items(),
            ims = emptyList(),
            websites = emptyList(),
            relations = emptyList(),
            events = emptyList(),
            phoneticGivenName = phoneticGivenNameField.text.toString().trim().ifBlank { null },
            phoneticFamilyName = phoneticFamilyNameField.text.toString().trim().ifBlank { null },
            customFields = emptyList(),
            pronouns = pronounsField.text.toString().trim().ifBlank { null },
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

    /** [android.text.TextWatcher] that only cares about the end state, matching every row-field
     *  use in this Activity (none need before/during-change info). */
    private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged()
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
    givenName: String?,
    familyName: String?,
    middleName: String?,
    prefix: String?,
    suffix: String?,
    nickname: String?,
    org: String?,
    title: String?,
    department: String?,
    notes: String?,
    birthday: String?,
    emails: List<ContactFieldDto>,
    phones: List<ContactFieldDto>,
    addresses: List<ContactAddressDto>,
    ims: List<ContactImDto>,
    websites: List<ContactUrlDto>,
    relations: List<ContactRelationDto>,
    events: List<ContactEventDto>,
    phoneticGivenName: String?,
    phoneticFamilyName: String?,
    customFields: List<ContactCustomFieldDto>,
    pronouns: String?,
): ContactDto = loaded.copy(
    uid = uid,
    rev = rev,
    fn = fn,
    givenName = givenName,
    familyName = familyName,
    middleName = middleName,
    prefix = prefix,
    suffix = suffix,
    nickname = nickname,
    org = org,
    title = title,
    department = department,
    notes = notes,
    birthday = birthday,
    emails = emails,
    phones = phones,
    addresses = addresses,
    ims = ims,
    websites = websites,
    relations = relations,
    events = events,
    phoneticGivenName = phoneticGivenName,
    phoneticFamilyName = phoneticFamilyName,
    customFields = customFields,
    pronouns = pronouns,
)
