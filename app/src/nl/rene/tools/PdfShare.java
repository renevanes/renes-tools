package nl.rene.tools;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Verwerkt wat er naar "PDF maken" gedeeld is. Uitkomst (JSON) wacht in 'pending' tot het PDF-scherm hem ophaalt. */
final class PdfShare {
    private PdfShare() { }

    static volatile String pending;

    /** {kind: images|pdf|done, count, made:[...], name, pages} of {error}. */
    static String take(Context c, List<Uri> uris, String type, String subject, String text, String html) throws Exception {
        JSONObject r = new JSONObject();
        if (!uris.isEmpty()) {
            // Eén PDF: naar splitsen. Eén e-mail of tekstbestand: meteen een PDF. Plaatjes: naar de lijst.
            if (uris.size() == 1) {
                Uri u = uris.get(0);
                String name = PdfTools.displayName(c, u), mime = c.getContentResolver().getType(u);
                String lower = name.toLowerCase(Locale.ROOT);
                if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) {
                    String o = PdfTools.openPdf(c, u);
                    return new JSONObject(o).put("kind", "pdf").toString();
                }
                if ("message/rfc822".equals(mime) || lower.endsWith(".eml") || (mime != null && mime.startsWith("text/")) || lower.endsWith(".txt") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    File tmp = new File(PdfTools.dir(c, "pdf-in"), "gedeeld-" + System.nanoTime() + ".tmp");
                    byte[] raw;
                    try { PdfTools.copy(c, u, tmp, 20L * 1024 * 1024); raw = PdfTools.readAll(tmp, 20L * 1024 * 1024); }
                    finally { tmp.delete(); }
                    File out;
                    if ("message/rfc822".equals(mime) || lower.endsWith(".eml") || MailParse.looksLikeMail(raw)) out = PdfTools.mailToPdf(c, raw);
                    else {
                        String s = new String(raw, MailParse.charset(""));
                        boolean isHtml = "text/html".equals(mime) || lower.endsWith(".html") || lower.endsWith(".htm") || s.trim().toLowerCase(Locale.ROOT).startsWith("<");
                        out = PdfTools.textToPdf(c, PdfTools.base(name), "", isHtml ? PdfTools.fromHtml(s) : s);
                    }
                    return r.put("kind", "done").put("made", new JSONArray().put(out.getName())).toString();
                }
            }
            int n = 0;
            StringBuilder skipped = new StringBuilder();
            for (Uri u : uris) {
                try { PdfTools.addImage(c, u); n++; } catch (Exception e) { if (skipped.length() < 200) skipped.append(e.getMessage()).append(". "); }
            }
            if (n == 0) throw new Exception(skipped.length() > 0 ? skipped.toString().trim() : "Geen plaatjes gevonden");
            r.put("kind", "images").put("count", n);
            if (skipped.length() > 0) r.put("warn", skipped.toString().trim());
            return r.toString();
        }
        // Tekst (bijv. een e-mail die als tekst gedeeld wordt, of een webpagina)
        String title = subject == null || subject.trim().isEmpty() ? null : subject.trim();
        CharSequence body = html != null && !html.trim().isEmpty() ? PdfTools.fromHtml(html) : text;
        if (title == null && text != null) {
            String first = text.trim().split("\n", 2)[0].trim();
            title = first.length() > 60 ? first.substring(0, 60) : first;
        }
        File out = PdfTools.textToPdf(c, title == null ? "Tekst" : title, "", body);
        return r.put("kind", "done").put("made", new JSONArray().put(out.getName())).toString();
    }
}
