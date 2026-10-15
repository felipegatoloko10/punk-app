package com.punk.chat

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM messages WHERE conversationId=:cid ORDER BY ts ASC")
    fun messages(cid: String): Flow<List<ChatMessage>>
    @Insert suspend fun insert(m: ChatMessage)
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun conversations(): Flow<List<Conversation>>
    @Insert suspend fun insertConv(c: Conversation)
}

@Database(entities=[ChatMessage::class, Conversation::class], version=1, exportSchema=false)
abstract class ChatDb: RoomDatabase() { abstract fun dao(): ChatDao }
