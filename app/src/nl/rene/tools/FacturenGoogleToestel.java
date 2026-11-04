package nl.rene.tools;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Google koppelen met het Google-account dat al op de telefoon staat (Android AccountManager): Rene kiest zijn account,
 * Android vraagt één keer toestemming, en daarna geeft Android zelf de toegangssleutel (geen browser, geen client
 * secret, geen refresh-token in de app). Werkt alleen als er in zijn eigen Google-project een Android-client is met de
 * pakketnaam en de SHA-1 van deze app (die toont het scherm Google). Daarna gebruiken Gmail en Drive deze sleutel via
 * FacturenDrive.token().
 */
final class FacturenGoogleToestel {
    private FacturenGoogleToestel() { }

    static final String TYPE = "com.google";

    static String account(Context c) { return Facturen.prefs(c).getString("gdAccount", ""); }
    static boolean actief(Context c) { return !account(c).isEmpty(); }

    /** Het soort sleutel voor AccountManager: "oauth2:" + de scopes (gescheiden door spaties). */
    static String soort(String scopes) { return "oauth2:" + scopes.trim(); }

    /** Een toegangssleutel van Android (niet op de hoofdthread). Geen toestemming meer: een duidelijke fout. */
    static String token(Context c) throws Exception {
        String acc = account(c), scopes = Facturen.prefs(c).getString("gdScopes", "");
        if (acc.isEmpty() || scopes.isEmpty()) throw new Exception("Koppel Google (opnieuw) in Facturen → Koppelingen");
        String t;
        try { t = AccountManager.get(c).blockingGetAuthToken(new Account(acc, TYPE), soort(scopes), true); }
        catch (android.accounts.AuthenticatorException e) { throw new Exception("Google weigert de app: " + uitleg(e)); }
        if (t == null || t.isEmpty()) throw new Exception("Google vraagt (opnieuw) toestemming: open Facturen → Koppelingen → Google en kies je account nog een keer");
        return t;
    }

    /** Een verlopen sleutel bij Android weggooien (dan geeft de volgende keer een nieuwe). */
    static void vergeet(Context c, String token) {
        try { if (token != null) AccountManager.get(c).invalidateAuthToken(TYPE, token); } catch (Throwable ignored) { }
    }

    static void ontkoppel(Context c) {
        Facturen.prefs(c).edit().remove("gdAccount").apply();
    }

    static String uitleg(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("UNREGISTERED_ON_API_CONSOLE") || m.toLowerCase(Locale.ROOT).contains("unregistered"))
            return "er is nog geen Android-client met pakketnaam " + BuildConfigPakket.NAAM + " en de SHA-1 van deze app in je Google-project";
        return m.isEmpty() ? "onbekende fout" : m;
    }

    /** Het scherm om een Google-account te kiezen (alleen Google-accounts). */
    static Intent kiesIntent() {
        return AccountManager.newChooseAccountIntent(null, null, new String[]{TYPE}, null, null, null, null);
    }

    /** Toestemming vragen (Android toont zelf het venster) en de koppeling bewaren. Antwoord via done(fout, naam). */
    static void koppel(Activity a, String naam, String rechten, Spotify.Done done) {
        final Context app = a.getApplicationContext();
        final String scopes = FacturenDrive.scopes(rechten);
        try {
            AccountManager.get(a).getAuthToken(new Account(naam, TYPE), soort(scopes), null, a, f -> {
                String fout = null;
                try {
                    Bundle b = f.getResult();
                    String t = b.getString(AccountManager.KEY_AUTHTOKEN);
                    if (t == null || t.isEmpty()) fout = "Geen toestemming gekregen";
                    else {
                        FacturenDrive.vergeetSleutel();
                        Facturen.prefs(app).edit().putString("gdAccount", naam).putString("gdGebruiker", naam).putString("gdScopes", scopes).remove("gdMappen").apply();
                        Facturen.geheim(app).edit().remove("gd_refresh").apply(); // de browser-koppeling (als die er was) is niet meer nodig
                    }
                } catch (android.accounts.OperationCanceledException e) { fout = "Geannuleerd";
                } catch (Throwable e) { fout = "Google weigert de app: " + uitleg(e); }
                try { done.done(fout, fout == null ? naam : null); } catch (Throwable ignored) { }
            }, null);
        } catch (Throwable e) { try { done.done("Koppelen lukt niet: " + uitleg(e), null); } catch (Throwable ignored) { } }
    }

    /** SHA-1 van het certificaat waarmee deze app is ondertekend (AA:BB:…), voor de Android-client in Google. */
    @SuppressWarnings("deprecation")
    static String sha1(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sig;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sig = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            } else sig = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            if (sig == null || sig.length == 0) return "";
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sig[0].toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(b.length() > 0 ? ":" : "").append(String.format(Locale.ROOT, "%02X", x & 0xff));
            return b.toString();
        } catch (Throwable e) { return ""; }
    }

    /** De pakketnaam (los gehouden voor de foutuitleg zonder Context). */
    static final class BuildConfigPakket { static final String NAAM = "nl.rene.tools"; }
}
