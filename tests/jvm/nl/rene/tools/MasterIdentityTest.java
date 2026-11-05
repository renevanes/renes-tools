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
        System.out.println("Profiel, delen, gerichte relaties en veilige AI-context gecontroleerd");
    }
}
