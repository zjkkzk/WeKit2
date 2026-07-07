package dev.ujhhgtg.wekit.features.items.system.agent

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.agent.WeAgentBall
import dev.ujhhgtg.wekit.ui.agent.WeAgentPanel
import dev.ujhhgtg.wekit.ui.utils.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Manages the WeAgent system overlay (`TYPE_APPLICATION_OVERLAY`): a draggable floating ball and an
 * expandable panel window, both added to the [WindowManager] rather than a host Activity's view
 * tree. This survives across all WeChat Activities (and even when WeChat is backgrounded) with no
 * per-Activity hooks.
 *
 * The overlay lives in WeChat's process, so the effective `SYSTEM_ALERT_WINDOW` grant is WeChat's;
 * we gate mounting on [Settings.canDrawOverlays] and toast guidance if it's missing.
 */
object WeAgentOverlayController {

    private val TAG = "WeAgentOverlay"

    private const val PREF_BALL_X = "weagent_ball_x"
    private const val PREF_BALL_Y = "weagent_ball_y"

    private val wm: WindowManager
        get() = HostInfo.application.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: ComposeView? = null

    // Ball window position captured at drag start (absolute-offset dragging, set in onDragStart).
    private var dragStartX = 0
    private var dragStartY = 0

    @Volatile var isShown = false
        private set

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(HostInfo.application)

    /** Adds the floating ball if permitted; toasts guidance otherwise. Idempotent. */
    fun show() {
        if (isShown) return
        if (!canDrawOverlays()) {
            showToast("WeAgent 需要悬浮窗权限，请在系统设置中为微信开启「显示在其他应用上层」")
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        runCatching { addBall() }.onFailure { WeLogger.e(TAG, "failed to add ball", it) }
        isShown = true
    }

    fun hide() {
        removePanel()
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        ballParams = null
        isShown = false
    }

    // -----------------------------------------------------------------------------------------
    // Ball window
    // -----------------------------------------------------------------------------------------

    private fun addBall() {
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = WePrefs.getIntOrDef(PREF_BALL_X, 24)
            y = WePrefs.getIntOrDef(PREF_BALL_Y, 240)
        }
        ballParams = params

        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentBall(
                        state = WeAgentService.ballState.value,
                        onClick = { togglePanel() },
                        onDragStart = {
                            ballParams?.let { dragStartX = it.x; dragStartY = it.y }
                        },
                        onDrag = { dx, dy ->
                            val p = ballParams
                            val v = ballView
                            if (p != null && v != null) {
                                p.x = dragStartX + dx.toInt()
                                p.y = dragStartY + dy.toInt()
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            val p = ballParams ?: return@WeAgentBall
                            val v = ballView
                            if (v != null) {
                                clampToScreen(v, p)
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                            WePrefs.putInt(PREF_BALL_X, p.x)
                            WePrefs.putInt(PREF_BALL_Y, p.y)
                        },
                    )
                }
            }
        }
        ballView = view
        wm.addView(view, params)
    }

    /** Keeps the ball fully on-screen after a drag. */
    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        val w = if (view.width > 0) view.width else (52 * metrics.density).toInt()
        val h = if (view.height > 0) view.height else (52 * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    // -----------------------------------------------------------------------------------------
    // Panel window
    // -----------------------------------------------------------------------------------------

    fun togglePanel() {
        if (panelView != null) removePanel() else addPanel()
    }

    private fun addPanel() {
        val params = baseLayoutParams(focusable = true).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentPanel(onDismiss = { removePanel() })
                }
            }
        }
        panelView = view
        runCatching { wm.addView(view, params) }.onFailure { WeLogger.e(TAG, "failed to add panel", it) }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    // -----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun baseLayoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }
}
