package me.codex.yuanbaodaily.config

/** Explicit request contract between the module UI and the injected host process. */
object RunRequest {
    const val ACTION_RUN_NOW = "me.codex.yuanbaodaily.action.RUN_NOW"
    const val EXTRA_REQUEST_ID = "me.codex.yuanbaodaily.extra.REQUEST_ID"
    const val EXTRA_ENABLED = "me.codex.yuanbaodaily.extra.ENABLED"
    const val EXTRA_RUN_AT_MINUTES = "me.codex.yuanbaodaily.extra.RUN_AT_MINUTES"
    const val EXTRA_SOURCE = "me.codex.yuanbaodaily.extra.SOURCE"

    /**
     * Diagnostic channel: read the current screen and type a probe string through
     * the accessibility bridge, so the host-side channels can be verified without
     * waiting for a task round.
     */
    const val ACTION_SELF_TEST = "me.codex.yuanbaodaily.action.SELF_TEST"
    const val EXTRA_SELF_TEST_TEXT = "me.codex.yuanbaodaily.extra.SELF_TEST_TEXT"
    /** typing（默认）/ media / problem / template */
    const val EXTRA_SELF_TEST_MODE = "me.codex.yuanbaodaily.extra.SELF_TEST_MODE"

    /**
     * 调试用：两次点击之间的等待毫秒数（建议 6000~8000）。
     *
     * 开启后每一次真实点击都会打 `TAPTRACE #n ...` 日志并停顿，便于外部工具
     * 逐次截屏核对"这一下点得对不对"（见 `automation/TapTrace`）。
     * 不传 / 传 0 = 保持生产节拍（不改变行为）。
     */
    const val EXTRA_TAP_PACE_MS = "me.codex.yuanbaodaily.extra.TAP_PACE_MS"

    // Shared preference keys used by the module UI and alarm receiver.
    const val KEY_REQUEST_ID = "run_request_id"
    const val KEY_RUN_NOW = "run_now"
}
