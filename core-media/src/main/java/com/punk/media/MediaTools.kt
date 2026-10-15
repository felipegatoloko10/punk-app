package com.punk.media

import android.content.Context
import android.os.Environment
import java.io.File

object MediaTools {
    fun listSdcard(path: String = "/sdcard", limit: Int = 100): String {
        val root = File(if(path.startsWith("/sdcard")) path else "/sdcard/$path")
        if(!root.exists()) return "caminho n\u00e3o existe: $path"
        val files = root.listFiles()?.take(limit) ?: return "(vazio)"
        return files.joinToString("\n"){ f -> "${if(f.isDirectory) "DIR " else "FILE"} ${f.name} ${if(f.isFile) "${f.length()} bytes" else ""}" }.take(8000)
    }
    fun fileInfo(path: String): String {
        val f = File(path)
        if(!f.exists()) return "n\u00e3o existe"
        return "path=${f.absolutePath} size=${f.length()} dir=${f.isDirectory} readable=${f.canRead()}"
    }
}
