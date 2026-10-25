package nl.rene.tools;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Modellen beheren: alle modellen op één plek (taalmodellen en het zoekmodel van de assistent, de spraakmodellen),
 * met wat ze innemen, of ze in gebruik zijn, wanneer voor het laatst, wat er gedownload wordt en wat op te ruimen is.
 */
final class Models {

    private Models() { }

    static JSONObject json(Context c) throws Exception {
        JSONObject o = new JSONObject();
        long free;
        try { free = new StatFs(c.getFilesDir().getPath()).getAvailableBytes(); } catch (Throwable t) { free = -1; }
        o.put("free", free).put("online", ModelDownloadService.online(c)).put("metered", ModelDownloadService.metered(c))
                .put("wifiOnly", LocalAi.wifiOnly(c)).put("supported", LocalAi.supported()).put("txBusy", TranscribeService.busy);
        JSONArray ms = new JSONArray();
        long used = 0;
        String chat = LocalAi.chatModel(c).id;
        for (LocalAi.Model m : LocalAi.MODELS) {
            JSONObject s = LocalAi.state(c, m);
            boolean ready = "ready".equals(s.optString("state"));
            s.put("key", "ai:" + m.id).put("kind", m.embed ? "zoek" : "ai").put("last", ModelUse.last(c, "ai:" + m.id));
            s.put("inUse", ready && (m.embed || chat.equals(m.id)));
            // Welke versie (bijv. "Qwen3.5 2B") en of er een nieuwere is die deze vervangt
            s.put("version", m.desc.contains(" · ") ? m.desc.substring(0, m.desc.indexOf(" · ")) : m.desc);
            for (LocalAi.Model x : LocalAi.MODELS) if (m.id.equals(x.replaces)) s.put("newer", x.id);
            if (ready) used += m.size;
            ms.put(s);
        }
        Transcribe.Model act = Transcribe.activeModel(c);
        JSONObject st = new JSONObject(Transcribe.prefs(c).getString("status", "{}"));
        String dl = Transcribe.prefs(c).getString("dlModel", "");
        for (Transcribe.Model m : Transcribe.MODELS) {
            File f = Transcribe.modelFile(c, m);
            JSONObject s = new JSONObject().put("id", m.id).put("key", "spraak:" + m.id).put("kind", "spraak")
                    .put("label", "Spraakmodel " + m.label.replaceAll("\\s*\\(.*\\)$", "").toLowerCase(java.util.Locale.ROOT))
                    .put("desc", "Spraak naar tekst · whisper " + m.id).put("version", "Whisper " + m.id).put("size", m.size).put("last", ModelUse.last(c, "spraak:" + m.id));
            if (f.isFile()) { s.put("state", "ready").put("inUse", act != null && act.id.equals(m.id)); used += f.length(); }
            else if (TranscribeService.busy && st.optBoolean("running") && "download".equals(st.optString("phase")) && m.id.equals(dl))
                s.put("state", "running").put("bytes", m.size * st.optInt("pct") / 100).put("total", m.size);
            else s.put("state", "none");
            ms.put(s);
        }
        long left = 0;
        try { left = LocalAi.orphanBytes(c) + whisperParts(c, false); } catch (Throwable ignored) { }
        o.put("models", ms).put("used", used).put("leftovers", left).put("ram", LocalAi.ramBytes(c)).put("tts", TtsCheck.json());
        return o;
    }

    /** Halve spraakmodellen (.part) van een download die niet afkwam; weghalen als delete (en er niets loopt). */
    static long whisperParts(Context c, boolean delete) {
        if (TranscribeService.busy) return 0;
        long n = 0;
        File[] fs = Transcribe.modelDir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".part")) { long l = f.length(); if (!delete || f.delete()) n += l; }
        return n;
    }

    /** Alles opruimen wat geen bruikbaar model is; geeft de vrijgekomen bytes. */
    static long clean(Context c) {
        long n = 0;
        try { n += LocalAi.cleanOrphans(c); } catch (Throwable ignored) { }
        try { n += whisperParts(c, true); } catch (Throwable ignored) { }
        return n;
    }
}
