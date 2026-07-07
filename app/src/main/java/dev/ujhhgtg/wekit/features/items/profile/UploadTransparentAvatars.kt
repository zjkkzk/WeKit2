package dev.ujhhgtg.wekit.features.items.profile

import android.graphics.Bitmap
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(name = "上传透明头像", categories = ["个人资料"], description = "头像上传时使用 PNG 格式保持透明")
object UploadTransparentAvatars : SwitchFeature(), IResolveDex {

    private const val TAG = "UploadTransparentAvatars"

    private val methodSaveBitmap by dexMethod {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            usingStrings("saveBitmapToImage pathName null or nil", "MicroMsg.BitmapUtil")
        }
    }

    override fun onEnable() {
        methodSaveBitmap.hookBefore {
            val args = args

            val pathName = args[3] as? String
            if (pathName != null &&
                (pathName.contains("avatar") || pathName.contains("user_hd"))
            ) {
                WeLogger.i(TAG, "检测到头像保存: $pathName")
                args[2] = Bitmap.CompressFormat.PNG
                WeLogger.i(TAG, "已将头像格式修改为PNG，保留透明通道")
            }
        }
    }
}
