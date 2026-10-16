package nl.rene.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DocumentWriteTest {
    static final class Store implements DocumentWrite.Store {
        final List<DocumentWrite.Entry> entries=new ArrayList<>();
        boolean empty, crash, denied, noChange, newId, duplicate;int lists,sequence;
        public List<DocumentWrite.Entry> list() { lists++;return new ArrayList<>(entries); }
        public String create(String name,String mime) {
            if(denied)throw new SecurityException("Geen toestemming");
            String id=++sequence==1?"new":"new"+sequence;
            if(!noChange) { entries.add(new DocumentWrite.Entry(id,name,mime));if(duplicate)entries.add(new DocumentWrite.Entry("other",name,mime)); }
            if(crash)throw new NullPointerException("Bundle.getParcelable");
            return empty?null:id;
        }
        public String rename(String id,String name) {
            if(denied)throw new SecurityException("Geen toestemming");
            if(!noChange)for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id))entries.set(i,new DocumentWrite.Entry(newId?"renamed":id,name,entries.get(i).mime));
            if(crash)throw new NullPointerException("Bundle.getParcelable");
            return empty?null:newId?"renamed":id;
        }
    }
    static void check(boolean ok) { if(!ok)throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Store s=new Store();check("new".equals(DocumentWrite.create(s,"sms.xml","text/xml")));
        s=new Store();s.empty=true;check("new".equals(DocumentWrite.create(s,"sms.xml","text/xml")));
        s=new Store();s.crash=true;check("new".equals(DocumentWrite.create(s,"sms.xml","text/xml")));
        s=new Store();s.empty=true;s.noChange=true;s.entries.add(new DocumentWrite.Entry("old","sms.xml","text/xml"));
        try { DocumentWrite.create(s,"sms.xml","text/xml");throw new AssertionError("Oude backup ten onrechte als nieuwe aangemerkt"); } catch(IOException expected) { check(expected.getMessage().contains("backup-map")); }
        check(s.entries.size()==1&&s.entries.get(0).id.equals("old"));
        s=new Store();s.empty=true;s.duplicate=true;
        try { DocumentWrite.create(s,"sms.xml","text/xml");throw new AssertionError("Dubbelzinnig antwoord aanvaard"); } catch(IOException expected) { }
        s=new Store();s.denied=true;
        try { DocumentWrite.create(s,"sms.xml","text/xml");throw new AssertionError("Toegangsfout verborgen"); } catch(SecurityException expected) { }
        for(boolean changedId:new boolean[]{false,true}) {
            s=new Store();s.crash=true;s.newId=changedId;s.entries.add(new DocumentWrite.Entry("temp","sms.rt-tmp","text/xml"));
            check((changedId?"renamed":"temp").equals(DocumentWrite.rename(s,"temp","sms.xml")));
        }
        s=new Store();s.empty=true;s.noChange=true;s.entries.add(new DocumentWrite.Entry("temp","sms.rt-tmp","text/xml"));s.entries.add(new DocumentWrite.Entry("old","sms.xml","text/xml"));
        check(DocumentWrite.rename(s,"temp","sms.xml")==null);check(s.entries.size()==2);
        s.denied=true;try { DocumentWrite.rename(s,"temp","sms.xml");throw new AssertionError(); } catch(SecurityException expected) { }
        // Een normale export leest de groeiende map eenmaal, in plaats van voor ieder bestand.
        s=new Store();DocumentWrite.Session session=new DocumentWrite.Session(s);
        for(int i=0;i<100;i++)session.create("gesprek-"+i,"text/html");check(s.lists==1);
        session.rename("new","index.html");session.rename("new","overzicht.html");check(s.lists==2);
        s.crash=true;
        try { session.rename("new","laatste.html");throw new AssertionError("Onverwacht leeg antwoord als succes aanvaard"); } catch(DocumentWrite.UncertainReply expected) { }
        check(s.entries.stream().anyMatch(e->e.id.equals("new")&&e.name.equals("laatste.html")));
        check(session.rename("new","hersteld.html").equals("new"));
        try { session.create("nieuw.html","text/html");throw new AssertionError(); } catch(DocumentWrite.UncertainReply expected) { }
        check(session.create("opnieuw.html","text/html")!=null);
        System.out.println("Documentantwoorden: normaal, leeg antwoord, Android-fout, gewijzigde id, oude backup en toegang gecontroleerd");
    }
}
