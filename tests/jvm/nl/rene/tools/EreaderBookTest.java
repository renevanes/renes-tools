package nl.rene.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EreaderBookTest {
    static javax.xml.parsers.DocumentBuilderFactory baseFactory;
    /** Android ondersteunt alleen namespace/validation-features; de beveiliging mag daarvan niet afhangen. */
    public static class LimitedFactory extends javax.xml.parsers.DocumentBuilderFactory {
        public javax.xml.parsers.DocumentBuilder newDocumentBuilder() throws javax.xml.parsers.ParserConfigurationException { return baseFactory.newDocumentBuilder(); }
        public void setAttribute(String name, Object value) { baseFactory.setAttribute(name, value); }
        public Object getAttribute(String name) { return baseFactory.getAttribute(name); }
        public void setFeature(String name, boolean value) throws javax.xml.parsers.ParserConfigurationException { throw new javax.xml.parsers.ParserConfigurationException(name); }
        public boolean getFeature(String name) throws javax.xml.parsers.ParserConfigurationException { throw new javax.xml.parsers.ParserConfigurationException(name); }
        public void setNamespaceAware(boolean on) { baseFactory.setNamespaceAware(on); }
        public void setExpandEntityReferences(boolean on) { baseFactory.setExpandEntityReferences(on); }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            }
        }
        return out.toByteArray();
    }
    static Map<String, String> book() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
        e.put("OPS/book.opf", "<package xmlns='http://www.idpf.org/2007/opf' xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Een boek &amp; meer</dc:title><dc:creator>Auteur</dc:creator></metadata><manifest><item id='a' href='chapters/a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='chapters/b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>");
        e.put("OPS/chapters/a.xhtml", "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'><html xmlns='http://www.w3.org/1999/xhtml'><head><title>Hoofdstuk A</title><style>verborgen</style></head><body><h1>Begin</h1><p>Hallo &amp; café&nbsp;wereld.</p><script>niet uitvoeren</script><p>Tweede alinea.</p><img src='https://example.com/beeld'/></body></html>");
        e.put("OPS/chapters/b.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Eerst B</h1><p>In de leesvolgorde.</p></body></html>");
        return e;
    }
    static void rejects(Map<String, String> e, String why) throws Exception {
        boolean rejected = false; try { EreaderBook.parse(zip(e), "boek.epub"); } catch (Exception expected) { rejected = true; }
        check(rejected, why);
    }
    public static void main(String[] args) throws Exception {
        EreaderBook b = EreaderBook.parse(zip(book()), "voorbeeld.EPUB");
        check(b.title.equals("Een boek & meer") && b.author.equals("Auteur"), "metadata uit EPUB");
        check(b.chapters.size() == 2 && b.names.get(0).equals("Eerst B") && b.names.get(1).equals("Begin"), "leesvolgorde uit spine, niet zip of manifest");
        check(b.chapters.get(1).contains("café") && b.chapters.get(1).contains("Tweede alinea.") && b.chapters.get(1).contains("\n"), "tekens en alinea's behouden");
        check(!b.chapters.get(1).contains("niet uitvoeren") && !b.chapters.get(1).contains("verborgen") && !b.chapters.get(1).contains("https://"), "uitsluitend zichtbare tekst, geen scripts, stijl of beeldverwijzingen");
        baseFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        String factoryKey = "javax.xml.parsers.DocumentBuilderFactory", previous = System.getProperty(factoryKey);
        try {
            System.setProperty(factoryKey, LimitedFactory.class.getName());
            check(EreaderBook.parse(zip(book()), "boek.epub").chapters.size() == 2, "EPUB werkt ook als XML-features ontbreken zoals op Android");
            Map<String, String> tricky = book(); tricky.put("OPS/chapters/b.xhtml", "<!DOCTYPE html [<!ENTITY x SYSTEM 'https://example.com/privaat'>]><html><body>&x;</body></html>");
            rejects(tricky, "ook zonder XML-features geen entiteiten");
            byte[] utf16 = "<?xml version='1.0' encoding='UTF-16'?><html><body>Hallo café</body></html>".getBytes(StandardCharsets.UTF_16);
            check(EreaderBook.xml(utf16).getDocumentElement().getTextContent().equals("Hallo café"), "UTF-16 EPUB-inhoud");
        } finally { if (previous == null) System.clearProperty(factoryKey); else System.setProperty(factoryKey, previous); }
        Map<String, String> bad = book(); bad.remove("OPS/chapters/b.xhtml"); rejects(bad, "ontbrekend hoofdstuk weigeren");
        bad = book(); bad.put("META-INF/encryption.xml", "<encryption/>"); rejects(bad, "versleutelde onderdelen weigeren");
        bad = book(); bad.put("OPS/chapters/b.xhtml", "<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><html><body>&x;</body></html>"); rejects(bad, "externe entiteiten weigeren");
        bad = book(); bad.put("OPS/chapters/b.xhtml", "<!DOCTYPE html [<!ENTITY x 'aaaa'><!ENTITY y '&x;&x;&x;'>]><html><body>&y;</body></html>"); rejects(bad, "interne entiteiten weigeren");
        bad = book(); bad.put("OPS/book.opf", bad.get("OPS/book.opf").replace("chapters/b.xhtml", "https://example.com/b.xhtml")); rejects(bad, "externe hoofdstukken weigeren");
        bad = book(); bad.put("OPS/book.opf", bad.get("OPS/book.opf").replace("chapters/b.xhtml", "../../b.xhtml")); rejects(bad, "paden buiten het boek weigeren");
        boolean limit = false; try { EreaderBook.read(new ByteArrayInputStream(new byte[20]), 19); } catch (Exception expected) { limit = true; }
        check(limit, "limiet geldt ook zonder opgegeven bestandsgrootte");
        b = EreaderBook.parse("\uFEFFHallo\r\nwereld.".getBytes(StandardCharsets.UTF_8), "notities.txt");
        check(b.title.equals("notities") && b.chapters.get(0).equals("Hallo\nwereld."), "UTF-8 tekst en regeleinden");
        StringBuilder longText = new StringBuilder(); for (int i = 0; i < 10000; i++) longText.append("Een lange alinea.\n");
        b = EreaderBook.parse(longText.toString().getBytes(StandardCharsets.UTF_8), "lang.txt");
        StringBuilder rebuilt = new StringBuilder(); for (String chapter : b.chapters) rebuilt.append(chapter);
        check(b.chapters.size() > 1 && rebuilt.toString().equals(longText.toString().trim()), "lange tekst zonder verlies in stukken");
        check(AppData.fileRule("ereader/abc/boek.json") == null && AppData.fileRule("ereader/abc/0.txt") == null, "boeken en leesposities gaan mee in appgegevens");
        System.out.println("EreaderBookTest OK");
    }
}
