package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

// no behavior of its own; WeChatMessageContextMenuApi reads isEnabled to decide whether to
// collapse all provider menu items into a single "WeKit" entry backed by a Compose dialog
@Feature(
    name = "消息长按菜单项合并展示",
    categories = ["聊天"],
    description = "开启后, 消息长按菜单只显示一个 WeKit 项, 点击后在对话框中集中展示所有模块菜单项"
)
object MergeChatMessageContextMenuItems : SwitchFeature()
