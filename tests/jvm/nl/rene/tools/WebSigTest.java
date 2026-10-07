package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** De handtekening in update/update.json (gemaakt door build.sh) klopt met de controle in de app (Updater.sigMessage). */
public class WebSigTest {
    public static void main(String[] a) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("update/update.json")), StandardCharsets.UTF_8);
        int code = Integer.parseInt(field(json, "versionCode"));
        String web = field(json, "webSha256"), start = field(json, "startSha256");
        String sig = json.contains("\"webSig\"") ? "aanwezig" : null;
        char[] pass = new String(Files.readAllBytes(Paths.get("keys/keystore.pass")), StandardCharsets.UTF_8).trim().toCharArray();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get("keys/release.p12"))) { ks.load(in, pass); }
        java.security.cert.Certificate cert = ks.getCertificate("release");
        boolean ok = sig == null; // v1 wordt niet meer meegestuurd
        // v2: ook nativeLevel en APK zitten in de handtekening
        int nat = Integer.parseInt(field(json, "nativeLevel"));
        String apk = field(json, "apkSha256"), s2 = field(json, "webSig2");
        boolean ok2 = verify(cert, Updater.sigMessage2(code, nat, web, start, apk), s2);
        boolean lowered = verify(cert, Updater.sigMessage2(code, nat - 1, web, start, apk), s2);
        boolean forgedSha = verify(cert, Updater.sigMessage2(code, nat, (web.charAt(0) == '0' ? "1" : "0") + web.substring(1), start, apk), s2);
        boolean oldAsNew = forgedSha;
        System.out.println((ok ? "✓" : "✗") + " oude v1-handtekening niet meer meegestuurd");
        System.out.println((ok2 ? "✓" : "✗") + " v2-handtekening klopt");
        System.out.println((!lowered ? "✓" : "✗") + " verlaagd nativeLevel afgekeurd");
        System.out.println((!oldAsNew ? "✓" : "✗") + " gewijzigd controlegetal afgekeurd (v2)");
        boolean all = ok && ok2 && !lowered && !oldAsNew;
        System.out.println(all ? "Alle handtekening-tests geslaagd" : "Handtekening-test mislukt");
        System.exit(all ? 0 : 1);
    }

    static boolean verify(java.security.cert.Certificate cert, String msg, String sig) throws Exception {
        java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
        v.initVerify(cert.getPublicKey());
        v.update(msg.getBytes(StandardCharsets.UTF_8));
        return v.verify(java.util.Base64.getMimeDecoder().decode(sig));
    }

    static String field(String json, String k) {
        int i = json.indexOf("\"" + k + "\"");
        int c = json.indexOf(':', i) + 1;
        while (json.charAt(c) == ' ') c++;
        if (json.charAt(c) == '"') return json.substring(c + 1, json.indexOf('"', c + 1));
        int e = c; while (Character.isDigit(json.charAt(e))) e++;
        return json.substring(c, e);
    }
}
