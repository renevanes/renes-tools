package nl.rene.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** De handtekening in update/update.json (gemaakt door build.sh) klopt met de controle in de app (Updater.sigMessage). */
public class WebSigTest {
    public static void main(String[] a) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("update/update.json")), StandardCharsets.UTF_8);
        int code = Integer.parseInt(field(json, "versionCode"));
        String web = field(json, "webSha256"), start = field(json, "startSha256"), sig = field(json, "webSig");
        char[] pass = new String(Files.readAllBytes(Paths.get("keys/keystore.pass")), StandardCharsets.UTF_8).trim().toCharArray();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get("keys/release.p12"))) { ks.load(in, pass); }
        java.security.cert.Certificate cert = ks.getCertificate("release");
        java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
        v.initVerify(cert.getPublicKey());
        v.update(Updater.sigMessage(code, web, start).getBytes(StandardCharsets.UTF_8));
        boolean ok = v.verify(java.util.Base64.getMimeDecoder().decode(sig));
        // Een gewijzigd controlegetal moet afgekeurd worden
        java.security.Signature v2 = java.security.Signature.getInstance("SHA256withRSA");
        v2.initVerify(cert.getPublicKey());
        v2.update(Updater.sigMessage(code, "0" + web.substring(1), start).getBytes(StandardCharsets.UTF_8));
        boolean forged = v2.verify(java.util.Base64.getMimeDecoder().decode(sig));
        System.out.println((ok ? "✓" : "✗") + " handtekening klopt");
        System.out.println((!forged ? "✓" : "✗") + " vervalst controlegetal afgekeurd");
        System.out.println(ok && !forged ? "Alle handtekening-tests geslaagd" : "Handtekening-test mislukt");
        System.exit(ok && !forged ? 0 : 1);
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
