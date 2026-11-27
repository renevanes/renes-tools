package org.apache.tools.tar;

/** Alleen om de app te kunnen compileren in een cloudsessie zonder Maven Central (zie tests/cloudsessie/LEESMIJ.md). Leest niets. */
public class TarInputStream extends java.io.FilterInputStream {
    public TarInputStream(java.io.InputStream in) { super(in); }
    public TarEntry getNextEntry() throws java.io.IOException { throw new java.io.IOException("ant ontbreekt (cloudsessie)"); }
}
