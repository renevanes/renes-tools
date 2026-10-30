package nl.rene.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Begrensde download naar een tijdelijk bestand; nooit de hele APK in het geheugen. */
final class UpdateDownload {
    private UpdateDownload() { }
    interface Progress { void step(long done, long total); }

    static String copy(InputStream source, File target, long total, long max, Progress progress) throws Exception {
        boolean complete = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = source; FileOutputStream out = new FileOutputStream(target)) {
                if (total > max) throw new IOException("Download te groot");
                byte[] buffer = new byte[1 << 16]; long done = 0; int n;
                progress.step(0, total);
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    done += n;
                    if (done > max) throw new IOException("Download te groot");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n); progress.step(done, total);
                }
                if (total > 0 && done != total) throw new IOException("Download onvolledig");
                out.getFD().sync();
                progress.step(done, Math.max(done, total));
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b));
            complete = true;
            return hash.toString();
        } finally { if (!complete) target.delete(); }
    }
}
