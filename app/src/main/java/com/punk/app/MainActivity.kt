package com.punk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.punk.bridge.BridgeService
import com.punk.chat.ChatScreen
import com.punk.hud.HudScreen
import com.punk.app.ui.PunkTheme
import com.punk.app.ui.ConfigScreen

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Solicitar permissões em runtime
        val perms = arrayOf(
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG
        )
        requestPermissions(perms, 1001)

        try {
            if (rikka.shizuku.Shizuku.pingBinder()) {
                if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    rikka.shizuku.Shizuku.requestPermission(1002)
                }
            }
        } catch (_: Throwable) {}

        // start bridge eagerly (YOLO mode)
        startBridge()
        setContent {
            PunkTheme {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(bottomBar={
                    NavigationBar{
                        NavigationBarItem(selected=tab==0, onClick={tab=0}, label={Text("HUD")}, icon={Text("\u25c9")})
                        NavigationBarItem(selected=tab==1, onClick={tab=1}, label={Text("Chat")}, icon={Text("\u2709")})
                        NavigationBarItem(selected=tab==2, onClick={tab=2}, label={Text("Config")}, icon={Text("\u2699")})
                    }
                }){ pad ->
                    Box(Modifier.padding(pad)){
                        when(tab){
                            0 -> HudScreen()
                            1 -> ChatScreen()
                            2 -> ConfigScreen(onConnect={ h,p,t -> startBridge(h,p,t) })
                        }
                    }
                }
            }
        }
    }
    private fun startBridge(host:String?=null, port:Int?=null, token:String?=null){
        val i = Intent(this, BridgeService::class.java).apply{
            action = BridgeService.ACTION_START
            if(host!=null) putExtra(BridgeService.EXTRA_HOST, host)
            if(port!=null) putExtra(BridgeService.EXTRA_PORT, port)
            if(token!=null) putExtra(BridgeService.EXTRA_TOKEN, token)
        }
        startForegroundService(i)
    }
}
