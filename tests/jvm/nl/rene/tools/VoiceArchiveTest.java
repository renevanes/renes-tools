package nl.rene.tools;
import java.io.*;
import java.nio.file.Files;
import org.apache.tools.tar.*;
import org.apache.tools.bzip2.*;

public class VoiceArchiveTest {
    interface Checked{void run()throws Exception;}
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static void rejects(Checked checked)throws Exception{try{checked.run();throw new AssertionError("Ongeldig archief toegestaan");}catch(IOException expected){ }}
    static File archive(File root,String name,char type,boolean duplicate)throws Exception{
        File file=new File(root,"stem.tar.bz2");
        try(OutputStream raw=new FileOutputStream(file)){raw.write('B');raw.write('Z');try(CBZip2OutputStream bz=new CBZip2OutputStream(raw);TarOutputStream tar=new TarOutputStream(bz)){tar.setLongFileMode(TarOutputStream.LONGFILE_GNU);
            for(int i=0;i<(duplicate?2:1);i++){TarEntry e=new TarEntry(name,true);e.setLinkFlag((byte)type);if(type=='2'||type=='1')e.setLinkName("../../buiten");byte[] data="modeldata".getBytes("UTF-8");e.setSize(type=='0'?data.length:0);tar.putNextEntry(e);if(type=='0')tar.write(data);tar.closeEntry();}
        }}return file;
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("voice-archive").toFile(),stage=new File(root,"uit");
        try{
            File file=archive(root,"stem/sub/model.onnx",'0',false);VoiceArchive.extract(file,stage,"stem",()->false);check(new File(stage,"sub/model.onnx").length()==9,"gewone modelbestanden uitgepakt");
            String original=VoiceArchive.hash(file);try(OutputStream out=new FileOutputStream(file,true)){out.write(0);}check(!original.equals(VoiceArchive.hash(file)),"gewijzigd archief heeft ander controlegetal");
            for(String bad:new String[]{"/stem/buiten","../stem/buiten","stem/"+new String(new char[120]).replace('\0','a')+"/../buiten","stem/../buiten","ander/model.onnx","stem/sub/../../buiten","stem/a\\b","stem/C:bestand"}){File invalid=archive(root,bad,'0',false);rejects(()->VoiceArchive.extract(invalid,stage,"stem",()->false));check(!stage.exists(),"geen gedeeltelijke bestanden na weigeren");}
            for(char special:new char[]{'1','2','3','4','6'}){File invalid=archive(root,"stem/link",special,false);rejects(()->VoiceArchive.extract(invalid,stage,"stem",()->false));}
            File duplicate=archive(root,"stem/model.onnx",'0',true);rejects(()->VoiceArchive.extract(duplicate,stage,"stem",()->false));
            File good=archive(root,"stem/model.onnx",'0',false);try{VoiceArchive.extract(good,stage,"stem",()->true);throw new AssertionError();}catch(ModelFetch.Stopped expected){check(!stage.exists(),"afgebroken installatie laat geen model achter");}
            check(ReadAloud.validId("boek-123_1")&&!ReadAloud.validId(".."),"requestformaat");
            check(!ReadAloud.validId("a\nb")&&!ReadAloud.validId(new String(new char[91]).replace('\0','a')),"ongeldige requestidentiteit geweigerd");
            check(ReadAloud.speakable("Hoi\u0000\ud83d\ude00\ud800!").equals("Hoi!"),"geen NUL of ongeldige JNI-tekens");
            for(VoiceModels.Voice voice:VoiceModels.VOICES){check(voice.sha.matches("[0-9a-f]{64}")&&voice.size>10000000&&voice.url().startsWith("https://github.com/k2-fsa/sherpa-onnx/"),"stemmen hebben vaste herkomst en SHA");}
            for(VoiceModels.Voice voice:VoiceModels.VOICES){String[] u=voice.urls();check(u[0].equals(voice.url())&&(u.length==1||u.length==2&&u[1].startsWith("https://")),"eerst de bron, daarna hooguit één https-kopie");}
            {StringBuilder nc=new StringBuilder();for(VoiceModels.Voice voice:VoiceModels.VOICES){check(java.util.Arrays.asList("best","goed","compact").contains(voice.quality),"geldige kwaliteit: "+voice.id);check(voice.language.matches("nl|en"),"taal nl of en: "+voice.id);check(voice.folder.endsWith("-int8")==voice.quality.equals("compact"),"compact = int8, anders volledige precisie: "+voice.id);if(voice.nonCommercial)nc.append(voice.id).append(' ');}
             check(nc.toString().trim().equals("tts-nl-dii tts-nl-miro"),"precies Dii en Miro hebben een niet-commerciële licentie ("+nc+")");
             check(VoiceModels.rank(VoiceModels.model("tts-nl-dii"))>VoiceModels.rank(VoiceModels.model("tts-nl-pim"))&&VoiceModels.rank(VoiceModels.model("tts-nl-pim"))>VoiceModels.rank(VoiceModels.model("tts-nl-ronnie")),"rangorde: beste, goed, compact");}
            VoiceModels.Voice probe=new VoiceModels.Voice("x","x","nl","map","m.onnx",1L,"0");check(probe.withMirror("http://kopie/x").urls().length==1&&probe.withMirror("https://kopie/x").urls()[1].equals("https://kopie/x"),"kopie alleen via https");
        }finally{VoiceArchive.erase(root);}
        System.out.println("Modelarchief: gewone installatie, padcontrole, bestandstypen, dubbelen en afbreken gecontroleerd");
    }
}
