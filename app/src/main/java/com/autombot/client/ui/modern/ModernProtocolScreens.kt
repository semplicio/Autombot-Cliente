package com.autombot.client.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.modern.ManagedModernConnection
import com.autombot.client.protocols.modern.ModernProtocolStatus
import com.autombot.client.protocols.modern.ModernProtocolTunnelManager
import com.autombot.client.protocols.modern.ModernProtocolType
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.launch

@Composable
fun ModernProtocolScreen(manager: ModernProtocolTunnelManager, type: ModernProtocolType, onBack: () -> Unit, onAddProfile: () -> Unit, onViewLog: (String) -> Unit) {
    val allConnections by manager.connections.collectAsState()
    val connections = allConnections.filter { it.config.type == type }
    val scope = rememberCoroutineScope()
    val coreAvailable = remember { manager.coreAvailable() }
    val managedMode = rememberManagedMode()

    Column(Modifier.fillMaxSize().background(C.Background)) {
        AutomBotTopBar(type.displayName, onBack, "Núcleo sing-box")
        Column(Modifier.padding(horizontal=20.dp,vertical=8.dp).clip(RoundedCornerShape(12.dp)).background(C.SurfaceAlt).padding(12.dp)) {
            Text(
                if(managedMode) "Perfil fornecido e atualizado pelo administrador do painel."
                else if(coreAvailable) "Núcleo moderno disponível. ${type.displayName} usa QUIC/UDP e entrega um proxy SOCKS5 local ao AutomBot."
                else "Núcleo sing-box não está disponível neste APK.",
                color=if(coreAvailable||managedMode)C.TextDim else C.Red,fontSize=11.sp
            )
        }
        if(connections.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Text("Nenhum perfil ${type.displayName} configurado",color=C.TextDim,fontSize=13.sp)
            if(!managedMode){Spacer(Modifier.height(16.dp));AutomBotGradientButton(text="Importar link ${if(type==ModernProtocolType.HYSTERIA2)"hysteria2://" else "tuic://"}",onClick=onAddProfile,modifier=Modifier.fillMaxWidth(),accent=C.Accent)}
        } else LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(connections,key={"${it.config.type.id}:${it.config.connectionName}"}){conn->ModernCard(conn,!managedMode,onToggle={scope.launch{if(conn.status==ModernProtocolStatus.CONNECTED)manager.disconnect(conn.config.type,conn.config.connectionName)else manager.connect(conn.config.type,conn.config.connectionName)}},onViewLog={onViewLog(conn.config.connectionName)},onDelete={manager.removeProfile(conn.config.type,conn.config.connectionName)})}
            if(!managedMode)item{AutomBotGradientButton(text="Importar novo link",onClick=onAddProfile,modifier=Modifier.fillMaxWidth(),accent=C.Accent);Spacer(Modifier.height(12.dp))}
        }
    }
}

@Composable
fun ModernProtocolAddScreen(manager:ModernProtocolTunnelManager,type:ModernProtocolType,onBack:()->Unit,onSaved:()->Unit){
    var uri by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)};val expected=if(type==ModernProtocolType.HYSTERIA2)"hysteria2://" else "tuic://"
    Column(Modifier.fillMaxSize().background(C.Background)){AutomBotTopBar("Adicionar ${type.displayName}",onBack,"Importar configuração");Column(Modifier.weight(1f).padding(horizontal=20.dp,vertical=16.dp)){Text("Link de conexão",color=C.TextDim,fontSize=11.sp);Spacer(Modifier.height(6.dp));OutlinedTextField(value=uri,onValueChange={uri=it;error=null},modifier=Modifier.fillMaxWidth(),minLines=5,maxLines=8,placeholder={Text("$expected…")});error?.let{Spacer(Modifier.height(12.dp));Text(it,color=C.Red,fontSize=11.sp)}};Column(Modifier.padding(20.dp)){AutomBotGradientButton(text="Importar perfil",onClick={runCatching{manager.importUri(uri)}.onSuccess{parsed->if(parsed.type!=type){manager.removeProfile(parsed.type,parsed.connectionName);error="Esse link é ${parsed.type.displayName}, não ${type.displayName}."}else onSaved()}.onFailure{error=it.message?:"Link inválido"}},enabled=uri.trim().startsWith(expected,true)||(type==ModernProtocolType.HYSTERIA2&&uri.trim().startsWith("hy2://",true)),modifier=Modifier.fillMaxWidth(),accent=C.Accent)}}
}

@Composable private fun ModernCard(conn:ManagedModernConnection,allowDelete:Boolean,onToggle:()->Unit,onViewLog:()->Unit,onDelete:()->Unit){
    var confirm by remember{mutableStateOf(false)};val busy=conn.status==ModernProtocolStatus.CONNECTING
    val(label,color)=when(conn.status){ModernProtocolStatus.CONNECTED->"Conectado" to C.Green;ModernProtocolStatus.CONNECTING->"Conectando…" to C.Accent;ModernProtocolStatus.ERROR->"Erro" to C.Red;ModernProtocolStatus.DISCONNECTED->"Desconectado" to C.TextDim}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp,if(conn.status==ModernProtocolStatus.CONNECTED)C.Green.copy(alpha=0.35f)else C.Line,RoundedCornerShape(16.dp)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(conn.config.connectionName,color=C.Text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("${conn.config.server}:${conn.config.port}",color=C.TextDim,fontSize=12.sp)};if(busy){CircularProgressIndicator(color=C.Accent,strokeWidth=2.dp,modifier=Modifier.size(22.dp));Spacer(Modifier.width(12.dp))};Switch(checked=conn.status==ModernProtocolStatus.CONNECTED,onCheckedChange={onToggle()},enabled=!busy)}
        Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(color));Spacer(Modifier.width(6.dp));Text(label,color=color,fontSize=12.sp)}
        if(conn.status==ModernProtocolStatus.CONNECTED&&conn.localSocksPort!=null){Spacer(Modifier.height(10.dp));Text("SOCKS5 local 127.0.0.1:${conn.localSocksPort}",color=C.Accent,fontSize=11.sp);Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Chip("↓ Recebido",fmt(conn.rxBytes));Chip("↑ Enviado",fmt(conn.txBytes))}}
        if(conn.status==ModernProtocolStatus.ERROR&&!conn.lastError.isNullOrBlank()){Spacer(Modifier.height(8.dp));Text(conn.lastError,color=C.Red,fontSize=11.sp)}
        Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Text("Ver log",color=C.TextDim,fontSize=11.sp,modifier=Modifier.clickable(onClick=onViewLog));if(allowDelete)Text("Excluir",color=C.Red,fontSize=11.sp,modifier=Modifier.clickable{confirm=true})}
    }
    if(allowDelete&&confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Excluir conexão?")},text={Text("\"${conn.config.connectionName}\" será removida.")},confirmButton={TextButton(onClick={confirm=false;onDelete()}){Text("Excluir",color=C.Red)}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancelar")}},containerColor=C.Surface,titleContentColor=C.Text,textContentColor=C.TextDim)
}
@Composable private fun Chip(l:String,v:String){Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.SurfaceAlt).padding(horizontal=12.dp,vertical=8.dp)){Text(l,color=C.TextDim,fontSize=10.sp);Text(v,color=C.Accent,fontSize=13.sp,fontWeight=FontWeight.Medium)}}
private fun fmt(b:Long):String{if(b<=0)return"0 B";val u=arrayOf("B","KB","MB","GB","TB");val g=(Math.log10(b.toDouble())/Math.log10(1024.0)).toInt().coerceIn(0,u.lastIndex);return String.format("%.1f %s",b/Math.pow(1024.0,g.toDouble()),u[g])}
