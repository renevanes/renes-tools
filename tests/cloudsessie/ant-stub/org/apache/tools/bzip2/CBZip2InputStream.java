package org.apache.tools.bzip2;

/** Alleen om de app te kunnen compileren in een cloudsessie zonder Maven Central (zie tests/cloudsessie/LEESMIJ.md). Leest niets. */
public class CBZip2InputStream extends java.io.InputStream {
    public CBZip2InputStream(java.io.InputStream in, boolean x) throws java.io.IOException { throw new java.io.IOException("ant ontbreekt (cloudsessie)"); }
    @Override public int read() { return -1; }
}
