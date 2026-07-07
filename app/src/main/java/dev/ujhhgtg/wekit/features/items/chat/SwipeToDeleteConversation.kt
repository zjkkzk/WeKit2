package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.DexClassDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

@Feature(
    name = "左划删除对话",
    categories = ["聊天"],
    description = "在主页对话列表向左滑动: 滑一小段松手会停住并露出「隐藏」「删除」按钮; 一次性划到底直接删除\n删除彻底且不可恢复 (无需确认), 隐藏仅从列表移除"
)
object SwipeToDeleteConversation : SwitchFeature(), IResolveDex {

    // Layout / gesture tuning.
    private const val BUTTON_WIDTH_DP = 72        // width of each action button (隐藏 / 删除)
    private const val FLY_OUT_THRESHOLD_DP = 220  // drag past this (left) on release => fly out + delete
    private const val COLOR_HIDE = 0xFFF5A623.toInt()   // iOS-ish amber
    private const val COLOR_DELETE = 0xFFFF3B30.toInt()  // iOS-ish red

    // Per-row gesture + reveal state. The list recycles row views, so talker / conversation are
    // refreshed on every getView bind; the parked-open offset persists across binds of the SAME
    // view object (WeakHashMap key) which is what we want — but we reset it on rebind because a
    // recycled view now represents a different conversation.
    private class SwipeState(
        val touchSlop: Int,
        // The width of the revealed action panel (both buttons), in px.
        val revealWidth: Float,
        // Drag past this (leftwards) on release => fly the row out and delete.
        val flyOutThreshold: Float,
        var talker: String? = null,
        var conversation: Any? = null,
        // The FrameLayout we insert to host content+panel; non-null once this row is set up (guard).
        var wrapper: View? = null,
        // The content view we translate (cj0), the action panel behind it, and its two buttons.
        var content: View? = null,
        var panel: View? = null,
        var hideBtn: View? = null,
        var delBtn: View? = null,
        var startX: Float = 0f,
        var startY: Float = 0f,
        // translationX of the content at gesture start (0 when closed, -revealWidth when parked open).
        var dragBase: Float = 0f,
        var isDragging: Boolean = false,
        var isOpen: Boolean = false,
        // Whether the row was already parked-open when THIS gesture started. Captured on ACTION_DOWN
        // (not read live, since isOpen flips mid-animation). Only the second swipe (started open) gets
        // the expanding-删除 + fly-out behavior; the first swipe always settles to the threshold with
        // both buttons evenly split.
        var startedOpen: Boolean = false,
        var flungOut: Boolean = false,
    )

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    // At most one row is open at a time (iOS behavior). Weak so it can't leak a row view.
    @SuppressLint("StaticFieldLeak")
    private var openState: SwipeState? = null

    private val settleInterpolator = DecelerateInterpolator()

    private const val TAG = "SwipeToDeleteConversation"

    // WeChat has TWO home conversation-list adapters and picks one at runtime in MainUI.onCreate
    // (o75.s.f347101a.b()): the legacy ListView adapter com.tencent.mm.ui.conversation.p3
    // (ConversationWithCacheAdapter) and the newer MVVM adapter o75.v0
    // (ConversationAdapter.MvvmConversationAdapter). Both expose getView(int,View,ViewGroup) that
    // returns the clickable row root and getItem(position) -> com.tencent.mm.storage.m3, and both
    // re-install their own row OnTouchListener on every bind — so we hook getView on whichever is
    // present. allowFailure so a build that only ships one of them still resolves the other.
    private val classConversationAdapter by dexClass(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "[getView] position="
            )
        }
    }

    private val classMvvmConversationAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter")
        }
    }

    override fun onEnable() {
        hookAdapter(classConversationAdapter)
        hookAdapter(classMvvmConversationAdapter)
    }

    override fun onDisable() {
        states.clear()
    }

    // ── row binding: attach the swipe listener + keep talker / conversation fresh ─

    private fun hookAdapter(adapter: DexClassDelegate) {
        if (adapter.isPlaceholder) return
        adapter.reflekt()
            .firstMethod { name = "getView"; parameterCount = 3 }
            .hookAfter {
                val view = result as? View ?: return@hookAfter
                val position = args[0] as? Int ?: return@hookAfter

                // getItem(position) -> com.tencent.mm.storage.m3 (rconversation model).
                val conversation = runCatching {
                    thisObject.reflekt()
                        .firstMethod { name = "getItem"; parameterCount = 1 }
                        .invoke(position)
                }.getOrNull() ?: return@hookAfter

                val talker = runCatching {
                    conversation.reflekt()
                        .firstFieldOrNull { name = "field_username"; superclass() }
                        ?.get() as? String
                }.getOrNull()

                val ctx = view.context
                val state = states.getOrPut(view) {
                    SwipeState(
                        touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                        revealWidth = (BUTTON_WIDTH_DP * 2).dpToPx(ctx).toFloat(),
                        flyOutThreshold = FLY_OUT_THRESHOLD_DP.dpToPx(ctx).toFloat(),
                    )
                }
                state.talker = talker
                state.conversation = conversation

                // Resolve the content view (cj0 = the row's first child) and (once) build the action
                // panel behind it. The recycled row now represents a different conversation, so any
                // leftover open/translation from its previous use must be reset to closed.
                setUpRow(view, state)
                resetRow(state)

                // p3.getView (re)installs WeChat's own OnTouchListener (v3, for the ripple hotspot)
                // on the row root on EVERY bind — see p3.java:891, which runs before this hookAfter.
                // If we only set ours once, that call clobbers it on the next recycle and the swipe
                // silently stops working. So we re-install our wrapper every bind, delegating to
                // whatever listener is currently attached (unless it is already ours).
                attachSwipeListener(view, state)
            }
    }

    // The row root (cj1) is a horizontal LinearLayout whose first child (cj0) is the full-width
    // content. Adding a sibling to that LinearLayout would reflow cj0, so instead we WRAP cj0 in a
    // FrameLayout (inserted at cj0's original index, inheriting its LayoutParams): the action panel
    // is pinned to the wrapper's right edge, cj0 sits on top and slides left to reveal it. Done once
    // per row and tagged so re-binds don't wrap twice.
    private fun setUpRow(row: View, s: SwipeState) {
        // Already wrapped this row (state is keyed by the row view, stable across recycles).
        if (s.wrapper != null) return

        val group = row as? ViewGroup ?: return

        // cj0 = first child of the row root.
        val content = group.getChildAt(0) ?: return
        val index = group.indexOfChild(content)
        val lp = content.layoutParams
        WeLogger.i(TAG, "setUpRow: wrapping row=${group.javaClass.name} content=${content.javaClass.name} index=$index")

        val wrapper = FrameLayout(group.context)
        val panel = buildActionPanel(group.context, s)

        group.removeViewAt(index)
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Panel spans the whole wrapper; its two buttons are positioned/sized per-frame (see
        // applyTranslation) so the destructive one can expand to cover the other on over-drag.
        wrapper.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        wrapper.addView(content)  // content on top
        wrapper.layoutParams = lp  // take cj0's slot in the row LinearLayout
        wrapper.clipChildren = true
        group.addView(wrapper, index)

        s.wrapper = wrapper
        s.content = content
        s.panel = panel
        // Park the panel closed immediately so it isn't visible before the first drag.
        applyTranslation(s, 0f)
    }

    // A full-width host with two absolutely-positioned buttons. Their x/width are set per-frame in
    // applyTranslation, so we can grow 删除 leftward over 隐藏 during an over-drag.
    private fun buildActionPanel(context: Context, s: SwipeState): View {
        val panel = FrameLayout(context)

        fun button(label: String, bg: Int, onTap: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            maxLines = 1
            // Start at width 0: the buttons only ever get a real width from applyTranslation once the
            // row is measured. A non-zero initial width would flash as a colored box at x=0 before the
            // first layout pass runs applyTranslation (see the rowW guard there).
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            setOnClickListener { onTap() }
        }

        // 隐藏 (amber) added first (drawn under), 删除 (red) on top so it can cover 隐藏 when expanded.
        val hide = button("隐藏", COLOR_HIDE) { onAction(s, delete = false) }
        val del = button("删除", COLOR_DELETE) { onAction(s, delete = true) }
        panel.addView(hide)
        panel.addView(del)
        s.hideBtn = hide
        s.delBtn = del
        return panel
    }

    // Positions the content and the two action buttons for a given content offset (tx, <= 0).
    // reveal = -tx is how far the content has been pulled left (== the width of the button strip
    // showing on the right). The buttons GROW from width 0 to fill exactly the revealed strip
    // [rowW - reveal, rowW] as you drag. 隐藏 occupies the left half of the strip; 删除 the right.
    // On a second swipe (started open), dragging past the threshold smoothly widens 删除 LEFTWARD over
    // 隐藏 — driven by a continuous factor t (0 at the threshold → 1 at the fly-out point), so there is
    // no abrupt jump from the two-button split to the single 删除. gravity=CENTER keeps labels centered.
    private fun applyTranslation(s: SwipeState, tx: Float) {
        val content = s.content ?: return
        content.translationX = tx
        val hide = s.hideBtn ?: return
        val del = s.delBtn ?: return

        // Hide the whole panel when fully closed, independent of measurement: this runs BEFORE the
        // rowW guard below, so even if the row isn't measured yet (rowW == 0 at setup time) the panel
        // can't flash its buttons as colored boxes or be tapped. It's re-shown as soon as a drag
        // begins (reveal > 0).
        val reveal = (-tx).coerceAtLeast(0f)
        s.panel?.visibility = if (reveal <= 0f) View.GONE else View.VISIBLE
        if (reveal <= 0f) return

        val rowW = (s.panel?.width?.takeIf { it > 0 } ?: content.width).toFloat()
        if (rowW <= 0f) return

        val stripLeft = rowW - reveal
        val half = reveal / 2f

        // Transition factor: 0 during the even split (and all of a first swipe), ramping 0→1 as a
        // second-swipe over-drag grows from the reveal threshold toward the fly-out threshold.
        val t = if (s.startedOpen && s.flyOutThreshold > s.revealWidth) {
            ((reveal - s.revealWidth) / (s.flyOutThreshold - s.revealWidth)).coerceIn(0f, 1f)
        } else 0f

        // 隐藏: left half of the strip, stays put; gets covered by 删除 as t → 1.
        setButtonWidth(hide, half.toInt())
        hide.translationX = stripLeft

        // 删除: left edge lerps from the even-split position (stripLeft + half) toward stripLeft as
        // t → 1, so its width goes from half the strip to the whole strip continuously.
        val delLeft = stripLeft + half * (1f - t)
        setButtonWidth(del, (rowW - delLeft).toInt())
        del.translationX = delLeft
    }

    // Rubber-band resistance: linear up to [limit], then heavily damped beyond, so a first swipe can
    // move past the threshold a little but always wants to snap back to it.
    private fun rubberBand(tx: Float, limit: Float): Float {
        val over = -tx - limit
        return if (over <= 0f) tx else -(limit + over * 0.15f)
    }

    private fun setButtonWidth(v: View, w: Int) {
        val width = w.coerceAtLeast(0)
        if (v.layoutParams.width != width) {
            v.layoutParams = v.layoutParams.also { it.width = width }
            v.requestLayout()
        }
    }

    private fun resetRow(s: SwipeState) {
        s.isDragging = false
        s.isOpen = false
        s.flungOut = false
        s.dragBase = 0f
        s.content?.animate()?.cancel()
        applyTranslation(s, 0f)
        if (openState === s) openState = null
    }

    // Marks our wrapper so a re-bind can tell its own listener apart from WeChat's v3.
    private class SwipeTouchListener(
        val state: SwipeState,
        val delegate: View.OnTouchListener?,
    ) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val consumed = handleSwipe(v, state, event)
            // Always let WeChat's listener observe the event too (it only sets a ripple hotspot and
            // returns false), but our return value decides whether the row's click path proceeds.
            runCatching { delegate?.onTouch(v, event) }
            return consumed
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return  // already wrapped for this stream
        view.setOnTouchListener(SwipeTouchListener(state, current))
    }

    // Reads the View's current OnTouchListener out of its ListenerInfo, so we can chain to WeChat's.
    private fun getAttachedTouchListener(view: View): View.OnTouchListener? = runCatching {
        val info = view.reflekt()
            .firstFieldOrNull { name = "mListenerInfo"; superclass() }
            ?.get() ?: return null
        info.reflekt()
            .firstFieldOrNull { name = "mOnTouchListener" }
            ?.get() as? View.OnTouchListener
    }.getOrNull()

    // ── gesture ──────────────────────────────────────────────────────────────
    //
    // The conversation row is clickable, so it consumes ACTION_DOWN in its own onTouchEvent and no
    // child touch-target is created — meaning the row's onInterceptTouchEvent is never called for
    // subsequent MOVE events. On top of that, the home screen's horizontal ViewPager intercepts the
    // horizontal drag before the row would ever see it. So an OnTouchListener (which runs ahead of
    // both onTouchEvent and the click) is the only reliable place to catch the swipe: we detect the
    // horizontal drag at scaledTouchSlop (smaller than the pager's paging slop) and immediately call
    // requestDisallowInterceptTouchEvent(true) so the pager can't steal the gesture. This mirrors
    // how WeChat's own MMSlideDelView drives its slide entirely from onTouchEvent.
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        val content = s.content ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.isDragging = false
                // Continue a drag from wherever the content currently sits (0, or -revealWidth if
                // parked open), so an open row keeps sliding instead of jumping.
                s.dragBase = content.translationX
                // Remember whether we started from the parked-open state; only then does over-drag
                // expand 删除 / allow fly-out. A tap on an open row's content closes it (handled on UP).
                s.startedOpen = s.isOpen
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - s.startX
                val dy = event.rawY - s.startY
                if (!s.isDragging && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                    // Engage for a leftward drag, OR any drag when already open (to allow closing).
                    if (dx < 0 || s.isOpen) {
                        s.isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                        v.cancelLongPress()
                    }
                }
                if (s.isDragging) {
                    val raw = (s.dragBase + dx).coerceAtMost(0f)
                    // First swipe: rubber-band beyond the threshold so the pair never over-expands
                    // into single-删除 mode — the content resists past revealWidth and will snap back
                    // to exactly the threshold on release. Second swipe: free drag (enables fly-out).
                    val tx = if (s.startedOpen) raw else rubberBand(raw, s.revealWidth)
                    applyTranslation(s, tx)
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (!s.isDragging) {
                    // No drag happened. If the row was open and this was a tap on the content, close
                    // it and swallow the tap.
                    if (s.isOpen) {
                        settleClosed(s)
                        return true
                    }
                    return false
                }
                s.isDragging = false
                val tx = content.translationX
                when {
                    // First swipe (started closed): ALWAYS settle to the threshold once past a small
                    // engage distance — never fly out, never collapse to a single button. Below that
                    // small distance, treat it as a cancel and snap closed.
                    !s.startedOpen -> {
                        if (tx <= -s.revealWidth * 0.25f) settleOpen(s) else settleClosed(s)
                    }
                    // Second swipe (started open): far enough left => fly out + delete.
                    tx <= -s.flyOutThreshold -> flyOutAndDelete(v, s)
                    // Otherwise stay parked open (or re-open if it drifted).
                    tx <= -s.revealWidth * 0.5f -> settleOpen(s)
                    // Dragged back toward closed => close.
                    else -> settleClosed(s)
                }
                true
            }

            else -> false
        }
    }

    private fun settleOpen(s: SwipeState) {
        // Close any other open row first (one-at-a-time).
        openState?.takeIf { it !== s }?.let { settleClosed(it) }
        animateTo(s, -s.revealWidth)
        s.isOpen = true
        openState = s
    }

    private fun settleClosed(s: SwipeState) {
        animateTo(s, 0f)
        s.isOpen = false
        if (openState === s) openState = null
    }

    private fun flyOutAndDelete(v: View, s: SwipeState) {
        if (s.flungOut) return
        s.flungOut = true
        animateTo(s, -v.width.toFloat()) { onAction(s, delete = true) }
        if (openState === s) openState = null
    }

    // Animates the content to targetTx while keeping the action panel in lockstep (both driven
    // through applyTranslation), since ViewPropertyAnimator only touches the content's translationX.
    private fun animateTo(s: SwipeState, targetTx: Float, onEnd: (() -> Unit)? = null) {
        val content = s.content ?: return
        content.animate()
            .translationX(targetTx)
            .setDuration(200)
            .setInterpolator(settleInterpolator)
            .setUpdateListener { applyTranslation(s, content.translationX) }
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    // Executes the hide/delete for a row (button tap or fly-out). All deletes are no-confirm.
    private fun onAction(s: SwipeState, delete: Boolean) {
        val talker = s.talker
        val conversation = s.conversation
        if (talker.isNullOrBlank()) return
        runOnUiThread {
            if (delete) {
                WeConversationApi.deleteConversation(talker, conversation)
                showToast("已删除")
            } else {
                WeConversationApi.hideConversation(talker)
                showToast("已隐藏")
            }
            if (openState === s) openState = null
        }
    }
}
