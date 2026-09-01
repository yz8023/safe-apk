package com.adfxcbnm.frostshell.config

import com.adfxcbnm.frostshell.util.FrostKeyUtils
import com.adfxcbnm.frostshell.util.FrostStringUtils
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.annotation.JSONField
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

class FrostShellConfig {
    private var applicationName: String? = null
    private var appComponentFactoryName: String? = null

    @JSONField(name = "signature")
    private var signatureConfig: SignatureConfig? = null

    @JSONField(name = "shellPkgName")
    private var shellPackageName: String? = null

    @JSONField(name = "app_sign_sha256")
    private var appSignSha256: String? = null

    @JSONField(name = "dex_sign")
    private var dexSign: String? = null

    @JSONField(serialize = false)
    private var insnsCryptKey: ByteArray? = null

    @JSONField(name = "risk_check_flags")
    private var riskCheckFlags: Int = 0

    @JSONField(name = "insns_store")
    private var insnsStoreName: String? = null

    @JSONField(name = "dexes_zip")
    private var dexesZipName: String? = null

    @JSONField(name = "tail_salt")
    private var tailSaltHex: String? = null

    @JSONField(name = "tail_pad")
    private var tailPad: Int = 0

    @JSONField(name = "key_shard4")
    private var keyShard4Hex: String? = null

    fun getShellPackageName(): String? = shellPackageName

    fun setShellPackageName(shellPackageName: String?) {
        this.shellPackageName = shellPackageName
    }

    fun init(shellPackageName: String) {
        val shellConfig = getInstance()
        shellConfig.setShellPackageName(shellPackageName)
        init(shellConfig)
    }

    fun init(shellConfig: FrostShellConfig) {
        this.shellPackageName =
            if (FrostStringUtils.isEmpty(shellConfig.getShellPackageName())) "com/ironshell/runtime"
            else shellConfig.getShellPackageName()
        this.signatureConfig = shellConfig.getSignatureConfig()
        this.appSignSha256 = shellConfig.getAppSignSha256()
        this.riskCheckFlags = shellConfig.getRiskCheckFlags()
    }

    fun getSlashShellPackageName(): String = getShellPackageName().orEmpty().replace(".", "/")

    fun getAppComponentFactoryName(): String? = appComponentFactoryName

    fun setAppComponentFactoryName(appComponentFactoryName: String?) {
        this.appComponentFactoryName = appComponentFactoryName
    }

    fun getApplicationName(): String? = applicationName

    fun setApplicationName(applicationName: String?) {
        this.applicationName = applicationName
    }

    fun getSignatureConfig(): SignatureConfig? = signatureConfig

    fun setSignatureConfig(signatureConfig: SignatureConfig?) {
        this.signatureConfig = signatureConfig
    }

    fun getAppSignSha256(): String? = appSignSha256

    fun setAppSignSha256(appSignSha256: String?) {
        this.appSignSha256 = appSignSha256
    }

    fun getDexSign(): String? = dexSign

    fun setDexSign(dexSign: String?) {
        this.dexSign = dexSign
    }

    fun getInsnsCryptKey(): ByteArray? = insnsCryptKey

    fun setInsnsCryptKey(insnsCryptKey: ByteArray?) {
        this.insnsCryptKey = insnsCryptKey
    }

    fun getRiskCheckFlags(): Int = riskCheckFlags

    fun setRiskCheckFlags(riskCheckFlags: Int) {
        this.riskCheckFlags = riskCheckFlags
    }

    fun getInsnsStoreName(): String? = insnsStoreName

    fun setInsnsStoreName(insnsStoreName: String?) {
        this.insnsStoreName = insnsStoreName
    }

    fun getDexesZipName(): String? = dexesZipName

    fun setDexesZipName(dexesZipName: String?) {
        this.dexesZipName = dexesZipName
    }

    fun getTailSaltHex(): String? = tailSaltHex

    fun setTailSaltHex(tailSaltHex: String?) {
        this.tailSaltHex = tailSaltHex
    }

    fun getTailPad(): Int = tailPad

    fun setTailPad(tailPad: Int) {
        this.tailPad = tailPad
    }

    fun getKeyShard4Hex(): String? = keyShard4Hex

    fun setKeyShard4Hex(keyShard4Hex: String?) {
        this.keyShard4Hex = keyShard4Hex
    }

    fun randomizeForProtect() {
        this.insnsStoreName = FrostKeyUtils.randomName(16)
        this.dexesZipName = FrostKeyUtils.randomName(12) + ".zip"
        this.tailSaltHex = FrostKeyUtils.toHex(FrostKeyUtils.randomBytes(4))
        this.tailPad = 64 + SecureRandom().nextInt(64)
    }

    fun getJniSlashClassName(): String = String.format(Locale.US, "%s/%s", getSlashShellPackageName(), "JniBridge")

    fun getJniClassNameSig(): String = String.format(Locale.US, "L%s;", getJniSlashClassName())

    fun toJson(): String {
        val jsonObject = JSONObject()
        jsonObject.put("app_name", getApplicationName())
        jsonObject.put("acf_name", getAppComponentFactoryName())
        val jniClassName = getJniSlashClassName()
        jsonObject.put("jni_cls_name", jniClassName)
        if (!FrostStringUtils.isEmpty(getAppSignSha256())) {
            jsonObject.put("app_sign_sha256", getAppSignSha256())
        }
        jsonObject.put("dex_sign", getDexSign())
        jsonObject.put("risk_check_flags", getRiskCheckFlags())
        jsonObject.put("insns_store", getInsnsStoreName())
        jsonObject.put("dexes_zip", getDexesZipName())
        jsonObject.put("tail_salt", getTailSaltHex())
        jsonObject.put("tail_pad", getTailPad())
        jsonObject.put("key_shard4", getKeyShard4Hex())
        return jsonObject.toString()
    }

    override fun toString(): String = JSON.toJSONString(this)

    class SignatureConfig {
        @JSONField(name = "keystore")
        private var keystore: String? = null

        @JSONField(name = "alias")
        private var alias: String? = null

        @JSONField(name = "storepass")
        private var storePassword: String? = null

        @JSONField(name = "keypass")
        private var keyPassword: String? = null

        fun getKeystore(): String? = keystore

        fun setKeystore(keystore: String?) {
            this.keystore = keystore
        }

        fun getAlias(): String? = alias

        fun setAlias(alias: String?) {
            this.alias = alias
        }

        fun getStorePassword(): String? = storePassword

        fun setStorePassword(storePassword: String?) {
            this.storePassword = storePassword
        }

        fun getKeyPassword(): String? = keyPassword

        fun setKeyPassword(keyPassword: String?) {
            this.keyPassword = keyPassword
        }

        override fun toString(): String = JSON.toJSONString(this)
    }

    companion object {
        private val INSTANCE = FrostShellConfig()

        @JvmStatic
        fun getInstance(): FrostShellConfig = INSTANCE
    }
}
