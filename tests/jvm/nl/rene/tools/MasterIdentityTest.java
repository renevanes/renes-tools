package nl.rene.tools;
import org.json.JSONObject;
import org.json.JSONArray;

public final class MasterIdentityTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JSONObject r(String id,String type,JSONObject f)throws Exception{return new JSONObject().put("id",id).put("type",type).put("label",id).put("fields",f);}
    public static void main(String[] args)throws Exception {
        String me="11111111-1111-1111-1111-111111111111",kid="22222222-2222-2222-2222-222222222222";
        JSONObject self=r(me,"persoon",new JSONObject().put("naam","Ouder").put("voornamen","Ada").put("roepnaam","Aad").put("bsn","123456782").put("geboortedatum","1980-01-01").put("context","Vegetarisch"));
        JSONObject child=r(kid,"persoon",new JSONObject().put("naam","Kindnaam").put("context","Houdt van treinen"));
        JSONObject rel=r("rel","relatie",new JSONObject().put("vanPersoon",me).put("naarPersoon",kid).put("relatie","dochter").put("toelichting","Woont bij mij").put("delen","ja"));
        JSONObject profile=r("profile","profiel",new JSONObject().put("persoon",me).put("delen","nee").put("context","Reis graag per trein"));
        JSONObject doc=r("doc","identiteit",new JSONObject().put("persoon",me).put("document","SECRET_DOCUMENT")).put("images",new JSONObject().put("front","PRIVATE_IMAGE"));
        JSONArray records=new JSONArray().put(self).put(child).put(rel).put(profile).put(doc);
        check(!MasterIdentity.app(records).has("self"),"zonder toestemming geen gedeelde context");profile.getJSONObject("fields").put("delen","ja");
        JSONObject pf=profile.getJSONObject("fields");
        for(String k:new String[]{"aiStatus","aiGeboorte","aiDocumenten","aiWoonplaats","aiContact"})pf.put(k,"nee");
        JSONObject app=MasterIdentity.app(records);String json=app.toString();
        check(app.getJSONObject("self").getString("callname").equals("Aad"),"roepnaam");check(app.getJSONArray("people").getJSONObject(0).getJSONArray("relations").getString(0).equals("dochter"),"gerichte relatie");
        for(String secret:new String[]{"123456782","1980-01-01","SECRET_DOCUMENT","PRIVATE_IMAGE","documents","images","geboortedatum","bsn"})check(!json.contains(secret),"vinkjes uit: geen identiteit- of geboortedata in app-context: "+secret);
        // Standaard (vinkjes leeg): status, geboortedatum, documenten (soort en geldig) en contact wel, woonplaats niet; nooit nummer, BSN of foto
        for(String k:new String[]{"aiStatus","aiGeboorte","aiDocumenten","aiWoonplaats","aiContact"})pf.remove(k);
        self.getJSONObject("fields").put("plaats","Rotterdam").put("contact","0r1-ABC");doc.getJSONObject("fields").put("documentsoort","paspoort").put("geldig","2031-05-01");
        json=MasterIdentity.app(records).toString();
        check(json.contains("1980-01-01")&&json.contains("paspoort")&&json.contains("2031-05-01")&&json.contains("0r1-ABC")&&!json.contains("Rotterdam"),"standaard: geboortedatum, document en contact, geen woonplaats");
        for(String secret:new String[]{"123456782","SECRET_DOCUMENT","PRIVATE_IMAGE","images","bsn"})check(!json.contains(secret),"nooit in app-context: "+secret);
        pf.put("aiWoonplaats","ja");check(MasterIdentity.app(records).toString().contains("Rotterdam"),"woonplaats met vinkje");pf.remove("aiWoonplaats");
        String withAge=MasterIdentity.prompt(MasterIdentity.app(records),1800,"2026-11-09");
        check(withAge.contains("\"leeftijd\":46")&&withAge.contains("geldig_tot")&&!withAge.contains("0r1-ABC"),"leeftijd op de dag zelf, documenten, geen contactsleutel in de prompt");
        check(MasterIdentity.age("1980-11-10","2026-11-09")==45&&MasterIdentity.age("1980-11-09","2026-11-09")==46&&MasterIdentity.age("2000-02-29","2025-02-28")==24&&MasterIdentity.age("x","2026-01-01")==-1,"leeftijd");
        check(MasterIdentity.between(rel,kid,me).equals("ouder"),"omgekeerde relatie zonder geslacht aan te nemen");
        check(MasterIdentity.inverse("noodcontact").isEmpty()&&MasterIdentity.inverse("anders").isEmpty(),"geen omgekeerde betekenis raden");
        rel.getJSONObject("fields").put("delen","nee");check(MasterIdentity.app(records).getJSONArray("people").length()==0,"oude indeling: ongedeelde relatie blijft binnen de kluis");
        records.put(r("rel2","relatie",new JSONObject().put("vanPersoon",me).put("naarPersoon",kid).put("relatie","noodcontact").put("delen","ja")));
        JSONObject via=MasterIdentity.app(records).getJSONArray("people").getJSONObject(0);
        check(via.getJSONArray("relations").length()==1&&via.getJSONArray("relations").getString(0).equals("noodcontact")&&!via.toString().contains("Woont bij mij"),"oude indeling: relatie met nee blijft verborgen, ook als de persoon via een andere relatie bekend is");
        records.remove(records.length()-1);
        // Nieuwe indeling: Bekend bij de assistent staat bij de persoon en gaat voor
        child.getJSONObject("fields").put("delen","ja");check(MasterIdentity.app(records).getJSONArray("people").length()==1&&MasterIdentity.known(records).contains(kid),"persoon bekend bij de assistent");
        check(MasterIdentity.app(records).getJSONArray("people").getJSONObject(0).getJSONArray("relations").getString(0).equals("dochter"),"zelf aangezet: dan ook de relatie die vroeger nee was");
        rel.getJSONObject("fields").put("delen","ja");child.getJSONObject("fields").put("delen","nee");check(MasterIdentity.app(records).getJSONArray("people").length()==0&&MasterIdentity.known(records).isEmpty(),"persoon niet bekend, ook met een oude gedeelde relatie");
        child.getJSONObject("fields").remove("delen");check(!MasterIdentity.known(records).contains(me),"jijzelf telt niet als bekende persoon");
        profile.getJSONObject("fields").put("persoon",kid);check(MasterIdentity.app(records).getJSONArray("people").getJSONObject(0).getJSONArray("relations").getString(0).equals("ouder"),"eigen identiteit wijzigen keert perspectief om");
        profile.getJSONObject("fields").put("persoon",me);String prompt=MasterIdentity.prompt(MasterIdentity.app(records),1800);check(prompt.contains("Aad")&&prompt.contains("dochter")&&prompt.contains("geen gezag")&&!prompt.contains("PRIVATE_IMAGE"),"AI-context begrensd en zonder kaartfoto's");
        check(MasterIdentity.prompt(app,20).isEmpty(),"te klein budget deelt geen onvolledige JSON");
        JSONObject valid=MasterSchema.clean(new JSONObject().put("type","relatie").put("label","Kindrelatie").put("fields",rel.getJSONObject("fields")));check(valid.getJSONObject("fields").has("vanPersoon"),"relatieschema");
        rel.getJSONObject("fields").put("naarPersoon",me);boolean failed=false;try{MasterSchema.clean(new JSONObject().put("type","relatie").put("label","x").put("fields",rel.getJSONObject("fields")));}catch(Exception e){failed=true;}check(failed,"geen relatie met zichzelf");
        check(MasterSchema.permitted("formulieren","kind","profiel").isEmpty()&&MasterSchema.permitted("formulieren","kind","relatie").isEmpty(),"geen volledig profiel of relatie als formulierbron");
        JSONObject request=PdfFormAssist.request(new JSONObject().put("fields",new JSONArray().put(PdfFormAssistTest.field("begeleider1-relatie","text","begeleider1","relatie"))).put("sources",new JSONArray().put(new JSONObject().put("id","relation").put("label","Vastgelegde relatie").put("role","begeleider1").put("type","relatiecontext").put("fields",new JSONObject().put("relatie","ouder")))).put("text","").toString());
        check(PdfFormAssist.resolve(request,"{\"velden\":[{\"id\":\"begeleider1-relatie\",\"bron\":\"relation\",\"sleutel\":\"relatie\"}]}").getJSONObject(0).getString("value").equals("ouder"),"lokale AI gebruikt gecontroleerde relatiebron");
        JSONArray family=new JSONArray();String longNote=new String(new char[240]).replace('\0','x');
        for(int i=0;i<8;i++)family.put(new JSONObject().put("id","uuid-"+i).put("name","Gezinslid "+i).put("callname","Gezinslid "+i).put("relations",new JSONArray().put("kind")).put("context",longNote).put("detail",longNote));
        JSONObject large=new JSONObject().put("self",new JSONObject().put("name","Ada").put("context",longNote)).put("context",longNote).put("people",family);
        String familyPrompt=MasterIdentity.prompt(large,1200);JSONObject data=new JSONObject(familyPrompt.substring(familyPrompt.indexOf('{')));
        check(data.getJSONArray("people").length()==8,"alle gezinsnamen en relaties krijgen voorrang op lange context");check(data.toString().length()<=1200&&!data.toString().contains("uuid-"),"begrensde AI-context zonder onnodige identifiers");
        String small=MasterIdentity.prompt(large,350);JSONObject limited=new JSONObject(small.substring(small.indexOf('{')));check(limited.getJSONArray("people").length()+limited.getInt("more")==8,"onvolledige gezinslijst expliciet aangeduid");
        // Meer vaste relaties en hun omgekeerde betekenis (nooit geslacht of ontbrekende kennis raden)
        check(MasterIdentity.inverse("opa").equals("kleinkind")&&MasterIdentity.inverse("kleindochter").equals("grootouder")&&MasterIdentity.inverse("broer").equals("broerzus")
                &&MasterIdentity.inverse("schoonouder").equals("schoonkind")&&MasterIdentity.inverse("expartner").equals("expartner")&&MasterIdentity.inverse("oomtante").equals("neefnicht"),"nieuwe relaties hebben een omgekeerde betekenis");
        check(MasterIdentity.inverse("neefnicht").isEmpty(),"neef of nicht: andersom niet raden (oom, tante of ook neef of nicht)");
        for(String code:new String[]{"expartner","broer","zus","opa","oma","kleinzoon","kleindochter","oomtante","neefnicht","schoonouder","schoonkind","zwagerschoonzus","buur","huisgenoot"})check(MasterIdentity.RELATIONS.contains(code),"vaste relatie "+code);
        // Status: alleen een vaste waarde die bij de relatie past
        rel.getJSONObject("fields").put("naarPersoon",kid);
        JSONObject partner=new JSONObject(rel.getJSONObject("fields").toString()).put("relatie","partner").put("status","gehuwd");
        check(MasterSchema.clean(new JSONObject().put("type","relatie").put("label","p").put("fields",partner)).getJSONObject("fields").getString("status").equals("gehuwd"),"partner met status gehuwd");
        JSONObject ouder=new JSONObject(rel.getJSONObject("fields").toString()).put("relatie","kind").put("status","gezag");
        check(MasterSchema.clean(new JSONObject().put("type","relatie").put("label","k").put("fields",ouder)).getJSONObject("fields").getString("status").equals("gezag"),"ouder en kind met ouderlijk gezag");
        for(String[] fout:new String[][]{{"partner","gezag"},{"vriend","gehuwd"},{"kind","gehuwd"},{"partner","getrouwd"}}){
            boolean geweigerd=false;try{MasterSchema.clean(new JSONObject().put("type","relatie").put("label","x").put("fields",new JSONObject(rel.getJSONObject("fields").toString()).put("relatie",fout[0]).put("status",fout[1])));}catch(MasterError e){geweigerd=true;}
            check(geweigerd,"status "+fout[1]+" past niet bij "+fout[0]);
        }
        // De status: met het vinkje Status in de gedeelde app-context en de AI-context (als woorden), zonder vinkje niet
        rel.getJSONObject("fields").put("relatie","partner").put("status","samenwonend");
        String gedeeld=MasterIdentity.app(records).toString();check(gedeeld.contains("partner")&&gedeeld.contains("samenwonend"),"status in de gedeelde app-context");
        check(MasterIdentity.prompt(MasterIdentity.app(records),4000).contains("samenwonend"),"status in de AI-context");
        rel.getJSONObject("fields").put("relatie","kind").put("status","geengezag");check(MasterIdentity.prompt(MasterIdentity.app(records),4000).contains("de ouder heeft geen ouderlijk gezag"),"gezag in woorden");
        rel.getJSONObject("fields").put("relatie","partner").put("status","samenwonend");
        pf.put("aiStatus","nee");gedeeld=MasterIdentity.app(records).toString();check(gedeeld.contains("partner")&&!gedeeld.contains("samenwonend"),"vinkje Status uit: niet in de app-context");pf.remove("aiStatus");
        // Relaties tussen twee anderen die allebei bekend zijn
        String opa="33333333-3333-3333-3333-333333333333";JSONObject grandpa=r(opa,"persoon",new JSONObject().put("naam","Opa").put("roepnaam","Piet").put("delen","ja"));
        JSONObject link=r("link","relatie",new JSONObject().put("vanPersoon",opa).put("naarPersoon",kid).put("relatie","kleindochter"));
        child.getJSONObject("fields").put("delen","ja");JSONArray more=new JSONArray(records.toString()).put(grandpa).put(link);
        JSONObject linked=MasterIdentity.app(more);check(linked.getJSONArray("links").getJSONObject(0).getString("van").equals("Piet")&&linked.getJSONArray("links").getJSONObject(0).getString("relatie").equals("kleindochter"),"relatie tussen anderen");
        check(MasterIdentity.prompt(linked,4000).contains("kleindochter"),"relatie tussen anderen in de prompt");
        grandpa.getJSONObject("fields").put("delen","nee");check(!MasterIdentity.app(new JSONArray(records.toString()).put(grandpa).put(link)).has("links"),"niet bekend: geen relatie tussen anderen");
        child.getJSONObject("fields").remove("delen");
        // Overzicht van Stamgegevens: bij een relatie wie met wie, welke relatie en de status, niet de toelichting; andere onderdelen zien dat niet
        JSONObject sum=MasterStore.listed(rel.put("revision",3),"masterdata");
        check(sum.getString("van").equals(me)&&sum.getString("naar").equals(kid)&&sum.getString("relatie").equals("partner")&&sum.getString("status").equals("samenwonend")&&!sum.toString().contains("Woont bij mij"),"relatie in het overzicht met status, zonder toelichting");
        check(!MasterStore.listed(rel,"formulieren").has("van")&&!MasterStore.summary(rel).has("relatie"),"relatievelden alleen in het overzicht van Stamgegevens, niet bij lezen of in andere onderdelen");
        JSONObject listedSelf=MasterStore.listed(self.put("revision",1),"masterdata",MasterIdentity.known(records));
        check(!listedSelf.has("van")&&listedSelf.getBoolean("contact")&&!listedSelf.toString().contains("0r1-ABC")&&!listedSelf.getBoolean("bekend"),"persoon in het overzicht: gekoppeld ja/nee, niet de sleutel");
        JSONObject listedDoc=MasterStore.listed(doc.put("revision",1),"masterdata");check(listedDoc.getInt("fotos")==1&&!listedDoc.toString().contains("SECRET_DOCUMENT")&&!listedDoc.toString().contains("PRIVATE_IMAGE"),"document in het overzicht: aantal foto's, geen nummer of foto");
        check(!MasterStore.listed(doc,"formulieren").has("fotos")&&!MasterStore.listed(self,"formulieren").has("contact"),"extra overzichtsgegevens alleen in Stamgegevens");
        System.out.println("Profiel, delen, gerichte relaties en veilige AI-context gecontroleerd");
    }
}
