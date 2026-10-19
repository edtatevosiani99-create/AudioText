package com.audiotext.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileInputStream
import java.util.Locale

private val Ink = Color(0xFF17233B)
private val Blue = Color(0xFF356AE6)
private val CanvasColor = Color(0xFFF5F7FB)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready by mutableStateOf(false)
    private var voiceList by mutableStateOf<List<Voice>>(emptyList())
    private var status by mutableStateOf("Preparing voices…")
    private var saveCallback: ((String) -> Unit)? = null
    private var pendingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            AudioTextScreen(
                isReady = ready,
                voices = voiceList,
                status = status,
                onSpeak = { text, voice, speed ->
                    val engine = tts
                    if (engine == null || !ready) {
                        status = "Text-to-speech is not ready yet."
                    } else if (text.isBlank()) {
                        status = "Enter some text first."
                    } else {
                        voice?.let { engine.voice = it }
                        engine.setSpeechRate(speed)
                        val maxPart = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3500)
                        val parts = text.chunked(maxPart)
                        parts.forEachIndexed { index, part ->
                            engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "play-$index")
                        }
                        status = "Speaking…"
                        saveHistory(text)
                    }
                },
                onStop = {
                    tts?.stop()
                    status = "Playback stopped."
                },
                onSave = { text, voice, speed, done ->
                    val engine = tts
                    if (engine == null || !ready || text.isBlank()) {
                        done("Enter text and wait for voices to load.")
                    } else if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
                        done("This voice exports up to ${TextToSpeech.getMaxSpeechInputLength()} characters per file. Shorten the text and try again.")
                    } else {
                        engine.stop()
                        voice?.let { engine.voice = it }
                        engine.setSpeechRate(speed)
                        val output = File(cacheDir, "audiotext-${System.currentTimeMillis()}.wav")
                        pendingFile = output
                        saveCallback = done
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) {
                                if (utteranceId != "save-audio") return
                                runOnUiThread {
                                    try {
                                        val values = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, "AudioText-${System.currentTimeMillis()}.wav")
                                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AudioText")
                                        }
                                        val uri: Uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                            ?: throw IllegalStateException("Could not create audio file")
                                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                                            FileInputStream(output).use { input -> input.copyTo(outputStream) }
                                        } ?: throw IllegalStateException("Could not write audio file")
                                        saveHistory(text)
                                        saveCallback?.invoke("Saved to Music/AudioText as WAV audio.")
                                    } catch (error: Exception) {
                                        saveCallback?.invoke("Save failed: ${error.localizedMessage ?: "unknown error"}")
                                    } finally {
                                        output.delete()
                                        pendingFile = null
                                        saveCallback = null
                                    }
                                }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                runOnUiThread {
                                    saveCallback?.invoke("The installed voice could not create an audio file.")
                                    pendingFile?.delete()
                                    pendingFile = null
                                    saveCallback = null
                                }
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                        })
                        val result = engine.synthesizeToFile(text, Bundle(), output, "save-audio")
                        if (result == TextToSpeech.ERROR) {
                            output.delete()
                            pendingFile = null
                            saveCallback = null
                            done("Audio export is not supported by the current TTS engine.")
                        } else {
                            status = "Creating audio file…"
                        }
                    }
                }
            )
        }
    }

    private fun saveHistory(text: String) {
        val prefs = getSharedPreferences("history", MODE_PRIVATE)
        val old = prefs.getString("items", "") ?: ""
        val entries = (listOf(text.replace("\n", " ").take(500)) +
            old.split("\n---\n").filter { it.isNotBlank() }).distinct().take(20)
        prefs.edit().putString("items", entries.joinToString("\n---\n")).apply()
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            ready = true
            voiceList = tts?.voices?.sortedWith(
                compareBy<Voice> { it.locale.displayLanguage }.thenBy { it.name }
            ) ?: emptyList()
            status = if (voiceList.isEmpty()) "Ready. Check your phone's TTS voice data." else "Ready to speak."
        } else {
            status = "Could not start Android Text-to-Speech."
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun AudioTextScreen(
        isReady: Boolean,
        voices: List<Voice>,
        status: String,
        onSpeak: (String, Voice?, Float) -> Unit,
        onStop: () -> Unit,
        onSave: (String, Voice?, Float, (String) -> Unit) -> Unit
    ) {
        var text by remember { mutableStateOf("") }
        var speed by remember { mutableFloatStateOf(1f) }
        var selectedVoice by remember { mutableStateOf<Voice?>(null) }
        var voiceMenu by remember { mutableStateOf(false) }
        var savedStatus by remember { mutableStateOf("") }
        var history by remember { mutableStateOf(getSharedPreferences("history", MODE_PRIVATE).getString("items", "") ?: "") }
        val historyItems = history.split("\n---\n").filter { it.isNotBlank() }

        MaterialTheme(colorScheme = lightColorScheme(
            primary = Blue, onPrimary = Color.White, background = CanvasColor,
            surface = Color.White, onSurface = Ink, onBackground = Ink
        )) {
            Surface(modifier = Modifier.fillMaxSize(), color = CanvasColor) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(16.dp), color = Blue, modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AudioText", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text("Turn your words into audio", color = Color(0xFF6D7890), fontSize = 14.sp)
                        }
                    }

                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Your text", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it; savedStatus = "" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
                                placeholder = { Text("Paste or type something to read aloud…") },
                                shape = RoundedCornerShape(14.dp)
                            )
                            Text("${text.length} characters", color = Color(0xFF7B8497), fontSize = 12.sp)
                        }
                    }

                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Voice settings", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Box {
                                OutlinedButton(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(selectedVoice?.let { "${it.locale.displayLanguage} · ${it.name}" } ?: "Choose installed voice", maxLines = 1)
                                }
                                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                                    voices.forEach { voice ->
                                        DropdownMenuItem(
                                            text = { Text("${voice.locale.displayLanguage} · ${voice.name}", maxLines = 2) },
                                            onClick = { selectedVoice = voice; voiceMenu = false }
                                        )
                                    }
                                }
                            }
                            Text("Speech speed: ${String.format(Locale.US, "%.1f", speed)}×", color = Ink)
                            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f)
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Slow", color = Color.Gray, fontSize = 12.sp)
                                Text("Normal", color = Color.Gray, fontSize = 12.sp)
                                Text("Fast", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            onSpeak(text, selectedVoice, speed)
                            savedStatus = ""
                            history = getSharedPreferences("history", MODE_PRIVATE).getString("items", "") ?: ""
                        },
                        enabled = isReady && text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Listen", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                        Button(
                            onClick = {
                                savedStatus = "Preparing audio…"
                                onSave(text, selectedVoice, speed) {
                                    savedStatus = it
                                    history = getSharedPreferences("history", MODE_PRIVATE).getString("items", "") ?: ""
                                }
                            },
                            enabled = isReady && text.isNotBlank(),
                            modifier = Modifier.weight(1.5f).height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save audio")
                        }
                    }
                    Text(savedStatus.ifBlank { status }, color = if (savedStatus.startsWith("Save failed")) Color(0xFFB3261E) else Color(0xFF52617A), fontSize = 13.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = Blue)
                        Spacer(Modifier.width(8.dp))
                        Text("Recent texts", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    if (historyItems.isEmpty()) {
                        Text("Your recent texts will appear here.", color = Color.Gray)
                    } else {
                        historyItems.forEach { item ->
                            Card(
                                onClick = { text = item; savedStatus = "" },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Text(item, modifier = Modifier.fillMaxWidth().padding(14.dp), maxLines = 3, color = Ink)
                            }
                        }
                    }
                    Text("Uses voices installed on your device. Voice availability and audio export depend on your TTS engine.", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}
