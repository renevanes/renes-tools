package nl.rene.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PdfOverlayTest {
    static int checks;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checks++; }
    static byte[] edit(PdfSplit pdf, int page) throws Exception {
        PdfOverlay.Box box = PdfOverlay.box(pdf,page);
        int w=80,h=Math.max(1,(int)Math.round(w*box.height()/box.width()));
        int[] pixels=new int[w*h];
        for(int y=0;y<h/4;y++)for(int x=0;x<w/4;x++)pixels[y*w+x]=0xFFFF0000;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        PdfOverlay.write(pdf,Collections.singletonMap(page,new PdfOverlay.Image(w,h,pixels)),out);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        for(String file:new String[]{"gewoon.pdf","compact.pdf","hybride.pdf"}){
            byte[] original=Files.readAllBytes(new File("tests/jvm/pdf/"+file).toPath());
            PdfSplit source=new PdfSplit(original);byte[] updated=edit(source,0);PdfSplit result=new PdfSplit(updated);
            check(Arrays.equals(original,Arrays.copyOf(updated,original.length)),file+": originele bytes behouden");
            check(result.pageCount()==source.pageCount(),file+": alle pagina's behouden");
            Object contents=result.resolve(result.pages.get(0).get("Contents"));
            check(contents instanceof List && ((List<?>)contents).size()>=3,file+": toevoeging na originele inhoud");
            check(Arrays.equals(PdfTest.contents(source,0),((PdfSplit.Stream)result.resolve(((List<?>)contents).get(1))).raw()),file+": originele paginastream behouden");
            Object resources=result.resolve(result.pages.get(0).get("Resources"));
            PdfSplit.Dict objects=(PdfSplit.Dict)result.resolve(((PdfSplit.Dict)resources).get("XObject"));
            PdfSplit.Stream image=(PdfSplit.Stream)result.resolve(objects.get("RTEdit"));
            check(image.dict.get("SMask") instanceof PdfSplit.Ref,file+": transparantie met softmask");
            check(result.trailer.get("Prev") instanceof PdfSplit.Num,file+": incrementele xref-keten");
            byte[] again=edit(result,0);check(new PdfSplit(again).pageCount()==source.pageCount(),file+": opnieuw bewerken mogelijk");
            Files.write(new File("build/pdfedit-"+file).toPath(),updated);
        }
        byte[] hybrid=Files.readAllBytes(new File("tests/jvm/pdf/hybride.pdf").toPath());
        PdfSplit hs=new PdfSplit(hybrid);long oldxref=PdfOverlay.previous(hs);
        ByteArrayOutputStream hb=new ByteArrayOutputStream();PdfWriter.Out ho=new PdfWriter.Out(hb);ho.bytes(hybrid);ho.ascii("\n");
        long rootOffset=ho.pos;ho.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        long newxref=ho.pos;ho.ascii("xref\n1 1\n"+String.format(java.util.Locale.ROOT,"%010d 00000 n \n",rootOffset)+"trailer\n<< /Root 1 0 R /Size 7 /Prev "+oldxref+" >>\nstartxref\n"+newxref+"\n%%EOF\n");
        PdfSplit nested=new PdfSplit(hb.toByteArray());
        check(nested.hybridXref && nested.trailer.get("XRefStm")==null,"hybride xref in oudere sectie herkend");
        byte[] nestedOut=edit(nested,0);check(new PdfSplit(nestedOut).pageCount()==1,"oudere hybride objecten behouden");
        Files.write(new File("build/pdfedit-hybride-increment.pdf").toPath(),nestedOut);
        // CropBox, rotatie, UserUnit en catalogusgegevens blijven behouden.
        PdfSplit fixture=new PdfSplit(Files.readAllBytes(new File("tests/jvm/pdf/gewoon.pdf").toPath()));
        PdfSplit.Dict catalog=(PdfSplit.Dict)fixture.resolve(fixture.trailer.get("Root"));
        catalog.m.put("Lang",new PdfSplit.Str("nl-NL".getBytes("US-ASCII")));
        for(int rotation:new int[]{0,90,180,270}){
            fixture.pages.get(0).m.put("Rotate",new PdfSplit.Num(""+rotation));
            fixture.pages.get(0).m.put("CropBox",Arrays.asList(new PdfSplit.Num("20"),new PdfSplit.Num("30"),new PdfSplit.Num("420"),new PdfSplit.Num("630")));
            byte[] rendered=edit(fixture,0);PdfSplit result=new PdfSplit(rendered);
            PdfOverlay.Box box=PdfOverlay.box(result,0);
            check(box.rotation==rotation && box.x==20 && box.y==30,"uitsnede en rotatie "+rotation);
            check(box.width()==(rotation%180==0?400:600),"zichtbare breedte "+rotation);
            check(((PdfSplit.Dict)result.resolve(result.trailer.get("Root"))).get("Lang") instanceof PdfSplit.Str,"catalogus behouden "+rotation);
            Files.write(new File("build/pdfedit-rot"+rotation+".pdf").toPath(),rendered);
        }
        boolean rejected=false;try{PdfOverlay.previous(new PdfSplit(Files.readAllBytes(new File("tests/jvm/pdf/kapot.pdf").toPath())));}catch(java.io.IOException e){rejected=true;}
        check(rejected,"beschadigde xref niet stilzwijgend gebruiken");
        byte[] good=edit(fixture,0);
        String serialized=new String(good,"ISO-8859-1");
        for(String prev:new String[]{"999999999","0","ongeldig"}){
            String broken=serialized.replaceFirst("/Prev [0-9]+", "/Prev "+prev);
            rejected=false;try{PdfOverlay.previous(new PdfSplit(broken.getBytes("ISO-8859-1")));}catch(java.io.IOException e){rejected=true;}
            check(rejected,"ongeldige oudere xref geweigerd: "+prev);
        }
        int sx=serialized.lastIndexOf("startxref");String pointer=serialized.substring(sx+9).trim().split("\\s+")[0];
        String cyclic=serialized.replaceFirst("/Prev [0-9]+", "/Prev "+pointer);
        rejected=false;try{PdfOverlay.previous(new PdfSplit(cyclic.getBytes("ISO-8859-1")));}catch(java.io.IOException e){rejected=true;}
        check(rejected,"cyclische xref geweigerd");
        for(String type:new String[]{"Widget","Square","FreeText","Link"}){
            PdfSplit.Dict annot=new PdfSplit.Dict();annot.m.put("Subtype",new PdfSplit.Name(type));
            fixture.pages.get(0).m.put("Annots",Arrays.asList(annot));
            rejected=false;try{edit(fixture,0);}catch(java.io.IOException e){rejected=true;}
            check(rejected,"toevoeging niet achter annotatie laten verdwijnen: "+type);
            rejected=false;try{PdfOverlay.editableBox(fixture,0);}catch(java.io.IOException e){rejected=true;}
            check(rejected,"ook preview weigert annotatie: "+type);
        }
        fixture.pages.get(0).m.remove("Annots");
        rejected=false;try{edit(fixture,999);}catch(java.io.IOException e){rejected=true;}check(rejected,"ongeldige pagina geweigerd");
        rejected=false;try{new PdfOverlay.Image(5000,1,new int[5000]);}catch(java.io.IOException e){rejected=true;}check(rejected,"te grote tekenlaag geweigerd");
        System.out.println("Alle "+checks+" PDF-bewerkingstests geslaagd");
    }
}
