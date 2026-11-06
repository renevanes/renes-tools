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
        JSONObject app=MasterIdentity.app(records);String json=app.toString();
        check(app.getJSONObject("self").getString("callname").equals("Aad"),"roepnaam");check(app.getJSONArray("people").getJSONObject(0).getJSONArray("relations").getString(0).equals("dochter"),"gerichte relatie");
        for(String secret:new String[]{"123456782","1980-01-01","SECRET_DOCUMENT","PRIVATE_IMAGE","document","images","geboortedatum","bsn"})check(!json.contains(secret),"geen identiteit- of geboortedata in app-context: "+secret);
        check(MasterIdentity.between(rel,kid,me).equals("ouder"),"omgekeerde relatie zonder geslacht aan te nemen");
        check(MasterIdentity.inverse("noodcontact").isEmpty()&&MasterIdentity.inverse("anders").isEmpty(),"geen omgekeerde betekenis raden");
        rel.getJSONObject("fields").put("delen","nee");check(MasterIdentity.app(records).getJSONArray("people").length()==0,"ongedeelde relaties blijven binnen de kluis");rel.getJSONObject("fields").put("delen","ja");
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
        // De status blijft in de kluis: niet in de gedeelde app-context of de AI-context
        rel.getJSONObject("fields").put("relatie","partner").put("status","samenwonend");
        String gedeeld=MasterIdentity.app(records).toString();check(gedeeld.contains("partner")&&!gedeeld.contains("samenwonend"),"status niet in de gedeelde app-context");
        check(!MasterIdentity.prompt(MasterIdentity.app(records),4000).contains("samenwonend"),"status niet in de AI-context");
        // Overzicht van Stamgegevens: bij een relatie wie met wie en welke relatie, niet de status of toelichting; andere onderdelen zien dat niet
        JSONObject sum=MasterStore.listed(rel.put("revision",3),"masterdata");
        check(sum.getString("van").equals(me)&&sum.getString("naar").equals(kid)&&sum.getString("relatie").equals("partner")&&!sum.toString().contains("samenwonend")&&!sum.toString().contains("Woont bij mij"),"relatie in het overzicht zonder status en toelichting");
        check(!MasterStore.listed(rel,"formulieren").has("van")&&!MasterStore.summary(rel).has("relatie"),"relatievelden alleen in het overzicht van Stamgegevens, niet bij lezen of in andere onderdelen");
        check(!MasterStore.listed(self.put("revision",1),"masterdata").has("van"),"persoon in het overzicht zonder relatievelden");
        System.out.println("Profiel, delen, gerichte relaties en veilige AI-context gecontroleerd");
    }
}
