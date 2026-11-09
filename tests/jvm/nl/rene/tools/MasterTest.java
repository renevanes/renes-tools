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
    static JSONObject rec(String id,String type,String label,JSONObject fields)throws Exception{return new JSONObject().put("id",id).put("type",type).put("label",label).put("revision",1).put("fields",fields);}
    static String uid(int n){return String.format("00000000-0000-0000-0000-%012d",n);}
    /** Vaste formuliergegevens: verwijzingen, rolprojecties, toestemming voor identiteitsgegevens en relaties. */
    static void formProfile()throws Exception{
        java.util.Map<String,JSONObject> db=new java.util.HashMap<>();JSONArray all=new JSONArray();
        String kid=uid(1),mum=uid(2),dad=uid(3),kidDoc=uid(4),dadDoc=uid(5),org=uid(6),rel=uid(7),other=uid(8);
        for(JSONObject r:new JSONObject[]{
            rec(kid,"persoon","Zoë",new JSONObject().put("naam","Test").put("voornamen","Zoë Anna").put("geboortedatum","2015-02-03").put("bsn","123456782").put("adres","Straat 1")),
            rec(mum,"persoon","Moeder",new JSONObject().put("naam","Test").put("voornamen","Anna maria").put("adres","Straat 1").put("postcode","1234 AB").put("plaats","Rotterdam").put("bsn","111222333")),
            rec(dad,"persoon","Vader",new JSONObject().put("naam","Test").put("initialen","J.").put("adres","Laan 2")),
            rec(kidDoc,"identiteit","Paspoort Zoë",new JSONObject().put("persoon",kid).put("document","NX12345").put("geldig","2030-01-01")),
            rec(dadDoc,"identiteit","ID vader",new JSONObject().put("persoon",dad).put("document","IA999").put("geldig","2031-01-01")),
            rec(org,"organisatie","Jeugdzorg",new JSONObject().put("naam","Jeugdzorg BV").put("adres","Elders 3")),
            rec(rel,"relatie","Vader → Zoë",new JSONObject().put("vanPersoon",dad).put("naarPersoon",kid).put("relatie","dochter").put("delen","ja")),
            rec(other,"persoon","Ander",new JSONObject().put("naam","Ander"))}){db.put(r.getString("id"),r);all.put(r);}
        MasterFormProfile.Lookup lookup=id->{JSONObject r=db.get(id);if(r==null)throw new MasterError("weg");return r;};
        JSONObject fields=new JSONObject().put("formulier","reistoestemming").put("kind",kid).put("kindDocument",kidDoc).put("alleen","nee")
                .put("begeleider1",dad).put("begeleider1Document",dadDoc).put("gever1",mum).put("gever1Instelling",org).put("bsnInvullen","nee");
        MasterFormProfile.validate(fields,lookup,"");
        check(MasterSchema.clean(new JSONObject().put("type","formulier").put("label","Reistoestemming Zoë").put("fields",fields)).getJSONObject("fields").length()==fields.length(),"vaste gegevens opslaan");
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("kindDocument",dadDoc),lookup,""));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("kind",kidDoc),lookup,""));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("gever1",dad),lookup,""));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("gever1Instelling",mum),lookup,""));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("formulier","ander"),lookup,""));
        JSONObject noGever=new JSONObject(fields.toString());noGever.remove("gever1");noGever.remove("gever1Instelling");rejects(()->MasterFormProfile.validate(noGever,lookup,""));
        JSONObject noCompanion=new JSONObject(fields.toString());noCompanion.remove("begeleider1");noCompanion.remove("begeleider1Document");rejects(()->MasterFormProfile.validate(noCompanion,lookup,""));
        noCompanion.put("alleen","ja");MasterFormProfile.validate(noCompanion,lookup,"");
        rejects(()->MasterSchema.clean(new JSONObject().put("type","formulier").put("label","x").put("fields",new JSONObject(fields.toString()).put("kind","Zoë"))));
        rejects(()->MasterSchema.clean(new JSONObject().put("type","formulier").put("label","x").put("fields",new JSONObject(fields.toString()).put("begeleider1Relatie","baas"))));
        check(MasterSchema.permitted("formulieren","vast","formulier").contains("formulier")&&MasterSchema.permitted("formulieren","vast","persoon").isEmpty(),"vaste gegevens kiezen geeft geen persoonsvelden");
        check(MasterSchema.permitted("formulieren","kind","formulier").isEmpty(),"vaste gegevens niet als bron voor een rol");
        JSONObject profile=rec(uid(9),"formulier","Reistoestemming Zoë",fields);java.util.List<String> log=new java.util.ArrayList<>();
        MasterFormProfile.Use use=(r,role,f)->log.add(role+":"+r.getString("label")+":"+f);
        MasterFormProfile.Relation rel2=(r,role)->log.add("relatie:"+role+":"+r.getString("label"));
        JSONObject out=MasterFormProfile.resolve(profile,lookup,all,false,use,rel2),v=out.getJSONObject("values");
        check("Test".equals(v.getString("kind-naam"))&&"Zoë Anna".equals(v.getString("kind-voornamen"))&&"2015-02-03".equals(v.getString("kind-geboortedatum")),"kind uit stamgegevens");
        check(!v.has("kind-bsn")&&!v.has("kind-document")&&!v.has("begeleider1-document")&&out.getBoolean("omitted"),"zonder toestemming geen identiteitsgegevens");
        check(!v.has("kind-adres")&&!v.has("begeleider1-adres"),"alleen velden die bij de rol horen");
        check("A.M.".equals(v.getString("gever1-initialen"))&&"J.".equals(v.getString("begeleider1-initialen")),"initialen uit voornamen, opgeslagen initialen gaan voor");
        check("Straat 1".equals(v.getString("gever1-adres"))&&"Jeugdzorg BV".equals(v.getString("gever1-instelling"))&&!v.has("gever1-telefoon"),"toestemminggever met adres en instelling");
        check("ouder".equals(out.getJSONObject("relations").getString("begeleider1")),"relatie afgeleid uit gedeelde relatie");
        check("nee".equals(v.getString("tweedeBegeleider"))&&"nee".equals(v.getString("tweedeGever"))&&"nee".equals(v.getString("alleen")),"keuzes");
        check(log.contains("vast:Reistoestemming Zoë:[\"formulier\"]")&&log.stream().noneMatch(x->x.startsWith("kind:Paspoort"))&&log.contains("relatie:begeleider1:Vader → Zoë"),"logboek per gebruikt stamgegeven");
        out=MasterFormProfile.resolve(profile,lookup,all,true,use,rel2);v=out.getJSONObject("values");
        check("NX12345".equals(v.getString("kind-document"))&&"IA999".equals(v.getString("begeleider1-document"))&&!v.has("kind-bsn")&&!out.getBoolean("omitted"),"met toestemming documenten, BSN alleen als ingesteld");
        fields.put("bsnInvullen","ja").put("begeleider1Relatie","voogd");
        out=MasterFormProfile.resolve(profile,lookup,all,true,use,rel2);
        check("123456782".equals(out.getJSONObject("values").getString("kind-bsn"))&&"voogd".equals(out.getJSONObject("relations").getString("begeleider1")),"BSN en vaste relatie");
        fields.put("alleen","ja");out=MasterFormProfile.resolve(profile,lookup,all,true,use,rel2);
        check(!out.getJSONObject("values").has("begeleider1-naam")&&"ja".equals(out.getJSONObject("values").getString("alleen")),"alleen reizen: geen begeleiders");
        fields.remove("gever1");rejects(()->MasterFormProfile.resolve(profile,lookup,all,true,use,rel2));
        db.get(kid).getJSONObject("fields").put("naam","Nieuw");fields.put("gever1",mum);
        check("Nieuw".equals(MasterFormProfile.resolve(profile,lookup,all,false,use,rel2).getJSONObject("values").getString("kind-naam")),"altijd de actuele stamgegevens");
        // Verloopcontrole: vóór de laatste reisdag, binnen zes maanden daarna, of ruim geldig.
        check("2027-08-28".equals(MasterFormProfile.plusMonths("2027-02-28",6))&&"2028-02-29".equals(MasterFormProfile.plusMonths("2027-08-31",6))&&"2028-01-15".equals(MasterFormProfile.plusMonths("2027-07-15",6)),"maanden optellen");
        java.util.List<String> read=new java.util.ArrayList<>();MasterFormProfile.Relation checked=(r,role)->read.add(role+":"+r.getString("label"));
        fields.put("alleen","nee");
        JSONArray w=MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",true,checked);
        check(w.length()==2&&"voor".equals(w.getJSONObject(0).getString("niveau"))&&"Paspoort Zoë".equals(w.getJSONObject(0).getString("label"))&&!w.getJSONObject(0).has("document")&&"kort".equals(w.getJSONObject(1).getString("niveau")),"verlopen vóór de reis en kort erna, zonder nummer");
        check(read.contains("kind:Paspoort Zoë")&&read.contains("begeleider1:ID vader"),"geldigheid gecontroleerd in het logboek");
        w=MasterFormProfile.expiry(profile,lookup,"2029-07-01","2029-08-01",true,checked);
        check(w.length()==1&&"kort".equals(w.getJSONObject(0).getString("niveau")),"binnen zes maanden na de reis");
        check(MasterFormProfile.expiry(profile,lookup,"2029-06-01","2029-06-30",true,checked).length()==0,"ruim geldig: geen melding");
        check(!MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",false,checked).getJSONObject(0).has("geldig"),"zonder toestemming geen datum");
        // Toestemminggever reist niet mee: alleen als het document vóór de eerste reisdag al verlopen is.
        fields.put("gever1Document",kidDoc);check(MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",true,checked).length()==2,"document van een ander telt niet");fields.remove("gever1Document");
        JSONObject mumDoc=rec(uid(10),"identiteit","ID moeder",new JSONObject().put("persoon",mum).put("geldig","2029-12-01"));db.put(uid(10),mumDoc);fields.put("gever1Document",uid(10));
        JSONArray gw=MasterFormProfile.expiry(profile,lookup,"2029-09-01","2029-10-01",true,checked);check(gw.length()==1&&"kind".equals(gw.getJSONObject(0).getString("role")),"toestemminggever geldig bij ondertekenen: geen melding");
        gw=MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",true,checked);check(gw.toString().contains("\"verlopen\"")&&gw.toString().contains("gever1"),"toestemminggever met verlopen document");
        fields.put("gever1Document",uid(11));check(MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",true,checked).length()==2,"verdwenen document wordt overgeslagen");fields.remove("gever1Document");
        fields.put("alleen","ja");check(MasterFormProfile.expiry(profile,lookup,"2030-07-01","2030-08-01",true,checked).length()==1,"alleen reizen: begeleider niet gecontroleerd");fields.put("alleen","nee");
        // Plaats van ondertekening en de eigen handtekening: alleen bij de begeleiders, nooit bij een toestemminggever.
        check(!v.has("begeleider1-tekenplaats")&&!v.has("begeleider1-handtekening"),"zonder instelling geen plaats of handtekening");
        fields.put("tekenplaats","Rotterdam").put("handtekening","begeleider1");MasterFormProfile.validate(fields,lookup,"");
        check(MasterSchema.clean(new JSONObject().put("type","formulier").put("label","x").put("fields",fields)).getJSONObject("fields").getString("handtekening").equals("begeleider1"),"handtekening opslaan");
        log.clear();v=MasterFormProfile.resolve(profile,lookup,all,false,use,rel2).getJSONObject("values");
        check("Rotterdam".equals(v.getString("begeleider1-tekenplaats"))&&"ja".equals(v.getString("begeleider1-handtekening")),"plaats en handtekening bij begeleider 1");
        check(!v.has("gever1-tekenplaats")&&!v.has("gever1-handtekening")&&!v.has("begeleider2-handtekening"),"niet bij de toestemminggever");
        check(log.contains("vast:Reistoestemming Zoë:[\"formulier\",\"tekenplaats\",\"handtekening\"]"),"plaats en handtekening in het logboek");
        rejects(()->MasterSchema.clean(new JSONObject().put("type","formulier").put("label","x").put("fields",new JSONObject(fields.toString()).put("handtekening","gever1"))));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("handtekening","begeleider2"),lookup,""));
        rejects(()->MasterFormProfile.validate(new JSONObject(fields.toString()).put("alleen","ja"),lookup,""));
        MasterFormProfile.validate(new JSONObject(fields.toString()).put("alleen","ja").put("handtekening","nee"),lookup,"");
        // Met een app-profiel ("Dit ben ik") alleen bij de begeleider die jij bent, ook als het profiel later verandert.
        MasterFormProfile.validate(fields,lookup,dad);rejects(()->MasterFormProfile.validate(fields,lookup,other));
        JSONObject me=rec(uid(12),"profiel","Mijn app-profiel",new JSONObject().put("persoon",other));
        check(other.equals(MasterFormProfile.self(new JSONArray().put(db.get(kid)).put(me)))&&MasterFormProfile.self(new JSONArray().put(db.get(kid))).isEmpty(),"wie ik ben uit het app-profiel");
        JSONArray withMe=new JSONArray(all.toString()).put(me);rejects(()->MasterFormProfile.resolve(profile,lookup,withMe,false,use,rel2));
        me.getJSONObject("fields").put("persoon",dad);check(MasterFormProfile.resolve(profile,lookup,new JSONArray(all.toString()).put(me),false,use,rel2).getJSONObject("values").has("begeleider1-handtekening"),"handtekening bij jezelf");
        fields.put("alleen","ja");v=MasterFormProfile.resolve(profile,lookup,all,false,use,rel2).getJSONObject("values");
        check(!v.has("begeleider1-tekenplaats")&&!v.has("begeleider1-handtekening"),"alleen reizen: geen plaats of handtekening");
        fields.put("alleen","nee");fields.remove("tekenplaats");fields.remove("handtekening");
        // Geslacht zoals op het identiteitsbewijs
        JSONObject p2=new JSONObject().put("type","persoon").put("label","P").put("fields",new JSONObject().put("naam","P").put("geslacht","X"));
        check("X".equals(MasterSchema.clean(p2).getJSONObject("fields").getString("geslacht")),"geslacht X");
        p2.getJSONObject("fields").put("geslacht","man");rejects(()->MasterSchema.clean(p2));
        check(!MasterSchema.permitted("formulieren","kind","persoon").contains("geslacht")&&!MasterSchema.permitted("idcopy","ontvanger","persoon").contains("geslacht"),"geslacht gaat niet mee naar formulieren die er niet om vragen");
    }
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
        formProfile();
        System.out.println("Stamgegevens: encryptie, intrekken, looptijden en veldcontracten gecontroleerd");
    }
}
