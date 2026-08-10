package com.autombot.client.ui.vmess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.vmess.ManagedVmessConnection
import com.autombot.client.protocols.vmess.VmessStatus
import com.autombot.client.protocols.vmess.VmessTunnelManager
import com.autombot.client.protocols.vmess.describeTransport
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.launch

@Composable
fun VmessScreen(manager: VmessTunnelManager, onBack: () -> Unit, onAddProfile: () -> Unit, onViewLog: (String) -> Unit) {
    val connections by manager.connections.collectAsState()
    val scope = rememberCoroutineScope()
    val managedMode = rememberManagedMode()
    Column(Modifier.fillMaxSize().background(C.Background)) {
        Row(Modifier.padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Voltar",tint=C.Text)}
            Text("VMess",color=C.Text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
        }
        Column(Modifier.padding(horizontal=20.dp).clip(RoundedCornerShape(12.dp)).background(C.SurfaceAlt).padding(12.dp)) {
            Text(if(managedMode) "Perfil fornecido e atualizado pelo administrador do painel." else "VMess sobre WebSocket (com ou sem TLS). O tráfego é roteado pela VPN do sistema ao conectar.", color=C.TextDim,fontSize=11.sp)
        }
        Spacer(Modifier.height(12.dp))
        if(connections.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                Text("Nenhuma conexão VMess configurada",color=C.TextDim,fontSize=13.sp)
                if(!managedMode){Spacer(Modifier.height(16.dp));Button(onClick=onAddProfile,colors=ButtonDefaults.buttonColors(containerColor=C.Accent,contentColor=C.OnPrimary)){Text("Colar link vmess://")}}
            }
        } else LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(connections,key={it.config.connectionName}){conn-> VmessCard(conn,!managedMode,
                onToggle={scope.launch{if(conn.status==VmessStatus.CONNECTED)manager.disconnect(conn.config.connectionName) else manager.connect(conn.config.connectionName)}},
                onViewLog={onViewLog(conn.config.connectionName)}, onDelete={manager.removeProfile(conn.config.connectionName)}) }
            if(!managedMode)item{Button(onClick=onAddProfile,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=C.Accent,contentColor=C.OnPrimary)){Text("Colar link vmess://")};Spacer(Modifier.height(12.dp))}
        }
    }
}

@Composable private fun VmessCard(conn:ManagedVmessConnection,allowDelete:Boolean,onToggle:()->Unit,onViewLog:()->Unit,onDelete:()->Unit){
    var confirm by remember{mutableStateOf(false)}; val busy=conn.status==VmessStatus.CONNECTING
    val (label,color)=when(conn.status){VmessStatus.CONNECTED->"Conectado" to C.Green;VmessStatus.CONNECTING->"Conectando…" to C.Accent;VmessStatus.ERROR->"Erro" to C.Red;VmessStatus.DISCONNECTED->"Desconectado" to C.TextDim}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp,if(conn.status==VmessStatus.CONNECTED)C.Green.copy(alpha=.35f)else C.Line,RoundedCornerShape(16.dp)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(conn.config.connectionName,color=C.Text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("${conn.config.server}:${conn.config.port} · ${conn.config.describeTransport()}",color=C.TextDim,fontSize=12.sp)};if(busy){CircularProgressIndicator(color=C.Accent,strokeWidth=2.dp,modifier=Modifier.size(22.dp));Spacer(Modifier.width(12.dp))};Switch(checked=conn.status==VmessStatus.CONNECTED,onCheckedChange={onToggle()},enabled=!busy)}
        Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(color));Spacer(Modifier.width(6.dp));Text(label,color=color,fontSize=12.sp)}
        if(conn.status==VmessStatus.CONNECTED&&conn.localSocksPort!=null){Spacer(Modifier.height(10.dp));Text("SOCKS5 local 127.0.0.1:${conn.localSocksPort}",color=C.Accent,fontSize=11.sp);Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Chip("↓ Recebido",fmt(conn.rxBytes));Chip("↑ Enviado",fmt(conn.txBytes))}}
        if(conn.status==VmessStatus.ERROR&&conn.lastError!=null){Spacer(Modifier.height(8.dp));Text(conn.lastError,color=C.Red,fontSize=11.sp)}
        Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Text("Ver log",color=C.TextDim,fontSize=11.sp,modifier=Modifier.clickable(onClick=onViewLog));if(allowDelete)Text("Excluir",color=C.Red,fontSize=11.sp,modifier=Modifier.clickable{confirm=true})}
    }
    if(allowDelete&&confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Excluir conexão?")},text={Text("\"${conn.config.connectionName}\" vai ser removida.")},confirmButton={TextButton(onClick={confirm=false;onDelete()}){Text("Excluir",color=C.Red)}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancelar")}},containerColor=C.Surface,titleContentColor=C.Text,textContentColor=C.TextDim)
}
@Composable private fun Chip(l:String,v:String){Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.SurfaceAlt).padding(horizontal=12.dp,vertical=8.dp)){Text(l,color=C.TextDim,fontSize=10.sp);Text(v,color=C.Accent,fontSize=13.sp,fontWeight=FontWeight.Medium)}}
private fun fmt(b:Long):String{if(b<=0)return"0 B";val u=arrayOf("B","KB","MB","GB","TB");val g=(Math.log10(b.toDouble())/Math.log10(1024.0)).toInt().coerceIn(0,u.lastIndex);return String.format("%.1f %s",b/Math.pow(1024.0,g.toDouble()),u[g])}
