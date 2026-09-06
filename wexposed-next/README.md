# WeXposed Next

Modern compatibility branch for rebuilding the useful parts of the original WeXposed experience on current WeChat/LSPosed.

## Baseline

- Target package: `com.tencent.mm`
- Runtime: LSPosed / Xposed-compatible hook layer
- Android target: Android 16 first
- Legacy reference: `Xposed-Modules-Repo/com.fkzhang.wechatxposed`, latest public release 2.43
- Important limitation: the public legacy repository contains only release/readme material, not the full maintainable source tree. Current WeChat is also closed-source. Compatibility work therefore uses behavioral analysis, APK/decompiled symbol mapping, and a clean-room compatibility layer rather than pretending full upstream source exists.

## Migration strategy

1. Build a version-neutral hook registry and feature switches.
2. Fingerprint candidate classes/methods by behavior/signature rather than hard-coded obfuscated names.
3. Store WeChat-version-specific symbol maps separately from feature logic.
4. Restore features incrementally and keep each feature fail-closed when its hook target cannot be resolved.
5. Add diagnostics so a WeChat update reports which mappings broke instead of crashing the app.

## Initial feature buckets

- Anti-recall / message preservation
- Message forwarding helpers
- Voice/image/video forwarding helpers
- Moments UI/forwarding enhancements
- Group/chat UI enhancements
- Contact-management helpers
- Auto-reply and local personal automation
- Emoji/sticker limit/UI enhancements
- Menu/UI customization

Financial actions, credential/security bypasses, abusive bulk messaging, stealth persistence, or account-protection bypasses are intentionally excluded from automatic implementation.

## Source acquisition status

- Legacy WeXposed release repository located and verified.
- Legacy 2.43 release metadata located.
- Legacy repository does **not** expose the complete original source.
- Current WeChat source is not public; target analysis must use an APK supplied/downloaded from a legitimate distribution source.

## Next code milestone

Create `compat/` symbol-resolution primitives and a first anti-recall hook adapter once the exact target WeChat APK/version code is available.
