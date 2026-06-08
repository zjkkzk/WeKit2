@file:Suppress("DEPRECATION")

package dev.ujhhgtg.wekit.hooks.items.chat

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.params.OutputConfiguration
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.firstMethod
import java.io.File
import kotlin.io.path.div

@HookItem(
    name = "虚拟视频通话", categories = ["聊天"],
    description = "在微信视频通话相机预览中播放本地视频"
)
object VirtualVoipVideo : ClickableHookItem() {

    private const val TAG = "VirtualVideoHook"
    private const val KEY_VIDEO_PATH = "key_virtual_video_path"

    private val DEFAULT_VIDEO_PATH by lazy {
        KnownPaths.moduleData / "virtual_voip_video.mp4"
    }

    private val CAMERA2_METHODS = setOf(
        "createCaptureSession",
        "createCaptureSessionByOutputConfigurations",
        "createExtensionSession"
    )

    @Volatile
    private var isVoipUiActive = false

    @Volatile
    private var warnedMissingPath: String? = null

    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    private var ownedPlaybackSurface: Surface? = null

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI",
            "com.tencent.mm.plugin.voip.ui.VideoActivity"
        ).forEach { className ->
            className.toClass().apply {
                firstMethod { name = "onCreate" }.hookAfter {
                    isVoipUiActive = true
                    WeLogger.d(TAG, "entered voip ui: $className")
                }
                firstMethod { name = "onDestroy" }.hookBefore {
                    isVoipUiActive = false
                    releasePlayer("left voip ui: $className")
                }
            }
        }

        Camera::class.firstMethod { name = "setPreviewTexture" }.hookBefore {
            if (!shouldInterceptCamera()) return@hookBefore
            val targetTexture = args.firstOrNull() as? SurfaceTexture ?: return@hookBefore
            val uri = getVideoUri()

            args[0] = getDummySurfaceTexture()
            startVirtualVideo(
                surface = Surface(targetTexture),
                videoUri = uri,
                ownsSurface = true
            )
            WeLogger.d(TAG, "camera1 preview texture replaced")
        }

        val cameraDeviceImpl = runCatching {
            "android.hardware.camera2.impl.CameraDeviceImpl".toClass()
        }.getOrNull()

        if (cameraDeviceImpl == null) {
            WeLogger.w(TAG, "CameraDeviceImpl not found, Camera2 hook skipped")
            return
        }

        cameraDeviceImpl.declaredMethods.filter {
            name in CAMERA2_METHODS
        }.forEach { it.hookBefore {
            if (!shouldInterceptCamera()) return@hookBefore
            hijackCamera2Session(this)
        } }
    }

    override fun onDisable() {
        isVoipUiActive = false
        releasePlayer("disabled")
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var uriString by remember { mutableStateOf(WePrefs.getStringOrDef(KEY_VIDEO_PATH, "").trim()) }
            val currentUri = getVideoUri(uriString)
            val fileReadable = isVideoReadable(currentUri, context)

            AlertDialogContent(
                title = { Text("虚拟视频通话") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = uriString,
                            onValueChange = { uriString = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("视频 URI 或路径") },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                TransparentActivity.launch(context) {
                                    val selMediaLauncher = registerForActivityResult(
                                        ActivityResultContracts.PickVisualMedia()
                                    ) { uri ->
                                        finish()
                                        if (uri == null) return@registerForActivityResult

                                        runCatching {
                                            WePrefs.putString(KEY_VIDEO_PATH, uri.toString())
                                            uriString = uri.toString()
                                            showToast(context, "视频文件已成功导入")
                                        }.onFailure { e ->
                                            WeLogger.e(TAG, "Failed to save media URI", e)
                                            showToast(context, "导入视频失败")
                                        }
                                    }

                                    selMediaLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.VideoOnly
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("选择本地视频")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (fileReadable) {
                                "当前文件可读取"
                            } else {
                                "当前文件不可读取; 缺失时会自动放行真实相机"
                            }
                        )
                        Text("默认路径: $DEFAULT_VIDEO_PATH")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        WePrefs.putString(KEY_VIDEO_PATH, uriString)
                        warnedMissingPath = null
                        showToast(context, "已保存，重新进入通话后生效")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private fun hijackCamera2Session(param: XC_MethodHook.MethodHookParam) {
        val uri = getVideoUri()
        for (index in param.args.indices) {
            val arg = param.args[index] ?: continue
            val hijackedArg = when (arg) {
                is List<*> -> hijackSurfaceList(arg)
                else -> hijackSessionConfiguration(arg)
            } ?: continue

            param.args[index] = hijackedArg.replacement
            startVirtualVideo(
                surface = hijackedArg.playbackSurface,
                videoUri = uri,
                ownsSurface = false
            )
            WeLogger.d(TAG, "Camera2 session argument replaced: ${param.method.name}")
            return
        }
    }

    private fun hijackSurfaceList(list: List<*>): HijackedArg? {
        val replacement = ArrayList<Any?>(list.size)
        var playbackSurface: Surface? = null
        var changed = false

        for (item in list) {
            when (item) {
                is Surface -> {
                    if (playbackSurface == null) playbackSurface = item
                    replacement += getDummySurface()
                    changed = true
                }

                is OutputConfiguration -> {
                    val surface = runCatching { item.surface }.getOrNull()
                    if (surface == null) {
                        replacement += item
                    } else {
                        if (playbackSurface == null) playbackSurface = surface
                        replacement += OutputConfiguration(getDummySurface())
                        changed = true
                    }
                }

                else -> replacement += item
            }
        }

        val surface = playbackSurface
        return if (changed && surface != null) HijackedArg(replacement, surface) else null
    }

    private fun hijackSessionConfiguration(config: Any): HijackedArg? {
        val className = config.javaClass.name
        if (!className.endsWith("SessionConfiguration")) return null

        val outputs = runCatching {
            XposedHelpers.callMethod(config, "getOutputConfigurations") as? List<*>
        }.getOrNull() ?: return null

        val hijackedOutputs = hijackSurfaceList(outputs) ?: return null
        val newConfig = createReplacementSessionConfiguration(
            original = config,
            outputs = hijackedOutputs.replacement
        ) ?: return null

        return HijackedArg(newConfig, hijackedOutputs.playbackSurface)
    }

    private fun createReplacementSessionConfiguration(
        original: Any,
        outputs: Any
    ): Any? {
        val className = original.javaClass.name
        return when (className) {
            "android.hardware.camera2.params.SessionConfiguration" -> {
                val sessionType = XposedHelpers.callMethod(original, "getSessionType")
                val executor = XposedHelpers.callMethod(original, "getExecutor")
                val callback = XposedHelpers.callMethod(original, "getStateCallback")
                XposedHelpers.newInstance(original.javaClass, sessionType, outputs, executor, callback)
                    .also { copyOptionalSessionState(original, it) }
            }

            "android.hardware.camera2.params.ExtensionSessionConfiguration" -> {
                val extension = XposedHelpers.callMethod(original, "getExtension")
                val executor = XposedHelpers.callMethod(original, "getExecutor")
                val callback = XposedHelpers.callMethod(original, "getStateCallback")
                XposedHelpers.newInstance(original.javaClass, extension, outputs, executor, callback)
                    .also { copyOptionalSessionState(original, it) }
            }

            else -> null
        }
    }

    private fun copyOptionalSessionState(from: Any, to: Any) {
        copyOptionalState(from, to, "getInputConfiguration", "setInputConfiguration")
        copyOptionalState(from, to, "getSessionParameters", "setSessionParameters")
        copyOptionalState(from, to, "getPostviewOutputConfiguration", "setPostviewOutputConfiguration")
        copyOptionalState(from, to, "getColorSpace", "setColorSpace")
    }

    private fun copyOptionalState(from: Any, to: Any, getter: String, setter: String) {
        val value = runCatching { XposedHelpers.callMethod(from, getter) }.getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(to, setter, value) }
    }

    private fun shouldInterceptCamera(): Boolean {
        val uri = getVideoUri()
        if (!isVideoReadable(uri, null)) return false

        return isVoipUiActive
    }

    private fun getAppContext(): Context? {
        return runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? Context
        }.getOrNull()
    }

    private fun isVideoReadable(uri: Uri, context: Context?): Boolean {
        val readable = when (uri.scheme) {
            "content" -> {
                val ctx = context ?: getAppContext() ?: return false
                runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
            }
            "file" -> {
                val path = uri.path ?: return false
                val file = File(path)
                file.exists() && file.canRead()
            }
            else -> {
                // Backward compatibility fallback for raw absolute path strings
                val file = File(uri.toString())
                file.exists() && file.canRead()
            }
        }

        if (readable) {
            warnedMissingPath = null
            return true
        }

        val uriStr = uri.toString()
        if (warnedMissingPath != uriStr) {
            warnedMissingPath = uriStr
            WeLogger.w(TAG, "virtual video is missing or unreadable: $uriStr")
        }
        return false
    }

    private fun getVideoUri(pathStr: String = WePrefs.getStringOrDef(KEY_VIDEO_PATH, "").trim()): Uri {
        return if (pathStr.isEmpty()) {
            Uri.fromFile(DEFAULT_VIDEO_PATH.toFile())
        } else {
            runCatching { pathStr.toUri() }.getOrElse { Uri.fromFile(File(pathStr)) }
        }
    }

    @Synchronized
    private fun getDummySurfaceTexture(): SurfaceTexture {
        val current = dummySurfaceTexture
        if (current != null) return current

        return SurfaceTexture(10).apply {
            setDefaultBufferSize(1, 1)
            dummySurfaceTexture = this
        }
    }

    @Synchronized
    private fun getDummySurface(): Surface {
        val current = dummySurface
        if (current != null && current.isValid) return current

        return Surface(getDummySurfaceTexture()).also {
            dummySurface = it
        }
    }

    @Synchronized
    private fun startVirtualVideo(surface: Surface, videoUri: Uri, ownsSurface: Boolean) {
        if (!surface.isValid) {
            WeLogger.w(TAG, "playback surface is invalid")
            return
        }
        if (!isVideoReadable(videoUri, null)) return

        releasePlayer("restart")

        val player = MediaPlayer()
        mediaPlayer = player
        ownedPlaybackSurface = if (ownsSurface) surface else null

        runCatching {
            val context = getAppContext()
            if (context != null) {
                player.setDataSource(context, videoUri)
            } else {
                val pathStr = videoUri.path ?: videoUri.toString()
                player.setDataSource(pathStr)
            }
            player.setSurface(surface)
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener { preparedPlayer ->
                if (mediaPlayer !== preparedPlayer) return@setOnPreparedListener
                runCatching {
                    preparedPlayer.start()
                    WeLogger.d(TAG, "virtual video playback started")
                }.onFailure { WeLogger.e(TAG, "failed to start virtual video", it) }
            }
            player.setOnErrorListener { erroredPlayer, what, extra ->
                WeLogger.w(TAG, "virtual video playback error: what=$what, extra=$extra")
                if (mediaPlayer === erroredPlayer) releasePlayer("player error")
                true
            }
            player.prepareAsync()
        }.onFailure {
            WeLogger.e(TAG, "failed to prepare virtual video: $videoUri", it)
            releasePlayer("prepare failed")
        }
    }

    @Synchronized
    private fun releasePlayer(reason: String) {
        val player = mediaPlayer
        mediaPlayer = null

        if (player != null) {
            runCatching {
                player.setOnPreparedListener(null)
                player.setOnErrorListener(null)
                player.release()
            }.onFailure { WeLogger.w(TAG, "failed to release player after $reason", it) }
        }

        ownedPlaybackSurface?.runCatching { release() }
        ownedPlaybackSurface = null
    }

    private data class HijackedArg(
        val replacement: Any,
        val playbackSurface: Surface
    )
}
