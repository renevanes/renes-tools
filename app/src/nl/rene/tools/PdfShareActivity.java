package nl.rene.tools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * "Delen → PDF maken": plaatjes, een PDF, tekst, een webpagina-tekst of een e-mail (.eml) uit een andere app.
 * Neemt de gedeelde inhoud meteen over (de toegang van de andere app geldt alleen voor deze activiteit)
 * en opent daarna het PDF-scherm in Rene's Tools. Er wordt niets automatisch opgeslagen of verstuurd.
 */
public class PdfShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        final ArrayList<Uri> uris = new ArrayList<>();
        String text = null, subject = null, html = null, type = i == null ? null : i.getType();
        try {
            if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
                Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) text = t.toString();
                html = i.getStringExtra(Intent.EXTRA_HTML_TEXT);
                subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
            } else if (i != null && Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
                ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) for (Uri u : l) if (u != null && uris.size() < PdfTools.MAX_IMAGES) uris.add(u);
            }
        } catch (Exception ignored) { }
        // Alleen bestanden die de andere app zelf mocht doorgeven (Android geeft dan leesrecht mee). Zonder dat
        // leesrecht zou Rene's Tools met zijn eigen rechten iets lezen wat de andere app niet mag zien.
        if (!uris.isEmpty() && !PdfTools.grantedBySender(i, uris)) {
            Toast.makeText(this, "Dit bestand kan niet gebruikt worden", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (uris.isEmpty() && (text == null || text.trim().isEmpty()) && (html == null || html.trim().isEmpty())) {
            Toast.makeText(this, "Niets om een PDF van te maken", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final String fText = text, fSubject = subject, fHtml = html, fType = type;
        // Kopiëren mag niet op de hoofdthread (kan groot zijn); daarna het PDF-scherm openen
        Toast.makeText(this, "Bezig…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String r;
            try { r = PdfShare.take(getApplicationContext(), uris, fType, fSubject, fText, fHtml); }
            catch (Throwable e) { r = PdfTools.err(e); }
            final String res = r;
            runOnUiThread(() -> {
                PdfShare.pending = res;
                startActivity(new Intent(this, MainActivity.class).putExtra("open", "pdf-share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            });
        }, "pdf-share").start();
    }
}
