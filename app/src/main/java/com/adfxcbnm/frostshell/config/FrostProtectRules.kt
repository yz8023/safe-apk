package com.adfxcbnm.frostshell.config

object FrostProtectRules {

    private val ACCESS_MODIFIERS = setOf(
        "public", "private", "protected", "static", "final", "synthetic",
        "native", "abstract", "synchronized", "strictfp", "constructor",
        "declared-synchronized", "bridge", "varargs", "default"
    )

    private data class ParsedMethodRule(
        val className: String?, // null 表示任意类
        val methodName: String,
        val descriptor: String // "" 表示未指定签名，仅按方法名匹配
    )

    /**
     * 方法与字段匹配规则。格式: "类全限定名.成员名" 或 "类全限定名.*"，
     * 类名使用 dex 内部格式如 "Lcom/foo/Bar;"。
     * 空表示不过滤（保留全量抽取行为）。
     */
    private var memberRules: Array<String> = arrayOf()

    @Synchronized fun setMemberRules(rules: Array<String>) {
        memberRules = rules
    }

    /**
     * 方法级抽取过滤。为空则全部方法都参与抽取（保持原行为）；
     * 非空时只有命中的方法才被抽取，未命中的方法保留原始指令（不进入指令池）。
     *
     * 支持 smali `.method` 签名格式（方法名+签名精确匹配），如：
     *   .method public static onClick(I)V
     * 含签名的规则要求方法 descriptor（"(I)V"）完全一致；未含签名时仅按方法名匹配。
     */
    @Synchronized fun shouldExtractMethod(className: String, methodName: String): Boolean {
        return shouldExtractMethod(className, methodName, "")
    }

    @Synchronized fun shouldExtractMethod(className: String, methodName: String, methodDescriptor: String): Boolean {
        if (memberRules.isEmpty()) return true
        return matchesMemberRule(className, methodName, methodDescriptor)
    }

    private fun matchesMemberRule(className: String, methodName: String, methodDescriptor: String = ""): Boolean {
        for (rule in memberRules) {
            val trimmed = rule.trim()
            if (trimmed.isEmpty()) continue
            // smali `.method` 签名格式（含可选类前缀 `Lxxx;.method ...`）
            if (Regex("\\.method($|\\s)").containsMatchIn(trimmed)) {
                if (matchesMethodRule(trimmed, className, methodName, methodDescriptor)) return true
                continue
            }
            if (rule.startsWith("regex:")) {
                val patternText = rule.substring("regex:".length).trim()
                if (patternText.isEmpty()) continue
                val candidate = "$className.$methodName"
                try {
                    if (Regex(patternText, RegexOption.IGNORE_CASE).matches(candidate)) return true
                } catch (e: Exception) { }
                continue
            }
            val semiIndex = rule.indexOf(';')
            if (semiIndex < 0) {
                // 纯方法名关键词/正则规则，如 .*vip.* ，匹配任意类中的方法名
                try {
                    if (Regex(rule).matches(methodName)) return true
                } catch (e: Exception) { }
                continue
            }
            val ruleClass = rule.substring(0, semiIndex + 1)
            val classMatched = if (ruleClass.endsWith(";")) ruleClass == className
            else {
                try { Regex(ruleClass, RegexOption.IGNORE_CASE).matches(className) } catch (e: Exception) { false }
            }
            if (!classMatched) continue
            val ruleMember = rule.substring(semiIndex + 1).removePrefix(".")
            if (ruleMember.isEmpty()) continue
            if (ruleMember == "*" || ruleMember == methodName) return true
            try {
                if (Regex(ruleMember).matches(methodName)) return true
            } catch (e: Exception) { }
        }
        return false
    }

    /**
     * smali `.method` 签名格式规则匹配。
     * 解析形如 `.method public static onClick(I)V` 的规则，支持：
     *  - 方法名精确 / 正则（含通配符时按正则）
     *  - 签名精确匹配（descriptor 例如 "(I)V"，完整参数+返回类型）
     *  - 可选类限定：`Lcom/foo/Bar;.method public static onClick(I)V`
     */
    private fun matchesMethodRule(rule: String, className: String, methodName: String, methodDescriptor: String): Boolean {
        val parsed = parseMethodRule(rule) ?: return false
        // 类限定匹配（可空）：null 表示任意类
        if (parsed.className != null) {
            val classMatched = when {
                parsed.className == className -> true
                parsed.className.contains('*') || parsed.className.contains('?') ->
                    try { Regex(parsed.className).matches(className) } catch (e: Exception) { false }
                else -> false
            }
            if (!classMatched) return false
        }
        // 方法名匹配：精确或正则（含通配符时）
        val nameMatched = if (parsed.methodName.contains('*') || parsed.methodName.contains('?')) {
            try { Regex(parsed.methodName, RegexOption.IGNORE_CASE).matches(methodName) } catch (e: Exception) { false }
        } else {
            parsed.methodName == methodName
        }
        if (!nameMatched) return false
        // 签名可选：为空则仅按方法名匹配；非空需精确一致
        if (parsed.descriptor.isNotEmpty() && parsed.descriptor != methodDescriptor) return false
        return true
    }

    /**
     * 解析 `.method public static onClick(I)V` 格式。
     * 返回类名（可空）、方法名、descriptor（如 "(I)V"，含返回类型）。
     * 支持两种写法：`.method public static onClick(I)V` 单 token 含签名；
     * 以及带类名限定 `Lcom/foo/Bar;.method onClick(I)V`。
     */
    private fun parseMethodRule(rule: String): ParsedMethodRule? {
        var r = rule.trim()
        val methodIdx = r.indexOf(".method")
        if (methodIdx < 0) return null
        // 提取类前缀：`Lxxx/yyy;.method ...` 中 `L` 到 `;` 为类名
        var className: String? = null
        if (methodIdx > 0) {
            val classMarker = r.lastIndexOf("L", methodIdx)
            if (classMarker >= 0) {
                val semi = r.indexOf(';', classMarker)
                if (semi >= 0 && semi < methodIdx) {
                    className = r.substring(classMarker, semi + 1)
                }
            }
        }
        r = r.substring(methodIdx).removePrefix(".method").trim()
        // 去除访问修饰符
        val tokens = r.split(' ').filter { it.isNotEmpty() }
        val first = tokens.firstOrNull { !ACCESS_MODIFIERS.contains(it) } ?: return null
        val openIdx = first.indexOf('(')
        val name: String
        val descriptor: String
        if (openIdx >= 0) {
            // 单 token 形式：onClick(I)V
            name = first.substring(0, openIdx)
            descriptor = first.substring(openIdx) // "(I)V"
            if (descriptor.length < 3) return null
        } else {
            name = first
            // 两段式：`onClick` + `(I)V`
            var rest = r.removePrefix(first).trim()
            if (rest.startsWith("(")) {
                val close = rest.indexOf(')')
                descriptor = if (close >= 0) rest.substring(0, close + 1) else ""
            } else {
                descriptor = ""
            }
            if (descriptor.isEmpty()) return ParsedMethodRule(className, name, "")
        }
        return ParsedMethodRule(className, name, normalizeDescriptor(descriptor))
    }

    private fun normalizeDescriptor(descriptor: String): String {
        // 将 smali 描述符中的空格去掉，如 "(I) V" -> "(I)V"
        return descriptor.replace(" ", "")
    }

    private var excludeRules: Array<String> = arrayOf(
        "Lafzkl/development/.*", "Landroid/.*", "Landroid/arch/.*", "Landroid/content/.*", "Landroid/opengl/.*", "Landroid/support/.*", "Landroid/widget/.*", "Landroidx/.*",
        "Lanet/channel/.*", "Lanetwork/channel/.*", "Lanywheresoftware/.*", "Lau/com/bytecode/opencsv/.*", "Lbiz/neoline/.*", "Lbutterknife/.*", "Lch/boye/httpclientandroidlib/.*", "Lch/qos/logback/.*",
        "Lcn/domob/.*", "Lcn/jiguang/.*", "Lcn/jpush/android/.*", "Lcn/sharesdk/.*", "Lcn/smssdk/.*", "Lcn/uc/gamesdk/.*", "Lcom/ace/universalimageloader/.*", "Lcom/actionbarsherlock/.*",
        "Lcom/activeandroid/.*", "Lcom/adfonic/.*", "Lcom/adjust/.*", "Lcom/admob/.*", "Lcom/adobe/.*", "Lcom/adsdk/.*", "Lcom/adsmogo/.*", "Lcom/adtech/.*",
        "Lcom/adwhirl/.*", "Lcom/adwo/.*", "Lcom/alibaba/.*", "Lcom/alibaba/fastjson/.*", "Lcom/alibaba/mtl/.*", "Lcom/alimama/mobile/.*", "Lcom/alipay/.*", "Lcom/aliyun/.*",
        "Lcom/amap/.*", "Lcom/amap/api/.*", "Lcom/amazon/ags/.*", "Lcom/amazon/device/.*", "Lcom/amazon/identity/.*", "Lcom/amazon/inapp/.*", "Lcom/amazon/insights/.*", "Lcom/amazonaws/.*",
        "Lcom/android/vending/.*", "Lcom/android/volley/.*", "Lcom/androidplot/.*", "Lcom/androidquery/.*", "Lcom/ansca/corona/.*", "Lcom/anzhi/market/.*", "Lcom/appbrain/.*", "Lcom/appbyme/.*",
        "Lcom/apperhand/.*", "Lcom/appflood/.*", "Lcom/applovin/.*", "Lcom/appmakr/.*", "Lcom/appodeal/.*", "Lcom/appquanta/.*", "Lcom/appsflyer/.*", "Lcom/appyet/.*",
        "Lcom/astuetz/.*", "Lcom/auth0/android/.*", "Lcom/auth0/jwt/.*", "Lcom/autonavi/.*", "Lcom/aviary/.*", "Lcom/avos/avoscloud/.*", "Lcom/badlogic/.*", "Lcom/baidu/android/.*",
        "Lcom/baidu/appx/.*", "Lcom/baidu/autoupdatesdk/.*", "Lcom/baidu/bdgame/.*", "Lcom/baidu/cyberplayer/.*", "Lcom/baidu/frontia/.*", "Lcom/baidu/location/.*", "Lcom/baidu/mapapi/.*", "Lcom/baidu/mobads/.*",
        "Lcom/baidu/mobstat/.*", "Lcom/baidu/mtjstatsdk/.*", "Lcom/baidu/navi/.*", "Lcom/baidu/navisdk/.*", "Lcom/baidu/paysdk/.*", "Lcom/baidu/platform/.*", "Lcom/baidu/sapi2/.*", "Lcom/baidu/searchsdk/.*",
        "Lcom/baidu/tiebasdk/.*", "Lcom/baidu/wallet/.*", "Lcom/bda/controller/.*", "Lcom/bea/xml/stream/.*", "Lcom/biznessapps/.*", "Lcom/blankj/utilcode/.*", "Lcom/bugsense/.*", "Lcom/bumptech/glide/.*",
        "Lcom/chartboost/sdk/.*", "Lcom/chineseall/reader/.*", "Lcom/commonsware/cwac/merge/.*", "Lcom/commonsware/cwac/sacklist/.*", "Lcom/comscore/.*", "Lcom/conduit/app/.*", "Lcom/coolcloud/uac/.*", "Lcom/coremedia/iso/.*",
        "Lcom/crashlytics/android/.*", "Lcom/crowdcompass/.*", "Lcom/daggertech/.*", "Lcom/daimajia/androidanimations/.*", "Lcom/data/.*", "Lcom/db4o/.*", "Lcom/deploygate/.*", "Lcom/digits/sdk/vcard/.*",
        "Lcom/doapps/.*", "Lcom/dobao/.*", "Lcom/dropbox/.*", "Lcom/duoku/.*", "Lcom/easemob/.*", "Lcom/eclipsesource/.*", "Lcom/esotericsoftware/.*", "Lcom/espian/showcaseview/.*",
        "Lcom/everyplay/.*", "Lcom/external/.*", "Lcom/facebook/.*", "Lcom/fasterxml/.*", "Lcom/flurry/android/.*", "Lcom/flurry/org/.*", "Lcom/flurry/sdk/.*", "Lcom/goodbarber/.*",
        "Lcom/google/.*", "Lcom/google/ads/.*", "Lcom/google/analytics/.*", "Lcom/google/android/apps/dashclock/api/.*", "Lcom/google/android/exoplayer/.*", "Lcom/google/android/gcm/.*", "Lcom/google/android/gms/.*", "Lcom/google/android/material/.*",
        "Lcom/google/android/net/.*", "Lcom/google/android/vending/.*", "Lcom/google/android/youtube/.*", "Lcom/google/api/.*", "Lcom/google/appinventor/.*", "Lcom/google/common/.*", "Lcom/google/firebase/.*", "Lcom/google/gdata/.*",
        "Lcom/google/gson/.*", "Lcom/google/i18n/.*", "Lcom/google/inject/.*", "Lcom/google/maps/android/.*", "Lcom/google/protobuf/.*", "Lcom/google/tagmanager/.*", "Lcom/google/thirdparty/.*", "Lcom/google/zxing/.*",
        "Lcom/googlecode/apdfviewer/.*", "Lcom/handmark/pulltorefresh/.*", "Lcom/heyzap/.*", "Lcom/huawei/android/.*", "Lcom/huawei/ecs/.*", "Lcom/huawei/hms/.*", "Lcom/huntmads/.*", "Lcom/iapppay/.*",
        "Lcom/ibm/icu/.*", "Lcom/idddx/.*", "Lcom/idddx/sdk/.*", "Lcom/iflytek/speech/.*", "Lcom/iflytek/sunflower/.*", "Lcom/iflytek/thirdparty/.*", "Lcom/igaworks/.*", "Lcom/igexin/.*",
        "Lcom/immersion/hapticmediasdk/.*", "Lcom/inmobi/.*", "Lcom/inneractive/.*", "Lcom/ironsource/.*", "Lcom/iwanvi/.*", "Lcom/j256/.*", "Lcom/jakewharton/disklrucache/.*", "Lcom/jasonkostempski/.*",
        "Lcom/jayway/jsonpath/.*", "Lcom/jcodecraeer/xrecyclerview/progressindicator/.*", "Lcom/jcraft/jsch/.*", "Lcom/jirbo/adcolony/.*", "Lcom/jiubang/commerce/.*", "Lcom/jumptap/.*", "Lcom/kakao/talk/.*", "Lcom/kbeanie/imagechooser/.*",
        "Lcom/koushikdutta/async/.*", "Lcom/kuguo/ad/.*", "Lcom/leadbolt/.*", "Lcom/lenovo/lps/.*", "Lcom/letv/adlib/.*", "Lcom/letv/datastatistics/.*", "Lcom/letv/lepaysdk/.*", "Lcom/letv/sdk/.*",
        "Lcom/letvcloud/.*", "Lcom/lidroid/xutils/.*", "Lcom/loopj/.*", "Lcom/madhouse/android/ads/.*", "Lcom/magook/.*", "Lcom/magtab/.*", "Lcom/mashape/relocation/.*", "Lcom/maximono/.*",
        "Lcom/meitu/android/trivialdrivesample/.*", "Lcom/melnykov/fab/.*", "Lcom/milkmangames/extensions/android/coremobile/.*", "Lcom/millennialmedia/.*", "Lcom/mindprod/ledatastream/.*", "Lcom/mob/commons/.*", "Lcom/mob/tools/.*", "Lcom/mobcent/.*",
        "Lcom/mobclick/android/.*", "Lcom/mobeta/.*", "Lcom/mobfox/.*", "Lcom/mobisage/android/.*", "Lcom/mongodb/.*", "Lcom/mopub/.*", "Lcom/my/target/core/ui/.*", "Lcom/myappengine/.*",
        "Lcom/myshare/dynamic/.*", "Lcom/naef/jnlua/.*", "Lcom/nd/dianjin/.*", "Lcom/neatplug/u3d/plugins/.*", "Lcom/nerd/TapdaqUnityPlugin/.*", "Lcom/netease/nim/.*", "Lcom/netease/ntespm/.*", "Lcom/newrelic/.*",
        "Lcom/ngpinc/.*", "Lcom/nhaarman/listviewanimations/.*", "Lcom/nineoldandroids/.*", "Lcom/nokia/payment/.*", "Lcom/nostra13/.*", "Lcom/nuance/.*", "Lcom/nv/support/pulltorefresh/.*", "Lcom/onbarcode/.*",
        "Lcom/openfeint/.*", "Lcom/outlinegames/.*", "Lcom/papaya/.*", "Lcom/parse/.*", "Lcom/path/android/jobqueue/.*", "Lcom/paypal/.*", "Lcom/phonegap/.*", "Lcom/playhaven/.*",
        "Lcom/pocketchange/.*", "Lcom/polites/.*", "Lcom/pollfish/.*", "Lcom/prime31/.*", "Lcom/purplebrain/adbuddiz/.*", "Lcom/qihoo/gamecenter/.*", "Lcom/qihoo/psdk/.*", "Lcom/qihoo/stat/.*",
        "Lcom/qihoopp/framework/.*", "Lcom/qihoopp/qcoinpay/.*", "Lcom/qmoney/ui/.*", "Lcom/qoppa/.*", "Lcom/qwapi/.*", "Lcom/radiusnetworks/.*", "Lcom/readystatesoftware/sqliteasset/.*", "Lcom/renn/rennsdk/.*",
        "Lcom/revmob/.*", "Lcom/samsung/spen/.*", "Lcom/scoreloop/.*", "Lcom/sec/android/iap/.*", "Lcom/shengliwang/CarLogo/.*", "Lcom/sina/sso/.*", "Lcom/sina/weibo/.*", "Lcom/skplanet/.*",
        "Lcom/slidingmenu/.*", "Lcom/smaato/.*", "Lcom/spoledge/.*", "Lcom/sponsorpay/.*", "Lcom/squareup/okhttp/.*", "Lcom/squareup/otto/.*", "Lcom/squareup/picasso/.*", "Lcom/squareup/pollexor/.*",
        "Lcom/squareup/seismic/.*", "Lcom/squareup/tape/.*", "Lcom/squareup/timessquare/.*", "Lcom/startapp/.*", "Lcom/stericson/RootTools/.*", "Lcom/subsplash/.*", "Lcom/sun/activation/.*", "Lcom/sun/mail/.*",
        "Lcom/supersonic/adapters/supersonicads/.*", "Lcom/supersonic/mediationsdk/.*", "Lcom/supersonicads/sdk/.*", "Lcom/taobao/accs/.*", "Lcom/tapdaq/sdk/.*", "Lcom/tapdaq/tapdaqunityads/.*", "Lcom/tapit/.*", "Lcom/tapjoy/.*",
        "Lcom/tencent/android/tpush/.*", "Lcom/tencent/bugly/.*", "Lcom/tencent/common/.*", "Lcom/tencent/connect/.*", "Lcom/tencent/jsutil/.*", "Lcom/tencent/lbs/.*", "Lcom/tencent/map/.*", "Lcom/tencent/mm/.*",
        "Lcom/tencent/mobwin/.*", "Lcom/tencent/mta/.*", "Lcom/tencent/open/.*", "Lcom/tencent/plus/.*", "Lcom/tencent/qqconnect/.*", "Lcom/tencent/qzone/.*", "Lcom/tencent/record/.*", "Lcom/tencent/sdkutil/.*",
        "Lcom/tencent/smtt/.*", "Lcom/tencent/stat/.*", "Lcom/tencent/tauth/.*", "Lcom/tencent/utils/.*", "Lcom/tencent/webnet/.*", "Lcom/tencent/weibo/.*", "Lcom/tencent/weiyun/.*", "Lcom/tencent/wpa/.*",
        "Lcom/tencent/wxop/.*", "Lcom/tendcloud/tenddata/.*", "Lcom/tencent/mmkv/.*", "Lcom/tencent/matrix/.*", "Lcom/threatmetrix/.*", "Lcom/turbomanage/httpclient/.*", "Lcom/umeng/.*", "Lcom/umeng/analytics/.*",
        "Lcom/umeng/api/.*", "Lcom/umeng/common/.*", "Lcom/umeng/fb/.*", "Lcom/umeng/message/.*", "Lcom/umeng/newxp/view/.*", "Lcom/umeng/socialize/.*", "Lcom/umeng/socom/.*", "Lcom/umeng/update/.*",
        "Lcom/umeng/xp/.*", "Lcom/unionpay/mobile/android/.*", "Lcom/unionpay/sdk/.*", "Lcom/unionpay/tsmservice/.*", "Lcom/unity3d/ads/.*", "Lcom/urbanairship/.*", "Lcom/uservoice/uservoicesdk/.*", "Lcom/ut/mini/.*",
        "Lcom/uzmap/pkg/uzkit/.*", "Lcom/vercoop/.*", "Lcom/verizon/.*", "Lcom/viewpagerindicator/.*", "Lcom/vividsolutions/jts/.*", "Lcom/vjianke/pulltorefresh/.*", "Lcom/vl/pulltorefresh/.*", "Lcom/vpon/.*",
        "Lcom/vungle/.*", "Lcom/wm/pulltorefresh/.*", "Lcom/xiaomi/.*", "Lcom/xiaomi/mipush/sdk/.*", "Lcom/xiaomi/push/.*", "Lcom/xiaomi/smack/.*", "Lcom/xiaomi/xmpush/.*", "Lcom/yandex/mobile/ads/.*",
        "Lcom/yixia/.*", "Lcz/msebera/android/httpclient/.*", "Lde/greenrobot/dao/.*", "Lde/greenrobot/event/.*", "Lde/keyboardsurfer/.*", "Lfr/castorfle/.*", "Lgnu/commonlisp/.*", "Lgnu/ecmascript/.*",
        "Lgnu/kawa/.*", "Lgnu/mapping/.*", "Lgnu/xml/.*", "Lhirondelle/date4j/.*", "Lim/quar/.*", "Linfo/guardianproject/bouncycastle/.*", "Lio/dcloud/.*", "Lio/fabric/sdk/.*",
        "Lio/flutter/.*",
        "Lit/gmariotti/cardslib/.*", "Lit/sauronsoftware/ftp4j/.*", "Ljavax/.*", "Ljavax/activation/.*", "Ljavax/annotation/.*", "Ljavax/jmdns/.*", "Ljavax/mail/.*", "Ljavax/servlet/.*",
        "Ljavax/ws/.*", "Ljunit/.*", "Lkankan/wheel/.*", "Lkotlin/.*", "Lme/apla/cordova/.*", "Lme/zhanghai/android/.*", "Lmicrosoft/mappoint/.*", "Lmobisocial/omlet/.*",
        "Lmobisocial/omlib/.*", "Lmono/android/.*", "Lnet/authorize/.*", "Lnet/hockeyapp/android/.*", "Lnet/lingala/zip4j/.*", "Lnet/minidev/json/.*", "Lnet/simonvt/menudrawer/.*", "Lnet/sourceforge/pinyin4j/.*",
        "Lnet/sourceforge/zbar/.*", "Lnet/tsz/afinal/.*", "Lnet/youmi/.*", "Lnl/siegmann/epublib/.*", "Loauth/signpost/.*", "Lokhttp3/.*", "Lokio/.*", "Lopentk/.*",
        "Lorg/achartengine/.*", "Lorg/anddev/andengine/.*", "Lorg/andengine/.*", "Lorg/android/agoo/.*", "Lorg/androidannotations/.*", "Lorg/apache/.*", "Lorg/apache/commons/.*", "Lorg/apache/cordova/.*",
        "Lorg/apache/harmony/.*", "Lorg/apache/http/.*", "Lorg/apache/james/.*", "Lorg/apache/log4j/.*", "Lorg/apache/tools/ant/.*", "Lorg/appcelerator/.*", "Lorg/aspectj/.*", "Lorg/bouncycastle/.*",
        "Lorg/ccil/cowan/tagsoup/.*", "Lorg/cocos2d/.*", "Lorg/cocos2dx/.*", "Lorg/codehaus/jackson/.*", "Lorg/dom4j/.*", "Lorg/ebookdroid/.*", "Lorg/geometerplus/zlibrary/.*", "Lorg/hamcrest/.*",
        "Lorg/htmlcleaner/.*", "Lorg/ice4j/.*", "Lorg/ini4j/.*", "Lorg/jaudiotagger/.*", "Lorg/java_websocket/.*", "Lorg/jaxen/.*", "Lorg/jdeferred/.*", "Lorg/jdom/.*",
        "Lorg/jdom2/.*", "Lorg/jivesoftware/.*", "Lorg/joda/time/.*", "Lorg/json/.*", "Lorg/jsoup/.*", "Lorg/junit/.*", "Lorg/kde/necessitas/ministro/.*", "Lorg/kobjects/.*",
        "Lorg/ksoap2/.*", "Lorg/kxml2/.*", "Lorg/lobobrowser/.*", "Lorg/mapsforge/.*", "Lorg/metalev/multitouch/.*", "Lorg/mozilla/intl/chardet/.*", "Lorg/mozilla/javascript/.*", "Lorg/mozilla/universalchardet/.*",
        "Lorg/msgpack/.*", "Lorg/nexage/sourcekit/mraid/.*", "Lorg/ocpsoft/prettytime/.*", "Lorg/openudid/.*", "Lorg/osmdroid/.*", "Lorg/scribe/.*", "Lorg/simpleframework/.*", "Lorg/slf4j/.*",
        "Lorg/spongycastle/.*", "Lorg/springframework/.*", "Lorg/tukaani/xz/.*", "Lorg/vudroid/.*", "Lorg/xbill/DNS/.*", "Lorg/xinhua/analytics/.*", "Lorg/xmlpull/.*", "Lorg/yaml/snakeyaml/.*",
        "Lorg/zywx/.*", "Lpagerslidingtabstrip/.*", "Lpdftron/.*", "Lpl/polidea/.*", "Lpts/PhoneGap/.*", "Lretrofit/.*", "Lretrofit2/.*", "Lroboguice/.*",
        "Lrx/.*", "Lrx/internal/.*", "Lsafiap/framework/.*", "Lshared_presage/com/.*", "Lti/imagefactory/.*", "Lti/modules/titanium/.*", "Ltwitter4j/.*", "Luk/co/senab/actionbarpulltorefresh/.*",
        "Lv2/com/playhaven/.*", "Lxamarin/.*", "Lzfee/sdk/.*",
    )

    @JvmStatic
    fun getInstance(): FrostProtectRules = this

    @Synchronized fun setExcludeRules(rules: Array<String>) {
        // Flutter 引擎类必须无条件保护：libflutter.so/libapp.so 按名 FindClass 定位 io.flutter.*，
        // 类被重命名/字段被改名/方法被抽取都会导致启动即崩。用户规则文件覆盖时强制保留此条。
        val merged = rules.toMutableList()
        if (merged.none { it.contains("io/flutter") }) merged.add("Lio/flutter/.*")
        excludeRules = merged.toTypedArray()
    }

    @Synchronized fun matchRules(fullClassDefName: String): Boolean {
        for (rule in excludeRules) {
            if (fullClassDefName.matches(Regex(rule))) return true
        }
        return false
    }
}
