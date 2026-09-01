package com.adfxcbnm.hardeningtool

class ProtectionNative {
    companion object {
        @JvmStatic val available: Boolean = try {
            System.loadLibrary("protection")
            true
        } catch (_: Throwable) {
            false
        }

        @JvmStatic external fun checkDebugger(): Boolean
        @JvmStatic external fun checkRoot(): Boolean
        @JvmStatic external fun checkHook(): Boolean
        @JvmStatic external fun checkEmulator(): Boolean
        @JvmStatic external fun checkDebugTiming(): Boolean
        @JvmStatic external fun checkProxy(): Boolean
        @JvmStatic external fun checkInjection(): Boolean
        @JvmStatic external fun checkMemoryDump(): Boolean
        @JvmStatic external fun checkSpeed(): Boolean
        @JvmStatic external fun checkMultiInstance(): Boolean
        @JvmStatic external fun checkSsl(): Boolean
        @JvmStatic external fun checkCodeInject(): Boolean
        @JvmStatic external fun checkMagisk(): Boolean
        @JvmStatic external fun checkFrida(): Boolean
        @JvmStatic external fun checkXposed(): Boolean
        @JvmStatic external fun checkSelfIntegrity(): Boolean
        @JvmStatic external fun checkLibcHook(): Boolean
        @JvmStatic external fun checkInlineHook(): Boolean
        @JvmStatic external fun checkMemoryScan(): Boolean
        @JvmStatic external fun checkEnvTestKeys(): Boolean
        @JvmStatic external fun checkEnvSelinux(): Boolean
        @JvmStatic external fun getFullStatus(): String
    }
}
