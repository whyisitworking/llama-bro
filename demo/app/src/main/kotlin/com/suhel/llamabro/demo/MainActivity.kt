package com.suhel.llamabro.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.suhel.llamabro.demo.ui.chat.ChatScreen
import com.suhel.llamabro.demo.ui.shell.AppShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DemoApp).container
        setContent { AppShell(container, chatScreen = { id, chrome -> ChatScreen(container, id, chrome) }) }
    }
}
