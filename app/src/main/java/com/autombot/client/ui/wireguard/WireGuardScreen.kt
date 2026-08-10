package com.autombot.client.ui.wireguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.wireguard.ManagedTunnel
import com.autombot.client.protocols.wireguard.TunnelStatus
import com.autombot.client.protocols.wireguard.WireGuardManager
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

@Composable
fun WireGuardScreen(
    manager: WireGuardManager,
    onBack: () -> Unit,
    onRequestVpnPermission: (onGranted: () -> Unit) -> Unit,
    onPickConfigFile: (onText: (fileName: String, text: String) -> Unit) -> Unit,
    onViewLog: (String) -> Unit
) {
    val tunnels by manager.tunnels.collectAsState()
    val managedMode = rememberManagedMode()
    var showImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(C.Background)) {
        TopBar("WireGuard", onBack)
        if (tunnels.isEmpty()) {
            EmptyState(allowAdd = !managedMode, onAddClick = { showImport = true })
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tunnels, key = { it.name }) { tunnel ->
                    TunnelCard(
                        tunnel = tunnel,
                        allowDelete = !managedMode,
                        onToggle = { onRequestVpnPermission { scope.launch { manager.toggle(tunnel) } } },
                        onRefreshStats = { manager.refreshStatistics(tunnel) },
                        onViewLog = { onViewLog(tunnel.name) },
                        onDelete = { manager.removeTunnel(tunnel.name) }
                    )
                }
                if (!managedMode) item { AddTunnelRow { showImport = true } }
            }
        }
    }

    if (!managedMode && showImport) ImportTunnelSheet(
        onDismiss = { showImport = false },
        onPickFile = onPickConfigFile,
        onConfirm = { name, text -> manager.importConfig(name, text); showImport = false }
    )
}

@Composable private fun TopBar(title:String,onBack:()->Unit){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Voltar",tint=C.Text)};Text(title,color=C.Text,fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=4.dp))}}

@Composable private fun EmptyState(allowAdd:Boolean,onAddClick:()->Unit){
    Column(Modifier.fillMaxSize().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text("Nenhum túnel configurado",color=C.Text,fontSize=16.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp))
        Text(if(allowAdd)"Importe uma configuração WireGuard (.conf) colando o texto ou selecionando o arquivo." else "A configuração WireGuard é fornecida pelo administrador do painel.",color=C.TextDim,fontSize=13.sp)
        if(allowAdd){Spacer(Modifier.height(20.dp));Button(onClick=onAddClick,colors=ButtonDefaults.buttonColors(containerColor=C.Primary,contentColor=C.OnPrimary)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("Adicionar túnel")}}
    }
}

@Composable private fun AddTunnelRow(onClick:()->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).border(1.dp,C.Line,RoundedCornerShape(14.dp)).clickable(onClick=onClick).padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Add,null,tint=C.Primary);Spacer(Modifier.width(10.dp));Text("Adicionar túnel",color=C.Text,fontSize=14.sp,fontWeight=FontWeight.Medium)}}

@Composable private fun TunnelCard(tunnel:ManagedTunnel,allowDelete:Boolean,onToggle:()->Unit,onRefreshStats:()->Unit,onViewLog:()->Unit,onDelete:()->Unit){
    LaunchedEffect(tunnel.status){if(tunnel.status==TunnelStatus.CONNECTED)onRefreshStats()}
    var confirm by remember{mutableStateOf(false)}
    val connected=tunnel.state==Tunnel.State.UP;val busy=tunnel.status==TunnelStatus.CONNECTING||tunnel.status==TunnelStatus.DISCONNECTING
    val(label,color)=when(tunnel.status){TunnelStatus.CONNECTED->"Conectado" to C.Green;TunnelStatus.CONNECTING->"Conectando…" to C.Primary;TunnelStatus.DISCONNECTING->"Desconectando…" to C.Primary;TunnelStatus.ERROR->"Erro" to C.Red;TunnelStatus.DISCONNECTED->"Desconectado" to C.TextDim}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp,if(connected)C.Green.copy(alpha=0.35f)else C.Line,RoundedCornerShape(16.dp)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tunnel.name,color=C.Text,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text(tunnel.endpointLabel,color=C.TextDim,fontSize=12.sp)};if(busy){CircularProgressIndicator(color=C.Primary,strokeWidth=2.dp,modifier=Modifier.size(22.dp));Spacer(Modifier.width(12.dp))};Switch(checked=connected,onCheckedChange={onToggle()},enabled=!busy)}
        Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(color));Spacer(Modifier.width(6.dp));Text(label,color=color,fontSize=12.sp)}
        if(connected){Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Chip("↓ Recebido",fmt(tunnel.rxBytes));Chip("↑ Enviado",fmt(tunnel.txBytes))}}
        if(tunnel.status==TunnelStatus.ERROR&&tunnel.lastError!=null){Spacer(Modifier.height(8.dp));Text(tunnel.lastError,color=C.Red,fontSize=11.sp)}
        Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Text("Ver log",color=C.TextDim,fontSize=11.sp,modifier=Modifier.clickable(onClick=onViewLog));if(allowDelete)Text("Excluir",color=C.Red,fontSize=11.sp,modifier=Modifier.clickable{confirm=true})}
    }
    if(allowDelete&&confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Excluir túnel?")},text={Text("\"${tunnel.name}\" vai ser removido.")},confirmButton={TextButton(onClick={confirm=false;onDelete()}){Text("Excluir",color=C.Red)}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancelar")}},containerColor=C.Surface,titleContentColor=C.Text,textContentColor=C.TextDim)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ImportTunnelSheet(onDismiss:()->Unit,onPickFile:(onText:(String,String)->Unit)->Unit,onConfirm:(String,String)->Unit){
    var name by remember{mutableStateOf("")};var configText by remember{mutableStateOf("")}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=C.Surface){Column(Modifier.fillMaxWidth().padding(20.dp)){
        Text("Novo túnel WireGuard",color=C.Text,fontSize=17.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(16.dp))
        OutlinedTextField(value=name,onValueChange={name=it},label={Text("Nome do túnel (opcional)")},singleLine=true,modifier=Modifier.fillMaxWidth(),colors=fieldColors());Spacer(Modifier.height(12.dp))
        OutlinedTextField(value=configText,onValueChange={configText=it},label={Text("Cole a config (.conf) ou o JSON do painel")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().height(160.dp),colors=fieldColors());Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick={onPickFile{file,text->if(name.isBlank())name=file.removeSuffix(".conf");configText=text}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.FileUpload,null);Spacer(Modifier.width(8.dp));Text("Selecionar arquivo .conf")};Spacer(Modifier.height(18.dp))
        Button(onClick={onConfirm(name,configText)},enabled=configText.isNotBlank(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=C.Primary,contentColor=C.OnPrimary)){Text("Importar e salvar")};Spacer(Modifier.height(12.dp))
    }}
}
@Composable private fun fieldColors()=OutlinedTextFieldDefaults.colors(focusedTextColor=C.Text,unfocusedTextColor=C.Text,focusedBorderColor=C.Primary,unfocusedBorderColor=C.Line,focusedLabelColor=C.Primary,unfocusedLabelColor=C.TextDim,cursorColor=C.Primary)
@Composable private fun Chip(l:String,v:String){Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.SurfaceAlt).padding(horizontal=12.dp,vertical=8.dp)){Text(l,color=C.TextDim,fontSize=10.sp);Text(v,color=C.Accent,fontSize=13.sp,fontWeight=FontWeight.Medium)}}
private fun fmt(b:Long):String{if(b<=0)return"0 B";val u=arrayOf("B","KB","MB","GB","TB");val g=(Math.log10(b.toDouble())/Math.log10(1024.0)).toInt().coerceIn(0,u.lastIndex);return String.format("%.1f %s",b/Math.pow(1024.0,g.toDouble()),u[g])}
