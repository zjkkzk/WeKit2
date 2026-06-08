package dev.ujhhgtg.wekit.hooks.items.system

import android.content.Context
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BBool
import dev.ujhhgtg.wekit.utils.reflection.BFloat
import dev.ujhhgtg.wekit.utils.reflection.BInt
import dev.ujhhgtg.wekit.utils.reflection.isStatic
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier as ReflectModifier

@HookItem(
    name = "DPI 修改", categories = ["界面美化", "系统与隐私"],
    description = "自定义微信屏幕密度"
)
object CustomDpiHook : ClickableHookItem(), IResolvesDex {

    private val methodGetDisplayMetrics by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.MMDensityManager", "screenResolution_target_field")
            }

            modifiers = ReflectModifier.PUBLIC
            returnType = DisplayMetrics::class.java.name
            paramCount = 0

            addInvoke {
                returnType = "boolean"
            }
        }
    }

    private var tabIconScaleField: Field? = null
    private var tabIconInitMethod: Method? = null

    private var customDpi by prefOption("custom_dpi", 360)

    override fun onEnable() {
        methodGetDisplayMetrics.hookAfter {
            val metrics = result as? DisplayMetrics ?: return@hookAfter
            applyCustomDpi(metrics)
        }

        hookTabIconScale()
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(customDpi.toString()) }

            AlertDialogContent(
                title = { Text("DPI 修改") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it.filter { ch -> ch.isDigit() } },
                            label = { Text("显示宽度") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val dpiInput = value.toIntOrNull()
                        if (dpiInput == null || dpiInput <= 0) {
                            showToast("数字格式不正确!")
                            return@Button
                        }
                        customDpi = dpiInput
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun applyCustomDpi(metrics: DisplayMetrics) {
        val dpi = customDpi
        val fontScale = metrics.scaledDensity / metrics.density
        metrics.density = dpi / 160.0f
        metrics.densityDpi = dpi
        metrics.scaledDensity = dpi / 160.0f * fontScale
    }

    private fun hookTabIconScale() {
        val tabIconView = "com.tencent.mm.ui.TabIconView".toClass()
        val method = tabIconInitMethod ?: tabIconView.declaredMethods.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(BInt, BInt, BInt, BBool))
        }?.also {
            tabIconInitMethod = it
        } ?: return

        method.hookBefore {
            val view = thisObject ?: return@hookBefore
            val field = tabIconScaleField ?: view.javaClass.declaredFields.firstOrNull {
                it.type == BFloat && !it.isStatic
            }?.makeAccessible()?.also {
                tabIconScaleField = it
            } ?: return@hookBefore

            field.setFloat(view, customDpi * 1.1666666f / 400.0f)
        }
    }
}

