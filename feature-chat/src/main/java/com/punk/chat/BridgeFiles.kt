package com.punk.chat

import android.content.Context
import android.net.Uri
import com.punk.bridge.*
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object BridgeFiles {
    private const val MAX_SIZE = 512L * 1024 * 1024
    suspend fun upload(ctx: Context, draft: AttachmentDraft, progress: (Float) -> Unit): JsonObject {
        val config = BridgePrefs.load(ctx)
        require(config.deviceId.isNotBlank()) { "Conecte ao PC antes de enviar arquivos" }
        val cache = File(ctx.cacheDir, "uploads").apply { mkdirs() }
        val file = File(cache, draft.id)
        if (!file.exists()) {
            val temp = File(cache, draft.id + ".part")
            try {
                ctx.contentResolver.openInputStream(Uri.parse(draft.uri))!!.use { input -> temp.outputStream().use { output ->
                    val bytes = ByteArray(256 * 1024); var total = 0L
                    while (true) { coroutineContext.ensureActive(); val count=input.read(bytes); if(count<0) break
                        total+=count; require(total<=MAX_SIZE) { "O limite por arquivo é 512 MB" }; output.write(bytes,0,count) }
                } }
                check(temp.renameTo(file)) { "Não foi possível preparar o arquivo" }
            } finally { temp.delete() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(256*1024); while(true) { coroutineContext.ensureActive(); val count=input.read(buffer); if(count<0)break; digest.update(buffer,0,count) } }
        val sha=digest.digest().joinToString("") { "%02x".format(it) }
        val prefs=ctx.getSharedPreferences("punk_uploads",0)
        val key=config.deviceId+":"+sha
        return BridgeHttp.client(config).useClient { client ->
            fun jsonRequest(path:String, body:JsonObject?=null):JsonObject {
                val builder=BridgeHttp.request(config,path)
                if(body!=null) builder.post(BridgeHttp.jsonBody(body))
                return client.newCall(builder.build()).execute().use { response ->
                    check(response.isSuccessful) { "Transferência interrompida (${response.code}). Tente novamente para retomar." }
                    Json.parseToJsonElement(response.body!!.string()).jsonObject
                }
            }
            var uploadId=prefs.getString(key,null)
            var state=uploadId?.let { runCatching { jsonRequest("/api/uploads/$it") }.getOrNull() }
            if(state==null) {
                state=jsonRequest("/api/uploads",buildJsonObject { put("name",draft.name); put("mime",draft.mimeType); put("size",file.length()); put("sha256",sha) })
                uploadId=state.getValue("uploadId").jsonPrimitive.content
                prefs.edit().putString(key,uploadId).apply()
            }
            var offset=state["offset"]?.jsonPrimitive?.long ?: 0
            RandomAccessFile(file,"r").use { input ->
                input.seek(offset)
                while(offset<file.length()) {
                    coroutineContext.ensureActive()
                    val buffer=ByteArray(minOf(1024*1024L,file.length()-offset).toInt())
                    input.readFully(buffer)
                    val request=BridgeHttp.request(config,"/api/uploads/$uploadId/chunks").header("Upload-Offset",offset.toString())
                        .put(buffer.toRequestBody("application/octet-stream".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Envio interrompido. Tente novamente para retomar." }
                        offset=Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("offset").jsonPrimitive.long
                    }
                    progress(if(file.length()==0L)1f else offset.toFloat()/file.length())
                }
            }
            val result=if(state["complete"]?.jsonPrimitive?.booleanOrNull==true) buildJsonObject {
                put("uploadId",uploadId); put("name",draft.name); put("mime",draft.mimeType); put("size",file.length()); put("sha256",sha)
            } else jsonRequest("/api/uploads/$uploadId/complete",buildJsonObject {})
            file.delete(); progress(1f); result
        }
    }
    suspend fun download(ctx:Context,id:String,name:String):File {
        require(id.matches(Regex("[A-Za-z0-9:_-]{1,160}"))) { "Arquivo inválido" }
        val config=BridgePrefs.load(ctx)
        val directory=File(ctx.cacheDir,"received").apply { mkdirs() }
        val target=File(directory,id+"_"+name.replace(Regex("[^A-Za-z0-9._-]"),"_").take(120))
        val temp=File(directory,target.name+".part")
        BridgeHttp.client(config).useClient { client ->
            val metadata=client.newCall(BridgeHttp.request(config,"/api/uploads/$id").build()).execute().use { response ->
                check(response.isSuccessful) { "Metadados do arquivo indisponíveis" }
                Json.parseToJsonElement(response.body!!.string()).jsonObject
            }
            val expected=metadata["sha256"]?.jsonPrimitive?.content ?: error("O PC não informou a integridade do arquivo")
            val expectedSize=metadata["size"]?.jsonPrimitive?.long ?: error("Tamanho não informado")
            require(expectedSize<=MAX_SIZE)
            client.newCall(BridgeHttp.request(config,"/api/files/$id").build()).execute().use { response ->
            check(response.isSuccessful) { "Arquivo indisponível no PC" }
            try { response.body!!.byteStream().use { input -> temp.outputStream().use { output ->
                val buffer=ByteArray(256*1024); var size=0L
                while(true) { coroutineContext.ensureActive(); val count=input.read(buffer); if(count<0)break; size+=count
                    require(size<=MAX_SIZE); output.write(buffer,0,count) }
            } }
                val hash=MessageDigest.getInstance("SHA-256")
                temp.inputStream().use { input -> val buffer=ByteArray(256*1024); while(true) { val count=input.read(buffer); if(count<0)break; hash.update(buffer,0,count) } }
                check(temp.length()==expectedSize && hash.digest().joinToString("") { "%02x".format(it) }==expected) { "O arquivo recebido não passou na verificação de integridade" }
                if(target.exists())check(target.delete())
                check(temp.renameTo(target))
            } finally { temp.delete() }
        } }
        return target
    }
}
