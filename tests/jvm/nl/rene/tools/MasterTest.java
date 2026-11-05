package nl.rene.tools;

import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONObject;
import org.json.JSONArray;

public class MasterTest {
    interface Checked {void run()throws Exception;}
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void rejects(Checked call)throws Exception{boolean denied=false;try{call.run();}catch(Exception expected){denied=true;}check(denied,"ongeldige actie werd toegestaan");}
    static final class Clock implements MasterSession.Clock {long at;public long now(){return at;}}
    public static void main(String[] args)throws Exception {
        KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);SecretKey key=g.generateKey();byte[] plain="Uitsluitend testgegevens 123456782".getBytes("UTF-8");
        check("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(MasterCipher.hash(new byte[0])),"SHA-256 formaat");
        byte[] a=MasterCipher.encrypt(key,"record/1/1",plain),b=MasterCipher.encrypt(key,"record/1/1",plain);
        check(!Arrays.equals(a,b),"unieke nonce");check(Arrays.equals(plain,MasterCipher.decrypt(key,"record/1/1",a)),"AES-GCM roundtrip");
        rejects(()->MasterCipher.decrypt(key,"record/1/2",a));rejects(()->MasterCipher.decrypt(g.generateKey(),"record/1/1",a));
        for(int at:new int[]{0,5,17,a.length-1}){byte[] damaged=a.clone();damaged[at]^=1;rejects(()->MasterCipher.decrypt(key,"record/1/1",damaged));}
        rejects(()->MasterCipher.decrypt(key,"record/1/1",Arrays.copyOf(a,30)));
        Clock clock=new Clock();MasterSession s=new MasterSession(clock),other=new MasterSession(clock);String first=s.open();check(!other.accepts(first),"sessie per eigenaar");
        clock.at=MasterSession.IDLE-1;s.touch(first);clock.at=MasterSession.MAX;check(!s.accepts(first),"harde maximale duur");
        clock.at=0;String second=s.open();s.closeIf(first);check(s.accepts(second),"late fout sluit nieuwe sessie niet");clock.at=MasterSession.IDLE;check(!s.isOpen(),"inactiviteit");
        clock.at=0;String reset=s.open();MasterSession.invalidateAll();check(!s.accepts(reset),"reset trekt alle sessies in");
        check(MasterSchema.validDate("2000-02-29")&&!MasterSchema.validDate("1900-02-29")&&!MasterSchema.validDate("2026-02-30"),"datumcontrole");
        JSONObject record=new JSONObject().put("type","persoon").put("label","Testpersoon").put("fields",new JSONObject().put("naam","Test").put("voornamen","Ada").put("bsn","123456782"));
        check("Ada Test".equals(MasterSchema.projection(record,"idcopy","ontvanger").getString("ontvanger")),"projectie ontvanger");
        rejects(()->MasterSchema.selected(record,"idcopy","ontvanger",new JSONArray().put("bsn")));
        rejects(()->MasterSchema.context("onbekend","kind"));rejects(()->MasterSchema.context("formulieren","onbekend"));
        check(MasterSchema.projection(record,"formulieren","kind").has("voornamen"),"kindprojectie");
        check(!MasterSchema.projection(record,"formulieren","begeleider1").has("voornamen"),"rolgebonden velden");
        JSONObject clean=MasterSchema.clean(record);check(clean.getJSONObject("fields").length()==3,"schone invoer");
        record.getJSONObject("fields").put("onbekend","x");rejects(()->MasterSchema.clean(record));record.getJSONObject("fields").remove("onbekend");
        record.getJSONObject("fields").put("bsn","abc");rejects(()->MasterSchema.clean(record));
        check(MasterSchema.sensitive("document")&&!MasterSchema.sensitive("naam"),"extra toestemming identiteitsgegevens");
        System.out.println("Stamgegevens: encryptie, intrekken, looptijden en veldcontracten gecontroleerd");
    }
}
