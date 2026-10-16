package nl.rene.tools;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Eén herbruikbare handtekening, versleuteld buiten cache en Android-backups. */
final class PdfSignature {
    static final int MAX_BYTES=800000, MAX_POINTS=10000, MAX_PATHS=200;
    private PdfSignature() { }
    static AtomicFile file(Context c) { return new AtomicFile(new File(c.getNoBackupFilesDir(),"pdf-handtekening.bin")); }

    static void validate(double ratio, double[][][] paths) {
        if(!finite(ratio)||ratio<.01||ratio>10||paths==null||paths.length<1||paths.length>MAX_PATHS)
            throw new IllegalArgumentException("Ongeldige handtekening");
        int count=0;boolean moved=false;
        for(double[][] path:paths){
            if(path==null||path.length<2)throw new IllegalArgumentException("Teken eerst je handtekening");
            count+=path.length;if(count>MAX_POINTS)throw new IllegalArgumentException("Handtekening is te groot");
            for(int i=0;i<path.length;i++){
                double[] p=path[i];
                if(p==null||p.length!=2||!finite(p[0])||!finite(p[1])||p[0]<0||p[0]>1||p[1]<0||p[1]>1)
                    throw new IllegalArgumentException("Ongeldige handtekening");
                if(i>0&&(p[0]!=path[0][0]||p[1]!=path[0][1]))moved=true;
            }
        }
        if(!moved)throw new IllegalArgumentException("Teken eerst je handtekening");
    }
    static boolean finite(double n) { return !Double.isNaN(n)&&!Double.isInfinite(n); }

    static JSONObject normalize(String json) throws Exception {
        if(json==null||json.length()>MAX_BYTES)throw new Exception("Handtekening is te groot");
        JSONObject o=new JSONObject(json);JSONArray a=o.getJSONArray("paths");
        if(a.length()>MAX_PATHS)throw new Exception("Handtekening heeft te veel lijnen");
        double[][][] paths=new double[a.length()][][];int count=0;
        for(int i=0;i<a.length();i++){
            JSONArray path=a.getJSONArray(i);count+=path.length();
            if(count>MAX_POINTS)throw new Exception("Handtekening is te groot");
            paths[i]=new double[path.length()][2];
            for(int j=0;j<path.length();j++){
                JSONArray point=path.getJSONArray(j);if(point.length()!=2)throw new Exception("Ongeldige handtekening");
                paths[i][j][0]=point.getDouble(0);paths[i][j][1]=point.getDouble(1);
            }
        }
        double ratio=o.getDouble("ratio");validate(ratio,paths);
        String color=o.optString("color","#111111");if(!color.matches("#[0-9a-fA-F]{6}"))throw new Exception("Ongeldige kleur");
        JSONArray clean=new JSONArray();for(double[][] path:paths){JSONArray line=new JSONArray();for(double[] p:path)line.put(new JSONArray().put(p[0]).put(p[1]));clean.put(line);}
        return new JSONObject().put("version",1).put("ratio",ratio).put("color",color).put("paths",clean);
    }
    static synchronized JSONObject load(Context c) throws Exception {
        AtomicFile f=file(c);
        try(FileInputStream in=f.openRead()){
            if(in.getChannel().size()>MAX_BYTES+128)throw new Exception("Bewaarde handtekening is te groot");
            ByteArrayOutputStream out=new ByteArrayOutputStream();Kluis.decrypt(in,out);
            return normalize(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }catch(FileNotFoundException e){if(f.getBaseFile().exists()||new File(f.getBaseFile().getPath()+".bak").exists())throw e;return null;}
    }
    static synchronized void save(Context c,String json) throws Exception {
        byte[] bytes=normalize(json).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new Exception("Handtekening is te groot");
        // openRead herstelt zo nodig een bestaande AtomicFile-backup vóór de gecontroleerde vervanging.
        AtomicFile f=file(c);
        if(new File(f.getBaseFile().getPath()+".bak").exists())try(FileInputStream ignored=f.openRead()){}
        Kluis.writeEncrypted(f.getBaseFile(),bytes);
    }
    static synchronized void delete(Context c) throws Exception {
        AtomicFile f=file(c);f.delete();
        new File(f.getBaseFile().getPath()+".writing").delete();
        if(f.getBaseFile().exists()||new File(f.getBaseFile().getPath()+".bak").exists())throw new Exception("Handtekening verwijderen lukt niet");
    }
}
