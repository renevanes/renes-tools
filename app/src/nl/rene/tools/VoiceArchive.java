package nl.rene.tools;

import java.io.*;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.apache.tools.bzip2.CBZip2InputStream;

/** Alleen vaste, geverifieerde modelarchieven; geen links, vreemde paden of onbeperkt uitpakken. */
final class VoiceArchive {
    private VoiceArchive(){ }
    static String hash(File file)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte x:d.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return out.toString();
    }
    static CBZip2InputStream bzip(InputStream raw)throws IOException {if(raw.read()!='B'||raw.read()!='Z')throw new IOException();return new CBZip2InputStream(raw,true);}
    static void extract(File archive,File stage,String prefix,BooleanSupplier stop)throws Exception {
        if(stage.exists())erase(stage);if(!stage.mkdirs())throw new IOException();
        long total=0;int count=0;Set<String> seen=new HashSet<>();
        try(InputStream raw=new FileInputStream(archive);CBZip2InputStream bz=bzip(raw);TarInputStream tar=new TarInputStream(bz)){
            TarEntry entry;byte[] buf=new byte[65536];
            while((entry=tar.getNextEntry())!=null){
                if(stop.getAsBoolean())throw new ModelFetch.Stopped();if(++count>2000)throw new IOException();
                String name=entry.getName();if(name.equals(prefix+"/")&&entry.isDirectory())continue;
                if(!name.startsWith(prefix+"/"))throw new IOException();String rel=name.substring(prefix.length()+1);if(entry.isDirectory()&&rel.endsWith("/"))rel=rel.substring(0,rel.length()-1);
                if(entry.getLinkFlag()!=0&&entry.getLinkFlag()!='0'&&entry.getLinkFlag()!='5')throw new IOException();
                if(AppData.safeRel(rel)==null||!seen.add(rel)||entry.isGNUSparse()||(!entry.isDirectory()&&!entry.isFile()))throw new IOException();
                if(entry.getSize()<0||entry.getSize()>80L*1024*1024||(total+=entry.getSize())>160L*1024*1024)throw new IOException();
                File target=new File(stage,rel);if(!target.getCanonicalPath().startsWith(stage.getCanonicalPath()+File.separator))throw new IOException();
                if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException();continue;}
                if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException();
                long bytes=0;try(OutputStream out=new FileOutputStream(target)){int n;while((n=tar.read(buf))!=-1){if(stop.getAsBoolean())throw new ModelFetch.Stopped();bytes+=n;if(bytes>entry.getSize())throw new IOException();out.write(buf,0,n);}}if(bytes!=entry.getSize())throw new IOException();
            }
        }catch(Throwable e){erase(stage);throw e;}
    }
    static void erase(File f)throws IOException {File[] files=f.listFiles();if(files!=null)for(File x:files)erase(x);if(f.exists()&&!f.delete())throw new IOException();}
}
