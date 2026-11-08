package nl.rene.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Hetzelfde werkelijke EPUB-bestand voedt de Java-importtest en de browsertest. */
public class EreaderContentTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] zip(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String,byte[]> e : files.entrySet()) { out.putNextEntry(new ZipEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    static Map<String,byte[]> fixture() throws Exception {
        Map<String,byte[]> f = new LinkedHashMap<>();
        f.put("META-INF/container.xml", utf("<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>"));
        f.put("OPS/book.opf", utf("<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Een boek met beelden</dc:title><dc:creator>De tekenaar</dc:creator><dc:language>nl</dc:language></metadata><manifest>"
                + "<item id='cover' href='cover.xhtml' media-type='application/xhtml+xml'/><item id='one' href='text/one.xhtml' media-type='application/xhtml+xml'/><item id='two' href='text/two.xhtml' media-type='application/xhtml+xml'/>"
                + "<item id='notes' href='notes.xhtml' media-type='application/xhtml+xml'/><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/><item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>"
                + "<item id='png' href='images/land.png' media-type='image/png'/><item id='gif' href='images/dot.gif' media-type='image/gif'/><item id='svg' href='images/vector.svg' media-type='image/svg+xml'/><item id='font' href='font.otf' media-type='font/otf'/></manifest>"
                + "<spine toc='ncx'><itemref idref='cover'/><itemref idref='one'/><itemref idref='two'/></spine></package>"));
        String html = "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Een nieuw hoofdstuk</title><link rel='stylesheet' href='../book.css'/></head><body>";
        f.put("OPS/cover.xhtml", utf(html + "<figure><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 640 2000'><title>De heuvels</title><image width='640' height='480' xlink:href='images/land.png'/></svg></figure></body></html>"));
        StringBuilder longText = new StringBuilder(); for (int i = 0; i < 18; i++) longText.append("<p>De ochtendzon scheen over de groene heuvels. We namen de tijd om het landschap te bekijken en verder te lezen.</p>");
        f.put("OPS/text/one.xhtml", utf(html + "<h1 id='begin'>Een reis &mdash; 📚</h1><p class='intro'>Een <strong>bijzonder</strong> <em>verhaal</em> met café &eacute;n &copy; tekens.<br/>Een nieuwe regel.</p>"
                + "<figure id='land'><img src='../images/land.png' alt='Groene heuvels onder de zon'/><figcaption>Het landschap</figcaption></figure>"
                + "<p>Hier staat een voetnoot<a epub:type='noteref' href='../notes.xhtml#noot%20een'>[1]</a> en een <a href='https://example.org/boek'>website</a>.</p>"
                + "<h2 id='deel twee'>Een nadere blik</h2><ol start='3'><li>Eerst kijken</li><li>Dan lezen</li></ol><blockquote>Er is altijd meer te ontdekken.</blockquote>"
                + "<table><caption>De route</caption><thead><tr><th scope='col'>Plaats</th><th>Dagen</th></tr></thead><tbody><tr><td>Heuvel</td><td>2</td></tr></tbody></table>"
                + "<pre>  één\n    twee  drie</pre><p>Water: H<sub>2</sub>O; kwadraat: x<sup>2</sup>.</p><img src='../images/dot.gif' alt='Een GIF'/>"
                + "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 60'><rect width='100' height='60' fill='#345'/><text x='10' y='35' fill='white'>Vector</text></svg>"
                + "<object type='image/svg+xml' data='../images/vector.svg'/><p><img src='https://example.org/remote.png' alt='Niet lokaal'/></p><script>window.bookRan=true</script><iframe src='https://example.org/frame'/>"
                + longText + "</body></html>"));
        f.put("OPS/text/two.xhtml", utf(html + "<h1>Thuiskomst</h1><p>Het einde van een mooie reis.</p><math xmlns='http://www.w3.org/1998/Math/MathML'><mfrac><mn>1</mn><mn>2</mn></mfrac></math></body></html>"));
        f.put("OPS/notes.xhtml", utf(html + "<aside epub:type='footnote' id='noot een'><p>Dit is de uitleg bij het landschap. <a href='text/one.xhtml#begin'>Terug</a></p></aside></body></html>"));
        f.put("OPS/nav.xhtml", utf(html + "<nav epub:type='toc'><ol><li><a href='text/one.xhtml#begin'>De reis begint</a><ol><li><a href='text/one.xhtml#deel%20twee'>Een nadere blik</a></li></ol></li><li><a href='text/two.xhtml'>Thuiskomst</a></li></ol></nav></body></html>"));
        f.put("OPS/toc.ncx", utf("<ncx><navMap><navPoint><navLabel><text>Route uit NCX</text></navLabel><content src='text/one.xhtml#begin'/><navPoint><navLabel><text>Deel twee</text></navLabel><content src='text/one.xhtml#deel%20twee'/></navPoint></navPoint></navMap></ncx>"));
        f.put("OPS/book.css", utf(".intro{font-style:italic;text-indent:1em;color:red;position:fixed;background:url(https://example.org/bg)} h1{text-align:center} p{margin-top:.6em} body{display:none} @import url(https://example.org/other.css);"));
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(640,480,java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();g.setColor(new java.awt.Color(195,220,232));g.fillRect(0,0,640,480);g.setColor(new java.awt.Color(244,198,95));g.fillOval(450,55,95,95);g.setColor(new java.awt.Color(63,110,87));g.fillOval(-130,260,650,400);g.setColor(new java.awt.Color(105,145,99));g.fillOval(290,220,650,450);g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);f.put("OPS/images/land.png",png.toByteArray());
        f.put("OPS/images/dot.gif", EreaderBytes.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"));
        f.put("OPS/images/vector.svg", utf("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 640 480'><image width='640' height='480' href='land.png'/></svg>"));
        f.put("OPS/font.otf", new byte[]{1,2,3});
        return f;
    }
    static JSONObject find(JSONObject n, String key, String value) throws Exception {
        if (value.equals(n.optString(key))) return n;
        JSONArray c = n.optJSONArray("c"); for (int i = 0; c != null && i < c.length(); i++) { JSONObject found = find(c.getJSONObject(i),key,value); if(found != null) return found; } return null;
    }
    static void exported(EreaderBook book, Map<String,byte[]> files) throws Exception {
        JSONObject out = new JSONObject().put("chapters",new JSONArray(book.names)).put("linear",new JSONArray(book.linear)).put("toc",book.toc).put("content",new JSONArray(book.content)).put("title",book.title).put("language",book.language);
        JSONArray images = new JSONArray();
        for (EreaderBook.Asset a : book.assets) {
            byte[] data = a.bytes;
            if (a.mime.equals("image/svg+xml")) data = utf(EreaderSvg.clean(data, source -> { byte[] raw = files.get(EreaderBook.path(a.base,source)); return raw == null ? "" : "data:image/png;base64," + EreaderBytes.encode(raw); }));
            images.put(new JSONObject().put("src","data:" + a.mime + ";base64," + EreaderBytes.encode(data)).put("width",640).put("height",a.mime.equals("image/svg+xml") && new String(data, StandardCharsets.UTF_8).contains("640 2000") ? 2000 : 480));
        }
        out.put("images",images);Files.createDirectories(Paths.get("build/test-data"));Files.write(Paths.get("build/test-data/ereader-rich.json"),utf(out.toString()));
    }
    public static void main(String[] args) throws Exception {
        Map<String,byte[]> files = fixture(); EreaderBook b = EreaderBook.parse(zip(files),"inhoud.epub");
        check(b.chapters.size()==5 && b.chapters.get(0).isEmpty() && b.linear.get(0) && !b.linear.get(4),"beeldpagina en niet-lineaire voetnoten behouden");
        check(b.assets.size()==5 && b.assets.get(2).mime.equals("image/gif"),"PNG, GIF, SVG-wrapper en inline-SVG registreren");
        check(b.assets.get(4).base.equals("OPS/images/vector.svg"),"los SVG-bestand bewaart zijn eigen basispad voor lokale afbeeldingen");
        String text = b.chapters.get(1);JSONObject tree=b.content.get(1).getJSONObject("content");
        check(text.contains("— 📚")&&text.contains("café én ©")&&text.contains("  één\n    twee  drie"),"XHTML-entiteiten en codewitruimte behouden");
        check(find(tree,"tag","h1")!=null&&find(tree,"tag","strong")!=null&&find(tree,"tag","table")!=null&&find(tree,"tag","sup")!=null,"semantische opmaak behouden");
        check(find(tree,"tag","script")==null&&find(tree,"tag","iframe")==null&&!tree.toString().contains("position")&&!tree.toString().contains("background"),"boekcode en vrije CSS nooit doorgeven");
        JSONObject link=find(tree,"tag","a").getJSONObject("target");int notes=link.getInt("chapter");
        check(notes>2&&link.getString("fragment").equals("noot een")&&!b.linear.get(notes),"voetnoot buiten de spine met gedecodeerd fragment");
        check(b.toc.length()==3&&b.toc.getJSONObject(1).getInt("depth")==1&&text.substring(b.toc.getJSONObject(1).getInt("start")).trim().startsWith("Een nadere blik"),"geneste EPUB3-inhoudsopgave met precieze hoofdstukposities");
        check(b.legacyMap.size()==2&&b.legacyMap.get(0)==1&&b.legacyMap.get(1)==2,"oud hoofdstuk volgt ingevoegde beeldpagina");
        String old=EreaderBook.plain(EreaderBook.xml(files.get("OPS/text/one.xhtml")));int oldStart=old.indexOf("bijzonder");
        EreaderUpgrade upgrade=new EreaderUpgrade(b,index->index==0?old:EreaderBook.plain(EreaderBook.xml(files.get("OPS/text/two.xhtml"))));
        JSONObject previous=new JSONObject().put("chapter",0).put("anchor",oldStart).put("title","Mijn titel").put("favorite",true).put("finished",true);
        JSONObject migrated=upgrade.metadata(previous);
        check(migrated.getInt("chapter")==1&&text.substring(migrated.getInt("anchor")).startsWith("bijzonder")&&migrated.getBoolean("favorite")&&migrated.getBoolean("finished")&&migrated.getString("title").equals("Mijn titel"),"leesplek en eigen boekgegevens volgen inhoud");
        JSONObject mark=new JSONObject().put("id","eigen-notitie").put("kind","highlight").put("chapter",0).put("start",oldStart).put("end",oldStart+9).put("note","Bewaren");
        JSONObject after=upgrade.marks(new JSONArray().put(mark)).getJSONObject(0);
        check(after.getInt("chapter")==1&&text.substring(after.getInt("start"),after.getInt("end")).equals("bijzonder")&&after.getString("id").equals("eigen-notitie")&&mark.getInt("chapter")==0&&previous.getInt("chapter")==0,"notities en selectie behouden zonder oude gegevens te veranderen");
        boolean refused=false;try{EreaderUpgrade.map("oude inhoud","andere inhoud");}catch(Exception expected){refused=true;}check(refused,"onzekere migratie afbreken");
        String svg=EreaderSvg.clean(utf("<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'><script>bad</script><foreignObject>html</foreignObject><rect width='2' fill='url(https://example.org/x)'/><image href='https://example.org/x'/><style>.a{fill:red;filter:url(https://example.org/f)}</style></svg>"),src->"");
        check(!svg.contains("onload")&&!svg.contains("script")&&!svg.contains("foreignObject")&&!svg.contains("https:")&&svg.contains("fill:red"),"SVG actief en extern materiaal verwijderen");
        check(EreaderSvg.clean(utf("<svg width='600' height='900'><rect width='600' height='900'/></svg>"),src->"").contains("viewBox=\"0 0 600.0 900.0\""),"staande SVG zonder viewBox houdt verhouding en schaalt volledig");
        refused=false;try{EreaderSvg.clean(utf("<svg><defs><g id='a'><use href='#a'/></g></defs><use href='#a'/></svg>"),src->"");}catch(Exception expected){refused=true;}check(refused,"cyclische SVG-verwijzingen begrenzen");
        exported(b,files);
        files.put("OPS/nav.xhtml",utf("<html><body>Geen navigatie</body></html>"));b=EreaderBook.parse(zip(files),"ncx.epub");
        check(b.toc.length()==2&&b.toc.getJSONObject(0).getString("label").equals("Route uit NCX")&&b.toc.getJSONObject(1).getInt("depth")==1,"EPUB2 NCX als alternatief");
        files.put("META-INF/encryption.xml",utf("<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><CipherData><CipherReference URI='OPS/font.otf'/></CipherData></EncryptedData></encryption>"));
        check(EreaderBook.parse(zip(files),"font.epub").chapters.size()==5,"alleen lettertype-obfuscatie verhindert het lezen niet");
        files.put("META-INF/encryption.xml",utf("<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><CipherReference URI='OPS/text/one.xhtml'/></EncryptedData></encryption>"));
        refused=false;try{EreaderBook.parse(zip(files),"drm.epub");}catch(Exception expected){refused=true;}check(refused,"versleutelde hoofdstukken blijven geweigerd");
        for(int size=0;size<257;size++){byte[] a=new byte[size];for(int i=0;i<size;i++)a[i]=(byte)(i*37);check(java.util.Arrays.equals(a,EreaderBytes.decode(EreaderBytes.encode(a))),"base64 roundtrip");}
        check(AppData.fileRule("ereader/id/content-v3/bron.epub")==null&&AppData.fileRule("ereader/id/content-v3/images/0.bin")==null,"inhoud en bron in bestaande appbackup");
        System.out.println("EreaderContentTest OK");
    }
}
