package com.punk.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object PunkAccount {
    private val mutex=Mutex()
    private val json=Json { ignoreUnknownKeys=true }
    private val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    private fun prefs(ctx:Context)=ctx.getSharedPreferences("punk_account_v1",Context.MODE_PRIVATE)
    private fun endpoints(ctx:Context)=ctx.assets.open("punk-account.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    private fun endpoint(ctx:Context,key:String):String=endpoints(ctx).getValue(key).jsonPrimitive.content.also {
        require(it.startsWith("https://") && it.toHttpUrl().host.endsWith(".neon.tech")) { "Serviço de contas não ativado" }
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("punk.account.v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("punk.account.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun session(ctx:Context):JsonObject {
        val encoded=prefs(ctx).getString("session",null) ?: return buildJsonObject {}
        return runCatching {
            val parts=encoded.split(":")
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            json.parseToJsonElement(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)).jsonObject
        }.getOrElse { error("Sessão local indisponível. Entre novamente na conta.") }
    }
    private fun save(ctx:Context,value:JsonObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        check(prefs(ctx).edit().putString("session",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) { "Falha ao salvar sessão" }
    }
    fun email(ctx:Context):String=runCatching { session(ctx)["email"]?.jsonPrimitive?.content ?: "" }.getOrDefault("")
    private fun installation(ctx:Context):String {
        prefs(ctx).getString("installation",null)?.let { return it }
        val id=UUID.randomUUID().toString()
        check(prefs(ctx).edit().putString("installation",id).commit()); return id
    }
    private fun auth(ctx:Context,path:String,body:JsonObject?,old:JsonObject):Pair<JsonObject,JsonElement> {
        val url=(endpoint(ctx,"authUrl")+path).toHttpUrl()
        val request=Request.Builder().url(url).header("Origin","http://localhost:3000")
        val cookies=old["cookies"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }?.toMutableMap() ?: mutableMapOf()
        if(cookies.isNotEmpty())request.header("Cookie",cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if(body!=null)request.post(BridgeHttp.jsonBody(body))
        http.newCall(request.build()).execute().use { res ->
            require(res.isSuccessful) { "Login recusado. Confira e-mail e senha ou entre novamente." }
            for(cookie in Cookie.parseAll(url,res.headers)) {
                if(cookie.expiresAt<=System.currentTimeMillis())cookies.remove(cookie.name) else cookies[cookie.name]=cookie.value
            }
            val data=json.parseToJsonElement(res.body?.string() ?: "null")
            val merged=buildJsonObject {
                old.forEach { (k,v)->put(k,v) }; put("cookies",buildJsonObject { cookies.forEach { (k,v)->put(k,v) } })
                res.header("set-auth-jwt")?.let { put("jwt",it) }
            }
            return merged to data
        }
    }
    private fun renew(ctx:Context,old:JsonObject):JsonObject {
        val (updated,data)=auth(ctx,"/get-session",null,JsonObject(old.filterKeys { it!="jwt" }))
        val user=(data as? JsonObject)?.get("user") as? JsonObject ?: error("Sessão expirada. Entre novamente.")
        var result=updated
        if(updated["jwt"]==null) {
            val (cookies,token)=auth(ctx,"/token",null,updated)
            result=buildJsonObject { cookies.forEach { (k,v)->put(k,v) };put("jwt",token.jsonObject.getValue("token")) }
        }
        val jwt=result.getValue("jwt").jsonPrimitive.content
        val claims=json.parseToJsonElement(String(Base64.decode(jwt.split('.')[1],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))).jsonObject
        return buildJsonObject {
            result.forEach { (k,v)->put(k,v) };put("expires",claims.getValue("exp"));put("email",user.getValue("email"));put("owner",user.getValue("id"))
        }.also { save(ctx,it) }
    }
    suspend fun login(ctx:Context,email:String,password:String,signup:Boolean=false):Unit=withContext(Dispatchers.IO) {
        mutex.withLock {
            require(email.length in 3..254 && password.length in 8..1024) { "Informe e-mail e senha de pelo menos 8 caracteres" }
            val body=buildJsonObject { put("email",email.trim());put("password",password);if(signup)put("name","Punk") }
            val (cookies,_)=auth(ctx,if(signup)"/sign-up/email" else "/sign-in/email",body,buildJsonObject {})
            renew(ctx,cookies)
        }
    }
    private fun access(ctx:Context):String {
        var stored=session(ctx)
        if((stored["expires"]?.jsonPrimitive?.longOrNull ?: 0)<System.currentTimeMillis()/1000+90)stored=renew(ctx,stored)
        return stored.getValue("jwt").jsonPrimitive.content
    }
    private fun api(ctx:Context,path:String,body:JsonObject?=null):JsonElement {
        val request=Request.Builder().url(endpoint(ctx,"apiUrl")+path).header("Authorization","Bearer "+access(ctx))
        if(body!=null)request.post(BridgeHttp.jsonBody(body))
        http.newCall(request.build()).execute().use { res ->
            require(res.isSuccessful) { if(res.code==401)"Sessão expirada. Entre novamente." else "Não foi possível acessar seus computadores (${res.code})" }
            return json.parseToJsonElement(res.body!!.string())
        }
    }
    suspend fun computers(ctx:Context):JsonArray=withContext(Dispatchers.IO) { mutex.withLock { api(ctx,"/computers").jsonArray } }
    suspend fun reconnectTarget(ctx:Context,current:BridgeConfig):String {
        if(current.accountComputerId.isNotEmpty())return current.accountComputerId
        if(email(ctx).isEmpty())return ""
        return ConnectionRoutes.accountTarget(current,computers(ctx))
    }
    suspend fun connect(ctx:Context,id:String):BridgeConfig=withContext(Dispatchers.IO) { mutex.withLock {
        val computer=api(ctx,"/computers").jsonArray.map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content==id }
            ?: error("Computador não encontrado nesta conta")
        val local=computer.getValue("local_host").jsonPrimitive.content
        val remote=computer.getValue("endpoint").jsonPrimitive.content
        val base=BridgeConfig(host=local,port=computer.getValue("local_port").jsonPrimitive.int,useTls=true,
            certificateFingerprint=computer.getValue("fingerprint").jsonPrimitive.content,accountComputerId=id)
        val candidates=ConnectionRoutes.ordered(base,remote)
        val failures=mutableListOf<String>()
        for(route in candidates) {
            val ticket=api(ctx,"/rpc/punk_create_connect_grant",buildJsonObject { put("p_computer_id",id);put("p_installation_id",installation(ctx)) }).jsonPrimitive.content
            val connected=runCatching { BridgeHttp.client(route).newBuilder().callTimeout(if(route.trustMode=="cloudflare")20 else 4,TimeUnit.SECONDS).build().useClient { client ->
                val request=Request.Builder().url(route.httpUrl()+"/api/account/connect")
                    .post(BridgeHttp.jsonBody(buildJsonObject { put("ticket",ticket) }))
                val previous=BridgePrefs.load(ctx)
                if(previous.token.isNotEmpty() && (previous.accountComputerId==id || previous.certificateFingerprint==base.certificateFingerprint))
                    request.header("Authorization","Bearer "+previous.token)
                client.newCall(request.build()).execute().use { res ->
                    require(res.isSuccessful) { "Acesso recusado pelo PC (HTTP ${res.code})" }
                    val result=json.parseToJsonElement(res.body!!.string()).jsonObject
                    route.copy(token=result.getValue("token").jsonPrimitive.content,deviceId=result.getValue("deviceId").jsonPrimitive.content)
                }
            } }.onFailure { failures.add(ConnectionRoutes.label(route)+": "+ConnectionRoutes.failure(it)) }.getOrNull()
            if(connected!=null) { currentCoroutineContext().ensureActive();BridgePrefs.save(ctx,connected);return@withLock connected }
        }
        error(if(remote.isBlank())"O PC não publicou uma rota externa. Aguarde o túnel no Desktop e tente novamente." else "Não foi possível conectar. "+failures.joinToString("; "))
    } }
    suspend fun logout(ctx:Context):Unit=withContext(Dispatchers.IO) { mutex.withLock {
        val old=session(ctx)
        try { auth(ctx,"/sign-out",buildJsonObject {},old) } finally {
            prefs(ctx).edit().remove("session").commit()
            if(BridgePrefs.load(ctx).accountComputerId.isNotEmpty())BridgePrefs.save(ctx,BridgeConfig(host=""))
        }
    } }
}
