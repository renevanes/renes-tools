package nl.rene.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sommige mappen-apps voeren de bewerking uit, maar sturen geen antwoord terug naar Android. */
final class DocumentWrite {
    private DocumentWrite() { }
    static final String NO_REPLY = "De app voor je backup-map geeft geen geldig antwoord. Kies de map opnieuw bij Instellingen → Backup-map, of kies een map op je telefoon.";
    static final class Entry {
        final String id, name, mime;
        Entry(String id, String name, String mime) { this.id=id; this.name=name; this.mime=mime; }
    }
    interface Store {
        List<Entry> list() throws Exception;
        String create(String name, String mime) throws Exception;
        String rename(String id, String name) throws Exception;
    }
    static final class UncertainReply extends IOException {
        UncertainReply() { super(NO_REPLY); }
    }
    static String create(Store store, String name, String mime) throws Exception {
        return new Session(store).create(name,mime);
    }
    static String rename(Store store, String id, String name) throws Exception {
        return new Session(store).rename(id,name);
    }
    interface Input { InputStream open() throws Exception; }
    interface Output { OutputStream open() throws Exception; }

    /** Pas terugkeren als ook flushen én sluiten gelukt zijn; providers kunnen dan pas hun upload afronden. */
    static void copy(Input source, Output target) throws Exception {
        try (InputStream in = source.open(); OutputStream out = target.open()) {
            if (in == null || out == null) throw new IOException("Kopiëren lukt niet");
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = in.read(buffer)) != -1) if (count > 0) out.write(buffer, 0, count);
            out.flush();
        }
    }
    /** Een geldige URI is de normale providerroute. Alleen onbetrouwbare antwoorden vragen mapcontroles. */
    static final class Session {
      final Store store;boolean createReplies,renameReplies;
      Session(Store store) { this.store=store; }
      String create(String name,String mime) throws Exception {
        Set<String> before=createReplies?null:ids(store.list());
        String result;
        try { result=store.create(name,mime); } catch (NullPointerException emptyReply) { result=null; }
        if(result!=null) { createReplies=true;return result; }
        // Na eerder geldige antwoorden is er geen actuele vooropname: niets als nieuw aannemen.
        if(before==null) { createReplies=false;throw new UncertainReply(); }
        String found=null;
        for(Entry e:store.list())if(!before.contains(e.id)&&name.equals(e.name)&&mime.equals(e.mime)) {
            if(found!=null)throw new IOException(NO_REPLY);
            found=e.id;
        }
        if(found==null)throw new IOException(NO_REPLY);
        return found;
      }
      String rename(String id,String name) throws Exception {
        List<Entry> previous=renameReplies?null:store.list();
        Set<String> before=previous==null?null:ids(previous);
        if(previous==null)previous=new java.util.ArrayList<>();
        String mime=null;for(Entry e:previous)if(id.equals(e.id))mime=e.mime;
        String result;
        try { result=store.rename(id,name); } catch (NullPointerException emptyReply) { result=null; }
        if(result!=null) { renameReplies=true;return result; }
        if(before==null) { renameReplies=false;throw new UncertainReply(); }
        List<Entry> after=store.list();
        // Zelfde document-id: bevestig de nieuwe naam. Bij een nieuw id moet het oude verdwenen zijn.
        for(Entry e:after)if(id.equals(e.id))return name.equals(e.name)?id:null;
        String found=null;
        for(Entry e:after)if(mime!=null&&!before.contains(e.id)&&name.equals(e.name)&&mime.equals(e.mime)) {
            if(found!=null)return null;
            found=e.id;
        }
        return found;
      }
    }
    private static Set<String> ids(List<Entry> entries) {
        Set<String> out=new HashSet<>();for(Entry e:entries)out.add(e.id);return out;
    }
}
