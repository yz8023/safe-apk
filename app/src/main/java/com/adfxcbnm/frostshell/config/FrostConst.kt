package com.adfxcbnm.frostshell.config

import com.adfxcbnm.frostshell.util.FrostStringUtils

object FrostConst {
    const val OPTION_OPEN_NOISY_LOG_LONG = "noisy-log"
    const val OPTION_NO_SIGN_PACKAGE_LONG = "no-sign"
    const val OPTION_NO_SIGN_PACKAGE = "x"
    const val OPTION_DUMP_CODE_LONG = "dump-code"
    const val OPTION_INPUT_FILE = "f"
    const val OPTION_INPUT_FILE_LONG = "package-file"
    const val OPTION_DEBUGGABLE_LONG = "debug"
    const val OPTION_DISABLE_APP_COMPONENT_FACTORY_LONG = "disable-acf"
    const val OPTION_OUTPUT_PATH = "o"
    const val OPTION_OUTPUT_PATH_LONG = "output"
    const val OPTION_EXCLUDE_ABI = "e"
    const val OPTION_EXCLUDE_ABI_LONG = "exclude-abi"
    const val OPTION_VERSION = "v"
    const val OPTION_VERSION_LONG = "version"
    const val OPTION_DO_NOT_PROTECT_CLASSES_RULES = "r"
    const val OPTION_DO_NOT_PROTECT_CLASSES_RULES_LONG = "rules-file"
    const val OPTION_KEEP_CLASSES = "K"
    const val OPTION_KEEP_CLASSES_LONG = "keep-classes"
    const val OPTION_SMALLER = "S"
    const val OPTION_SMALLER_LONG = "smaller"
    const val OPTION_PROTECT_CONFIG = "c"
    const val OPTION_PROTECT_CONFIG_LONG = "protect-config"
    const val OPTION_VERIFY_SIGN = "vs"
    const val OPTION_VERIFY_SIGN_LONG = "verify-sign"
    const val OPTION_DISABLE_FRIDA_DETECT_LONG = "disable-frida-detect"
    const val OPTION_DISABLE_CRC_DETECT_LONG = "disable-crc-detect"
    const val OPTION_DISABLE_ANTI_DEBUG_LONG = "disable-anti-debug"
    const val FLAG_DISABLE_FRIDA_DETECT = 1
    const val FLAG_DISABLE_CRC_DETECT = 2
    const val FLAG_DISABLE_ANTI_DEBUG = 4
    const val FLAG_DISABLE_ANTI_DUMP = 8
    const val KEY_STORE_ASSET_NAME = "ironshell.jks"
    const val KEY_STORE_ASSET_PATH = "assets/ironshell.jks"
    const val STORE_PASSWORD = "IRONSHELL_9f3kX2"
    const val KEY_PASSWORD = "IRONSHELL_9f3kX2"
    const val KEY_ALIAS = "ironshell"
    const val DEFAULT_THREAD_NAME = "ironshell"
    var ROOT_OF_OUT_DIR: String = System.getProperty("java.io.tmpdir") ?: ""
    const val MULTI_DEX_CODE_VERSION: Short = 2
    const val KEY_SHELL_CONFIG_STORE_NAME = ".meta"
    const val KEY_LIBS_DIR_NAME = "irn"
    const val KEY_DEXES_STORE_NAME = "d.zip"
    const val KEY_DEXES_STORE_UNALIGNED_NAME = "d_unaligned.zip"
    const val KEY_BUILD_KEY_FILE_NAME = "build-key"
    const val KEY_JNI_BASE_CLASS_NAME = "JniBridge"
    const val DEFAULT_SHELL_PACKAGE_NAME = "com/ironshell/runtime"
    const val SHELL_PACKAGE_NAME_AUTO = "<random>"
    val RANDOM_DIR_NAME: String = FrostStringUtils.generateIdentifier(16)
    const val LABEL_CFG_IV = "irn-cfg-iv"
    const val LABEL_POOL_KEY = "irn-pool-key"
    const val LABEL_POOL_IV = "irn-pool-iv"
    const val LABEL_DEXZIP_KEY = "irn-dexzip"
}
