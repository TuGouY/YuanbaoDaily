package me.codex.yuanbaodaily.task

import java.util.Random

/** Creates useful, visibly different requests for every round of a task pass. */
internal class TaskPromptFactory {
    private val random = Random(System.nanoTime())
    private val used = linkedSetOf<String>()

    fun ask(attempt: Int): String = unique {
        val topics = listOf(
            "整理一周的工作重点",
            "改善久坐后的疲劳",
            "安排一个低预算周末",
            "培养稳定的阅读习惯",
            "提高日常表达的条理",
            "快速准备健康早餐",
            "给手机照片做分类",
            "在忙碌时保持专注",
            "学习一项新技能",
            "做好当天的简短复盘",
            "减少无意识刷手机的时间",
            "规划一次轻松的城市散步",
        )
        val forms = listOf(
            "请给我三个可以马上执行的建议",
            "请用步骤清单说明具体做法",
            "请比较两种常见方法并给出选择理由",
            "请给出一个十五分钟内能开始的方案",
            "请用通俗语言说明关键原则和注意事项",
        )
        val endings = listOf(
            "，回答控制在120字以内。",
            "，并给一个生活化例子。",
            "，按重要程度排序。",
            "，语气自然简洁。",
            "，最后补充一个容易忽略的细节。",
        )
        "关于${topics.pick()},${forms.pick()}${endings[(attempt + random.nextInt(endings.size)) % endings.size]}"
    }

    fun writing(attempt: Int): String = unique {
        val scenes = listOf(
            "周末读书分享",
            "邀请朋友一起远足",
            "感谢同事协助项目",
            "提醒团队参加会议",
            "记录一次傍晚散步",
            "鼓励朋友坚持运动",
            "介绍一家安静的小店",
            "总结今天完成的工作",
            "给家人发送节日问候",
            "发布物品失而复得的感谢信息",
        )
        val tones = listOf("自然温暖", "简洁真诚", "轻松有画面感", "礼貌克制", "清晰有条理")
        val formats = listOf("一段短文", "三点式文案", "朋友圈文案", "简短通知", "小红书风格文案")
        val lengths = listOf(80, 100, 120, 150)
        "请围绕“${scenes.pick()}”写${formats.pick()}，语气${tones.pick()}，约${lengths[(attempt + random.nextInt(lengths.size)) % lengths.size]}字，不要使用夸张口号。"
    }

    fun picture(scene: PictureScene?, attempt: Int): String = unique {
        val instructions = when (scene) {
            PictureScene.LANDSCAPE -> listOf(
                "增强天空、山体和湖面的层次，适度提高通透度，保留自然色彩",
                "提亮湖面倒影并加强远山细节，整体调整为清新的旅行摄影风格",
                "优化天空高光和水面反射，让画面更有纵深感，不要过度饱和",
            )
            PictureScene.PORTRAIT -> listOf(
                "柔化背景并提亮人物面部，保持肤色自然，不要改变五官",
                "增强人物眼神光和发丝细节，轻微虚化背景，保持真实人像质感",
                "调整为自然的杂志人像风格，改善光线和构图，不要磨皮过度",
            )
            PictureScene.ANIMAL -> listOf(
                "突出动物主体并适度虚化背景，增强毛发细节和眼神光",
                "改善动物面部光线和草地色彩，保持毛色自然，画面更生动",
                "加强主体清晰度与背景层次，保留动物原本外观，不要添加物体",
            )
            PictureScene.CITY -> listOf(
                "调整为清晰的城市电影感，控制高光并增强建筑层次",
                "提升建筑和道路细节，优化晚霞色彩，保持夜景光线自然",
                "增强城市纵深和窗户灯光，压低过亮区域，不要改变建筑结构",
            )
            PictureScene.FLOWER -> listOf(
                "强化花瓣纹理和柔和光线，降低背景干扰，保持真实质感",
                "提亮花束主体并优化色彩层次，让背景更柔和但不过度虚化",
                "增强花瓣细节与花瓶反光，调整为自然清新的静物摄影风格",
            )
            null -> listOf(
                "提升主体清晰度，校正白平衡并增加层次，保留真实质感",
                "适度增强色彩和光影，优化构图，不要改变主体内容",
            )
        }
        "请编辑这张图片：${instructions[(attempt + random.nextInt(instructions.size)) % instructions.size]}。"
    }

    private fun unique(factory: () -> String): String {
        repeat(24) {
            val value = factory()
            if (used.add(value)) return value
        }
        val value = factory() + " 请从另一个角度组织答案。"
        used.add(value)
        return value
    }

    private fun <T> List<T>.pick(): T = this[random.nextInt(size)]
}
