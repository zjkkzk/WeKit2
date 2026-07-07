package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature

@Feature(name = "测试", categories = ["调试"], description = "???")
object Experiments : ClickableFeature() {

    @Suppress("unused")
    private const val TAG = "Experiments"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
    }
}
