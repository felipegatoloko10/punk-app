package com.punk.chat

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM messages WHERE conversationId=:cid ORDER BY ts ASC,id ASC") fun messages(cid:String):Flow<List<ChatMessage>>
    @Query("SELECT * FROM messages WHERE messageId=:mid LIMIT 1") suspend fun message(mid:String):ChatMessage?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insert(message:ChatMessage):Long
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC") fun conversations():Flow<List<Conversation>>
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC") suspend fun allConversations():List<Conversation>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertConv(conversation:Conversation)
    @Query("UPDATE conversations SET title=:title WHERE id=:id") suspend fun rename(id:String,title:String)
    @Query("SELECT * FROM activity ORDER BY ts DESC LIMIT 500") fun activity():Flow<List<ActionEvent>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertEvent(event:ActionEvent)
    @Query("UPDATE messages SET status='interrupted' WHERE status IN ('sending','running','streaming')") suspend fun interruptStale()
    @Query("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY ts DESC LIMIT 1000)") suspend fun trimActivity()
}
@Database(entities=[ChatMessage::class,Conversation::class,ActionEvent::class],version=1,exportSchema=false)
abstract class ChatDb:RoomDatabase() { abstract fun dao():ChatDao }
