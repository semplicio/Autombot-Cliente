package com.autombot.client.ui.openvpn

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
import com.autombot.client.protocols.openvpn.ManagedOpenVpnConnection
import com.autombot.client.protocols.openvpn.OpenVpnStatus
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun OpenVpnScreen(
    manager: OpenVpnTunnelManager,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onConnect: (config: com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) -> Unit,
    onDisconnect: () -> Unit
) {
    val connections by manager.connections.collectAsState()
    val managedMode = rememberManagedMode()
    Column(Modifier.fillMaxSize().background(C.Background)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Voltar",tint=C.Text)}; Text("OpenVPN",color=C.Text,fontSize=17.sp,fontWeight=FontWeight.SemiBold) }
        Column(Modifier.padding(horizontal=20.dp).clip(RoundedCornerShape(12.dp)).background(C.SurfaceAlt).padding(12.dp)) {
            Text(if(managedMode) "Perfil .ovpn fornecido e atualizado pelo administrador do painel." else "Importe um arquivo .ovpn com certificados embutidos. O OpenVPN assume a VPN do sistema inteira.", color=C.TextDim,fontSize=11.sp)
        }
        Spacer(Modifier.height(12.dp))
        if(connections.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Text("Nenhuma conexão OpenVPN configurada",color=C.TextDim,fontSize=13.sp)
            if(!managedMode){Spacer(Modifier.height(16.dp));Button(onClick=onAddProfile,colors=ButtonDefaults.buttonColors(containerColor=C.Accent,contentColor=C.OnPrimary)){Text("Importar .ovpn")}}
        } else LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(connections,key={it.config.connectionName}){conn-> OpenVpnCard(conn,!managedMode,onToggle={
                if(conn.status==OpenVpnStatus.CONNECTED){manager.requestDisconnect(conn.config.connectionName);onDisconnect()}else onConnect(conn.config)
            },onDelete={manager.removeProfile(conn.config.connectionName)})}
            if(!managedMode)item{Button(onClick=onAddProfile,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=C.Accent,contentColor=C.OnPrimary)){Text("Importar .ovpn")};Spacer(Modifier.height(12.dp))}
        }
    }
}

@Composable private fun OpenVpnCard(conn:ManagedOpenVpnConnection,allowDelete:Boolean,onToggle:()->Unit,onDelete:()->Unit){
    var confirm by remember{mutableStateOf(false)};val busy=conn.status==OpenVpnStatus.CONNECTING
    val(label,color)=when(conn.status){OpenVpnStatus.CONNECTED->"Conectado" to C.Green;OpenVpnStatus.CONNECTING->"Conectando…" to C.Accent;OpenVpnStatus.ERROR->"Erro" to C.Red;OpenVpnStatus.DISCONNECTED->"Desconectado" to C.TextDim}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp,if(conn.status==OpenVpnStatus.CONNECTED)C.Green.copy(alpha=0.35f)else C.Line,RoundedCornerShape(16.dp)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(conn.config.connectionName,color=C.Text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text("Arquivo .ovpn importado",color=C.TextDim,fontSize=12.sp)};if(busy){CircularProgressIndicator(color=C.Accent,strokeWidth=2.dp,modifier=Modifier.size(22.dp));Spacer(Modifier.width(12.dp))};Switch(checked=conn.status==OpenVpnStatus.CONNECTED,onCheckedChange={onToggle()},enabled=!busy)}
        Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(color));Spacer(Modifier.width(6.dp));Text(label,color=color,fontSize=12.sp)}
        if(conn.status==OpenVpnStatus.CONNECTED){Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Chip("↓ Recebido",fmt(conn.rxBytes));Chip("↑ Enviado",fmt(conn.txBytes))}}
        if(conn.status==OpenVpnStatus.ERROR&&conn.lastError!=null){Spacer(Modifier.height(8.dp));Text(conn.lastError,color=C.Red,fontSize=11.sp)}
        if(allowDelete){Spacer(Modifier.height(10.dp));Text("Excluir",color=C.Red,fontSize=11.sp,fontWeight=FontWeight.Medium,modifier=Modifier.clickable{confirm=true})}
    }
    if(allowDelete&&confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Excluir conexão?")},text={Text("\"${conn.config.connectionName}\" e o arquivo .ovpn serão removidos.")},confirmButton={TextButton(onClick={confirm=false;onDelete()}){Text("Excluir",color=C.Red)}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancelar")}},containerColor=C.Surface,titleContentColor=C.Text,textContentColor=C.TextDim)
}
@Composable private fun Chip(l:String,v:String){Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.SurfaceAlt).padding(horizontal=12.dp,vertical=8.dp)){Text(l,color=C.TextDim,fontSize=10.sp);Text(v,color=C.Accent,fontSize=13.sp,fontWeight=FontWeight.Medium)}}
private fun fmt(b:Long):String{if(b<=0)return"0 B";val u=arrayOf("B","KB","MB","GB","TB");val g=(Math.log10(b.toDouble())/Math.log10(1024.0)).toInt().coerceIn(0,u.lastIndex);return String.format("%.1f %s",b/Math.pow(1024.0,g.toDouble()),u[g])}
