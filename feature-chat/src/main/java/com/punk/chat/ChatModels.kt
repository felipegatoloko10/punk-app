package com.punk.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName="messages", indices=[Index(value=["messageId"], unique=true),Index(value=["conversationId"])])
data class ChatMessage(
    @PrimaryKey(autoGenerate=true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val messageId: String = UUID.randomUUID().toString(),
    val status: String = "completed",
    val spoken: Boolean = false,
    val attachmentsJson: String = "[]",
    val lastSeq: Long = 0
)
@Entity(tableName="conversations")
data class Conversation(@PrimaryKey val id:String, val title:String, val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="activity")
data class ActionEvent(@PrimaryKey val id:String, val title:String, val detail:String, val device:String, val status:String, val ts:Long=System.currentTimeMillis())
data class AttachmentDraft(val id:String, val name:String, val mimeType:String, val uri:String, val size:Long=0, val progress:Float=0f, val error:String?=null)
