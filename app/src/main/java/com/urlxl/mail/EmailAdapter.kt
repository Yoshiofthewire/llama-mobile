package com.urlxl.mail

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class EmailAdapter(
    private var emails: List<Email>,
    private val onEmailClick: ((Email) -> Unit)? = null
) : RecyclerView.Adapter<EmailAdapter.EmailViewHolder>() {

    class EmailViewHolder(view: View, private val onEmailClick: ((Email) -> Unit)?) : RecyclerView.ViewHolder(view) {
        private val cardView: CardView = view as CardView
        private val contentLayout: LinearLayout = view.findViewById(R.id.emailItemContent)
        private val subjectTextView: TextView = view.findViewById(R.id.textViewSubject)
        private val senderTextView: TextView = view.findViewById(R.id.textViewSender)
        private val previewTextView: TextView = view.findViewById(R.id.textViewPreview)

        fun bind(email: Email, palette: ThemePalette) {
            subjectTextView.text = email.subject
            senderTextView.text = email.sender
            previewTextView.text = email.preview

            val panel = Color.parseColor(palette.panel)
            cardView.setCardBackgroundColor(panel)
            contentLayout.setBackgroundColor(panel)
            subjectTextView.setTextColor(Color.parseColor(palette.inkStrong))
            senderTextView.setTextColor(Color.parseColor(palette.ink))
            previewTextView.setTextColor(Color.parseColor(palette.ink))

            itemView.setOnClickListener { onEmailClick?.invoke(email) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_email, parent, false)
        return EmailViewHolder(view, onEmailClick)
    }

    override fun onBindViewHolder(holder: EmailViewHolder, position: Int) {
        val palette = getStoredThemePalette(holder.itemView.context)
        holder.bind(emails[position], palette)
    }

    override fun getItemCount(): Int = emails.size

    fun getEmailAt(position: Int): Email = emails[position]

    fun updateEmails(newEmails: List<Email>) {
        emails = newEmails
        notifyDataSetChanged()
    }
}