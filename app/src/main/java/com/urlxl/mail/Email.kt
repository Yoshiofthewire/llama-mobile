package com.urlxl.mail

data class Email(
    val id: String,
    val subject: String,
    val sender: String,
    val preview: String,
    val keywords: Set<String> = emptySet(),
)