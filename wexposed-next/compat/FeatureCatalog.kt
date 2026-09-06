package com.fll.zafiro.wexposednext.compat

/** Stable logical feature IDs. Concrete WeChat symbols are resolved per build. */
object FeatureCatalog {
    const val ANTI_RECALL = "anti_recall"
    const val MESSAGE_FORWARD = "message_forward"
    const val VOICE_FORWARD = "voice_forward"
    const val MEDIA_FORWARD = "media_forward"
    const val MOMENTS_ENHANCEMENT = "moments_enhancement"
    const val GROUP_CHAT_ENHANCEMENT = "group_chat_enhancement"
    const val CONTACT_TOOLS = "contact_tools"
    const val AUTO_REPLY = "auto_reply"
    const val EMOJI_ENHANCEMENT = "emoji_enhancement"
    const val UI_CUSTOMIZATION = "ui_customization"

    val initialTargets = listOf(
        HookTarget(
            id = "message_recall_dispatch",
            feature = ANTI_RECALL,
            methodNameHints = listOf("revoke", "recall", "delete"),
            requiredStringConstants = listOf("revokemsg")
        ),
        HookTarget(
            id = "message_insert_or_update",
            feature = ANTI_RECALL,
            methodNameHints = listOf("insert", "update"),
            requiredFieldTypeHints = listOf("long", "String")
        ),
        HookTarget(
            id = "chat_context_menu",
            feature = UI_CUSTOMIZATION,
            methodNameHints = listOf("onCreateContextMenu", "onMMMenuItemSelected")
        )
    )
}
