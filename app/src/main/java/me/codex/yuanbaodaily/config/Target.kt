package me.codex.yuanbaodaily.config

import android.content.Context
import android.os.Build

object Target {
    const val PACKAGE_NAME = "com.tencent.hunyuan.app.chat"
    /** 元宝 2.85.1 (versionCode 64939656). */
    const val VERSION_CODE = 64939656L
    const val LOG_TAG = "YuanbaoDaily"

    /**
     * Disabled by default for startup stability. The 2.85.1 generated DTMP
     * model classes are created during the first launch; broad constructor and
     * method hooks there can block the host before its splash screen finishes.
     */
    const val ENABLE_MODEL_HOOKS = false

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** Classes observed in 元宝_2.85.1.apk. */
    val TASK_MODEL_CLASSES = listOf(
        "com.tencent.hunyuan.mpcommon.generated.biz.mine.viewmodel.items.MineItemWelfareViewModel",
        "com.tencent.hunyuan.mpcommon.generated.biz.hometab.viewmodel.items.OpenCampaignWebData",
        "com.tencent.hunyuan.mpcommon.generated.biz.hometab.viewmodel.items.HomeTabMineIconWelfareAnimationStatus",
        "com.tencent.hunyuan.mpcommon.generated.biz.campaign.viewmodel.CampaignContentViewModel",
        "com.tencent.hunyuan.mpcommon.generated.biz.campaign.viewmodel.OpenWebPage",
        "com.tencent.hunyuan.mpcommon.generated.biz.campaign.bizmodel.CampaignWebUrlInfo",
    )
}
