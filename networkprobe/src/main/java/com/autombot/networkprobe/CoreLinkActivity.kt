package com.autombot.networkprobe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class CoreLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileStore = CoreProfileStore(applicationContext)
        val tokenStore = SecureProbeTokenStore(applicationContext)
        val syncClient = AutomCoreSyncClient()

        setContent {
            MaterialTheme {
                CoreLinkScreen(
                    profileStore = profileStore,
                    tokenStore = tokenStore,
                    syncClient = syncClient,
                    onTestSaved = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(EXTRA_USE_SAVED_CORE_PROFILE, true)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CoreLinkScreen(
    profileStore: CoreProfileStore,
    tokenStore: SecureProbeTokenStore,
    syncClient: AutomCoreSyncClient,
    onTestSaved: () -> Unit
) {
    var managerUrl by remember {
        mutableStateOf(profileStore.managerUrl().ifBlank { "manager.infinitenet.net" })
    }
    var adminToken by remember { mutableStateOf("") }
    var savedProfile by remember { mutableStateOf(profileStore.loadProfile()) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshWithSavedToken() {
        val scoped = tokenStore.load()
        if (scoped.isNullOrBlank()) {
            success = false
            message = "Não existe token de diagnóstico salvo. Faça o vínculo novamente usando o token da VPS."
            return
        }
        running = true
        message = null
        scope.launch {
            runCatching {
                val normalized = AutomCoreSyncClient.normalizeBaseUrl(managerUrl)
                val json = syncClient.refresh(normalized, scoped)
                profileStore.saveProfile(normalized, json)
            }.onSuccess { profile ->
                savedProfile = profile
                success = true
                message = "Perfil atualizado e salvo no aparelho. Agora você pode trocar para 4G/5G e testar sem consultar o Manager."
            }.onFailure { error ->
                success = false
                message = error.message ?: error.javaClass.simpleName
            }
            running = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = LinkBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LinkCard {
                    Text("Vincular AutomBot Core", color = LinkText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sincronize a configuração da VPS uma vez e mantenha uma cópia local para testar depois no Wi‑Fi, 4G ou 5G.",
                        color = LinkDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            item {
                LinkCard {
                    Text("Plataforma / API", color = LinkText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LinkField(
                        value = managerUrl,
                        onValueChange = { managerUrl = it; message = null },
                        label = "Domínio da plataforma"
                    )
                    Spacer(Modifier.height(10.dp))
                    LinkField(
                        value = adminToken,
                        onValueChange = { adminToken = it; message = null },
                        label = "Token da VPS — usado somente no vínculo",
                        password = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "O token administrativo não é salvo. O Core emite um token somente-leitura (probe:read), que fica cifrado pelo Android Keystore.",
                        color = LinkDim,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            message?.let { current ->
                item {
                    Text(
                        current,
                        color = if (success) LinkPass else LinkFail,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (managerUrl.isBlank() || adminToken.isBlank()) {
                            success = false
                            message = "Informe o domínio da plataforma e o token da VPS."
                        } else {
                            running = true
                            message = null
                            scope.launch {
                                runCatching {
                                    val normalized = AutomCoreSyncClient.normalizeBaseUrl(managerUrl)
                                    val result = syncClient.bootstrap(normalized, adminToken)
                                    tokenStore.save(result.probeToken)
                                    profileStore.saveProfile(normalized, result.profileJson)
                                }.onSuccess { profile ->
                                    managerUrl = profile.managerUrl
                                    savedProfile = profile
                                    adminToken = ""
                                    success = true
                                    message = "Vínculo concluído. ${profile.protocols.size} configurações de protocolo foram salvas localmente."
                                }.onFailure { error ->
                                    success = false
                                    message = error.message ?: error.javaClass.simpleName
                                }
                                running = false
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LinkAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Vincular e salvar configurações", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            savedProfile?.let { profile ->
                item {
                    LinkCard {
                        Text("Perfil salvo no aparelho", color = LinkText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(profile.profileName, color = LinkText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Versão: ${profile.profileVersion}", color = LinkDim, fontSize = 11.sp)
                        Text("Protocolos/configurações: ${profile.protocols.size}", color = LinkDim, fontSize = 11.sp)
                        profile.publicIp?.let { Text("IP público: $it", color = LinkDim, fontSize = 11.sp) }
                        Text(
                            "Salvo: ${DateFormat.getDateTimeInstance().format(Date(profile.savedAtMs))}",
                            color = LinkDim,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(9.dp))
                        profile.protocols.forEach { protocol ->
                            Text(
                                "• ${protocol.type}: ${protocol.host}:${protocol.ports.joinToString()} · ${protocol.transport}",
                                color = LinkDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = onTestSaved,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LinkAccent)
                    ) {
                        Text("Testar configuração salva nesta rede", fontWeight = FontWeight.SemiBold)
                    }
                }

                item {
                    Button(
                        onClick = { refreshWithSavedToken() },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LinkSurfaceAlt)
                    ) {
                        Text("Sincronizar usando vínculo salvo", color = LinkText)
                    }
                }

                item {
                    Button(
                        onClick = {
                            profileStore.clearProfile()
                            tokenStore.clear()
                            savedProfile = null
                            adminToken = ""
                            success = true
                            message = "Vínculo local removido deste aparelho."
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LinkSurfaceAlt)
                    ) {
                        Text("Remover vínculo local", color = LinkDim)
                    }
                }
            }

            item {
                Text(
                    "O perfil salvo contém somente endpoints, portas, transportes, paths e dados de diagnóstico. Não contém token administrativo nem credenciais de clientes.",
                    color = LinkDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun LinkCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LinkSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

@Composable
private fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LinkText,
            unfocusedTextColor = LinkText,
            focusedBorderColor = LinkAccent,
            unfocusedBorderColor = LinkLine,
            focusedLabelColor = LinkAccent,
            unfocusedLabelColor = LinkDim,
            cursorColor = LinkAccent
        )
    )
}

private val LinkBackground = Color(0xFF120E1B)
private val LinkSurface = Color(0xFF1C1628)
private val LinkSurfaceAlt = Color(0xFF292039)
private val LinkAccent = Color(0xFF8B5CF6)
private val LinkText = Color(0xFFF5F2FA)
private val LinkDim = Color(0xFFAAA1B9)
private val LinkLine = Color(0xFF3A3049)
private val LinkPass = Color(0xFF4ADE80)
private val LinkFail = Color(0xFFF87171)
