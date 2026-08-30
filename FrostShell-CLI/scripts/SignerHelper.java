import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * SignerHelper — 复用 ironshell.jar 内置的 com.android.apksig 对 APK 重新签名（v1+v2）。
 *
 * 编译（需与 ironshell.jar 同目录或指定 -cp）：
 *     javac -cp ironshell.jar -d . SignerHelper.java
 *
 * 运行：
 *     java -cp ironshell.jar:. SignerHelper \
 *         <keystore> <alias> <storepass> <keypass> <in.apk> <out.apk> <sha256-out.txt>
 *
 * 参数说明：
 *     <keystore>    签名 keystore 路径（JKS / PKCS12 自动识别）
 *     <alias>       keystore 内密钥别名（不存在则自动取第一个 key entry）
 *     <storepass>   keystore 密码
 *     <keypass>     密钥密码
 *     <in.apk>      待签名 APK
 *     <out.apk>     签名输出 APK
 *     <sha256-out>  输出文件，写入签名证书的 SHA-256（小写 hex），用于 features.cfg 签名基线
 */
public class SignerHelper {

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("usage: SignerHelper <keystore> <alias> <storepass> <keypass> <in.apk> <out.apk> <sha256-out> [cert-only]");
            System.exit(1);
        }
        String ksPath = args[0];
        String alias = args[1];
        String storepass = args[2];
        String keypass = args[3];
        File input = new File(args[4]);
        File output = new File(args[5]);
        File shaFile = new File(args[6]);

        KeyStore ks = loadKeyStore(ksPath, storepass);
        if (!ks.containsAlias(alias)) {
            java.util.Enumeration<String> en = ks.aliases();
            while (en.hasMoreElements()) {
                String a = en.nextElement();
                if (ks.isKeyEntry(a)) {
                    alias = a;
                    break;
                }
            }
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keypass.toCharArray());
        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            chain = new Certificate[]{ks.getCertificate(alias)};
        }
        X509Certificate cert = (X509Certificate) chain[0];

        String sha = sha256Hex(cert.getEncoded());
        try (PrintWriter pw = new PrintWriter(shaFile, "UTF-8")) {
            pw.print(sha);
        }

        boolean certOnly = args.length >= 8 && args[7].equals("cert-only");
        if (certOnly) {
            System.out.println("cert-only ok sha256=" + sha);
            return;
        }

        List<X509Certificate> certs = Collections.singletonList(cert);
        ApkSigner.SignerConfig signerConfig =
                new ApkSigner.SignerConfig.Builder(alias, key, certs).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(26)
                .build();
        signer.sign();
        System.out.println("signed ok -> " + output.getAbsolutePath() + " sha256=" + sha);
    }

    private static KeyStore loadKeyStore(String path, String storepass) throws Exception {
        File f = new File(path);
        // 先按 PKCS12 试，失败再按 JKS
        for (String type : new String[]{"PKCS12", "JKS"}) {
            KeyStore ks = KeyStore.getInstance(type);
            try (FileInputStream fis = new FileInputStream(f)) {
                ks.load(fis, storepass.toCharArray());
                return ks;
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("无法加载 keystore: " + path);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
