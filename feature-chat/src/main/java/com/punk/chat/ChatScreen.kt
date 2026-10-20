package com.punk.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.punk.bridge.BridgeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    onVoice: () -> Unit = {},
    onSettings: () -> Unit = {},
    onPickAttachment: () -> Unit = {}
) {
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()
    val state by vm.bridgeState.collectAsState()
    val busy by vm.busy.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val conversationId by vm.conversationId.collectAsState()
    val attachments by vm.attachments.collectAsState()
    var history by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val connected = state is BridgeState.Connected
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val visibleMessages = messages.filter { it.conversationId == conversationId }
    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.text?.length, busy) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= listState.layoutInfo.totalItemsCount - 3 || visibleMessages.size <= 2) {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
    }
    LaunchedEffect(conversationId) { if (visibleMessages.isNotEmpty()) listState.scrollToItem(visibleMessages.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=18.dp,end=8.dp,top=6.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            BrandMark()
            Column(Modifier.weight(1f).padding(start=8.dp)) {
                Text(stringResource(R.string.punk_chat_name),style=MaterialTheme.typography.titleLarge)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(if(connected)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,CircleShape))
                    Text(if(connected)"Conectado ao seu PC" else if(state is BridgeState.Connecting)"Conectando…" else "Seu assistente pessoal",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick={history=true}) { ChatIcon("history",stringResource(R.string.punk_chat_history)) }
            IconButton(onClick=vm::newConversation,enabled=!busy) { ChatIcon("plus",stringResource(R.string.punk_chat_new)) }
            IconButton(onClick=onSettings) { ChatIcon("settings",stringResource(R.string.punk_chat_settings)) }
        }
        if (!connected && !keyboardVisible) {
            Surface(color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth(),shape=RoundedCornerShape(14.dp),onClick=onSettings) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ChatIcon("link",tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(if(state is BridgeState.Connecting)stringResource(R.string.punk_chat_connecting) else stringResource(R.string.punk_chat_connect_pc),style=MaterialTheme.typography.labelLarge)
                        Text(if(state is BridgeState.Failed)stringResource(R.string.punk_chat_connection_review) else stringResource(R.string.punk_chat_pair_hint),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChatIcon("chevron")
                }
            }
        }
        LazyColumn(
            state=listState,
            modifier=Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(horizontal=20.dp,vertical=24.dp),
            verticalArrangement=Arrangement.spacedBy(22.dp)
        ) {
            if(visibleMessages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top=30.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                        AssistantCore()
                        Text(stringResource(R.string.punk_chat_greeting),style=MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.punk_chat_intro),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Suggestion(stringResource(R.string.punk_chat_suggestion_resources), "O que você consegue fazer no meu PC e no celular?", vm::onInput)
                        Suggestion(stringResource(R.string.punk_chat_suggestion_phone), "Como estão a bateria e o armazenamento do meu celular?", vm::onInput)
                        Suggestion(stringResource(R.string.punk_chat_suggestion_day), "Me ajude a organizar meu dia.", vm::onInput)
                    }
                }
            }
            itemsIndexed(visibleMessages,key={_,message -> message.messageId}) { index,message ->
                val retryId=if(message.role=="user")message.messageId else visibleMessages.take(index).lastOrNull { it.role=="user" }?.messageId
                MessageBubble(message,onRetry={retryId?.let(vm::retry)},canRetry=retryId!=null&&!busy,onOpenAttachment=vm::openAttachment)
            }
            if(busy) {
                item {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                        Text(stringResource(R.string.punk_chat_working),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick=vm::stop) { Text(stringResource(R.string.punk_chat_stop)) }
                    }
                }
            }
        }
        Surface(
            modifier=Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp,vertical=8.dp),
            color=MaterialTheme.colorScheme.surface,
            shape=RoundedCornerShape(24.dp),
            border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(6.dp)) {
                if(attachments.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=6.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        attachments.forEach { draft ->
                            Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp)) {
                                Row(Modifier.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.widthIn(max=180.dp).padding(vertical=8.dp)) {
                                        Text(draft.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
                                        when {
                                            draft.error!=null -> Text(draft.error ?: "Falha no anexo",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall,maxLines=2)
                                            draft.progress>0f && draft.progress<1f -> Text("Enviando ${(draft.progress*100).toInt()}%",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                            else -> Text(if(draft.size>0)"${(draft.size/1024).coerceAtLeast(1)} KB" else "Anexo",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick={vm.removeAttachment(draft.id)},enabled=!busy) { ChatIcon("close","Remover ${draft.name}") }
                                }
                            }
                        }
                    }
                }
                TextField(
                    value=input,onValueChange=vm::onInput,
                    modifier=Modifier.fillMaxWidth(),
                    placeholder={Text(stringResource(R.string.punk_chat_placeholder))},
                    maxLines=if(compact)2 else 6,
                    colors=TextFieldDefaults.colors(
                        focusedContainerColor=MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor=MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent
                    ),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                    keyboardActions=KeyboardActions(onSend={if(!busy)vm.send()})
                )
                Row(Modifier.fillMaxWidth().padding(start=4.dp,end=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onPickAttachment,enabled=attachments.size<5 && !busy) { ChatIcon("attach",stringResource(R.string.punk_chat_attach)) }
                    Text(if(attachments.isEmpty())stringResource(R.string.punk_chat_text_voice) else "${attachments.size}/5 anexos",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f).padding(start=4.dp))
                    IconButton(onClick=onVoice,enabled=!busy) { ChatIcon("mic",stringResource(R.string.punk_chat_voice)) }
                    FilledIconButton(
                        onClick={if(busy)vm.stop() else vm.send()},
                        enabled=busy || input.isNotBlank() || attachments.isNotEmpty(),
                        modifier=Modifier.size(48.dp),
                        shape=CircleShape
                    ) { ChatIcon(if(busy)"stop" else "send",if(busy)stringResource(R.string.punk_chat_interrupt) else stringResource(R.string.punk_chat_send)) }
                }
            }
        }
    }
    if(history) {
        ModalBottomSheet(onDismissRequest={history=false},containerColor=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=24.dp)) {
                Text(stringResource(R.string.punk_chat_your_conversations),style=MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.punk_chat_resume),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp,bottom=16.dp))
                Button(onClick={vm.newConversation();history=false},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { ChatIcon("plus");Spacer(Modifier.width(8.dp));Text(stringResource(R.string.punk_chat_new)) }
                if(visibleMessages.isNotEmpty())TextButton(onClick={vm.exportConversation()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { ChatIcon("copy");Spacer(Modifier.width(8.dp));Text(stringResource(R.string.punk_chat_export)) }
                LazyColumn(Modifier.heightIn(max=420.dp).padding(vertical=16.dp)) {
                    if(conversations.isEmpty())item { Text(stringResource(R.string.punk_chat_history_empty),modifier=Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(conversations,key={it.id}) { conversation ->
                        Surface(onClick={vm.openConversation(conversation.id);history=false},enabled=!busy,color=if(conversation.id==conversationId)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                ChatIcon("chat",tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(conversation.title.ifBlank{"Conversa com Punk"},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                    Text(SimpleDateFormat("dd MMM · HH:mm",Locale("pt","BR")).format(Date(conversation.createdAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Suggestion(title:String,prompt:String,onInput:(String)->Unit) {
    Surface(onClick={onInput(prompt)},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min=58.dp).padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
            ChatIcon("arrow",tint=MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MessageBubble(message:ChatMessage,onRetry:()->Unit,canRetry:Boolean,onOpenAttachment:(String,String,String)->Unit) {
    val user=message.role=="user"
    val clipboard=LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(start=if(user)30.dp else 0.dp,end=if(user)0.dp else 12.dp),horizontalAlignment=if(user)Alignment.End else Alignment.Start,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(!user)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            BrandMark(Modifier.size(26.dp))
            Text(stringResource(R.string.punk_chat_name),style=MaterialTheme.typography.titleSmall)
        }
        Surface(color=if(user)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp,bottomStart=if(user)20.dp else 5.dp,bottomEnd=if(user)5.dp else 20.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(message.text.isNotBlank())RichMessageText(message.text)
                val files=remember(message.attachmentsJson) {
                    runCatching { Json.parseToJsonElement(message.attachmentsJson).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
                }
                files.forEach { file ->
                    val name=file["name"]?.jsonPrimitive?.content ?: "Arquivo"
                    val id=file["uploadId"]?.jsonPrimitive?.content.orEmpty()
                    val mime=file["mime"]?.jsonPrimitive?.content ?: "application/octet-stream"
                    TextButton(onClick={onOpenAttachment(id,name,mime)},enabled=id.isNotBlank(),contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.heightIn(min=48.dp)) {
                        ChatIcon("attach");Spacer(Modifier.width(8.dp));Text(name,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(message.ts)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(user) {
                val label=when(message.status) {
                    "pending","queued" -> stringResource(R.string.punk_chat_queued)
                    "sending" -> stringResource(R.string.punk_chat_sending)
                    "failed","error" -> stringResource(R.string.punk_chat_send_failed)
                    "cancelled","interrupted" -> stringResource(R.string.punk_chat_cancelled)
                    else -> stringResource(R.string.punk_chat_sent)
                }
                Text(label,style=MaterialTheme.typography.labelSmall,color=if(message.status in listOf("failed","error"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if(message.status in listOf("interrupted","cancelled"))Text(stringResource(R.string.punk_chat_cancelled),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(message.text.isNotEmpty())IconButton(onClick={clipboard.setText(AnnotatedString(message.text))},modifier=Modifier.size(48.dp)) { ChatIcon("copy",stringResource(R.string.punk_chat_copy),modifier=Modifier.size(18.dp)) }
            }
            if(message.status in listOf("failed","error","interrupted"))TextButton(onClick=onRetry,enabled=canRetry) { Text(stringResource(R.string.punk_chat_retry)) }
        }
    }
}

@Composable
private fun RichMessageText(text:String) {
    SelectionContainer {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            text.split("```").forEachIndexed { index, part ->
                if(part.isNotBlank()) {
                    if(index%2==1)Surface(color=MaterialTheme.colorScheme.background,shape=RoundedCornerShape(12.dp)) {
                        val raw=part.trim()
                        val first=raw.substringBefore('\n')
                        val code=if(raw.contains('\n') && first.matches(Regex("[A-Za-z0-9+#._-]{1,20}")))raw.substringAfter('\n') else raw
                        Text(code,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,modifier=Modifier.horizontalScroll(rememberScrollState()).padding(14.dp))
                    } else Text(part.trim(),style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun BrandMark(modifier:Modifier=Modifier.size(36.dp)) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val s=size.minDimension
        drawPath(Path().apply { moveTo(s*.24f,s*.77f);lineTo(s*.24f,s*.24f);lineTo(s*.58f,s*.24f);lineTo(s*.79f,s*.43f);lineTo(s*.58f,s*.62f);lineTo(s*.42f,s*.62f) },color,style=Stroke(s*.075f,cap=StrokeCap.Round))
        drawCircle(color,s*.035f,Offset(s*.77f,s*.76f))
    }
}

@Composable
private fun AssistantCore() {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(100.dp)) {
        val c=Offset(size.width/2,size.height/2)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.16f),androidx.compose.ui.graphics.Color.Transparent)),size.minDimension/2,c)
        drawCircle(color.copy(alpha=.12f),size.minDimension*.34f,c,style=Stroke(1.2f))
        drawArc(color,-70f,235f,false,Offset(size.width*.25f,size.height*.25f),Size(size.width*.5f,size.height*.5f),style=Stroke(2.5f,cap=StrokeCap.Round))
        drawCircle(color,size.minDimension*.07f,c)
    }
}

@Composable
private fun ChatIcon(name:String,description:String?=null,tint:androidx.compose.ui.graphics.Color=LocalContentColor.current,modifier:Modifier=Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(24.dp).then(if(description!=null)Modifier.semanticsDescription(description) else Modifier)) {
        withTransform({scale(size.width/24f,size.height/24f,Offset.Zero)}) {
            val st=Stroke(1.7f,cap=StrokeCap.Round)
            fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(tint,Offset(x,y),Offset(xx,yy),1.7f,StrokeCap.Round)
            fun path(vararg pts:Float) { val p=Path();p.moveTo(pts[0],pts[1]);for(i in 2 until pts.size step 2)p.lineTo(pts[i],pts[i+1]);drawPath(p,tint,style=st) }
            fun rect(x:Float,y:Float,w:Float,h:Float,r:Float=2f)=drawRoundRect(tint,Offset(x,y),Size(w,h),androidx.compose.ui.geometry.CornerRadius(r,r),style=st)
            when(name) {
                "history"->{drawArc(tint,-140f,285f,false,Offset(4f,4f),Size(16f,16f),style=st);path(3f,3f,3f,9f,8f,9f);path(12f,7f,12f,12f,15f,14f)}
                "plus"->{line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
                "close"->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                "settings"->{drawCircle(tint,8f,Offset(12f,12f),style=st);drawCircle(tint,3f,Offset(12f,12f),style=st);line(12f,1f,12f,4f);line(12f,20f,12f,23f);line(1f,12f,4f,12f);line(20f,12f,23f,12f)}
                "mic"->{rect(9f,3f,6f,12f,3f);drawArc(tint,0f,180f,false,Offset(6f,7f),Size(12f,12f),style=st);line(12f,19f,12f,22f);line(8f,22f,16f,22f)}
                "send"->{path(4f,4f,21f,12f,4f,20f,7f,12f,4f,4f);line(7f,12f,15f,12f)}
                "stop"->rect(5f,5f,14f,14f,3f)
                "attach"->{path(9f,17f,17f,9f,17f,6f,14f,5f,6f,13f,5f,17f,8f,21f,12f,21f,21f,12f)}
                "copy"->{rect(8f,7f,12f,14f);path(16f,7f,16f,3f,4f,3f,4f,17f,8f,17f)}
                "link"->{rect(3f,5f,11f,7f,3.5f);rect(10f,12f,11f,7f,3.5f);line(9f,9f,15f,15f)}
                "chevron"->path(9f,6f,15f,12f,9f,18f)
                "arrow"->{line(5f,17f,18f,5f);path(7f,5f,18f,5f,18f,16f)}
                else->{rect(3f,3f,18f,15f,4f);path(8f,18f,5f,21f,5f,17f);line(7f,8f,17f,8f);line(7f,12f,13f,12f)}
            }
        }
    }
}

private fun Modifier.semanticsDescription(description:String):Modifier = this.then(Modifier.semantics { contentDescription = description })
