package com.punk.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName="messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate=true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val text: String,
    val ts: Long = System.currentTimeMillis()
)

@Entity(tableName="conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)
