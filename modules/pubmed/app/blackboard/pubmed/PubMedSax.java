package blackboard.pubmed;

import blackboard.mesh.Entry;
import blackboard.mesh.Descriptor;
import blackboard.mesh.Qualifier;
import blackboard.mesh.MeshDb;
import blackboard.mesh.MeshKSource;
import blackboard.mesh.Concept;
import blackboard.mesh.CommonDescriptor;

import javax.xml.parsers.*;
import org.xml.sax.helpers.*;
import org.xml.sax.*;

import java.util.*;
import java.io.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

import play.Logger;

public class PubMedSax extends DefaultHandler {
    static final byte[] TAG_START_PA = "<PubmedArticle>".getBytes();
    static final byte[] TAG_STOP_PA = "</PubmedArticle>".getBytes();
    static final byte[] TAG_XML = "<?xml version=\"1.0\"?>\n".getBytes();

    static final long EPOCH = 18000000l;
    
    StringBuilder content = new StringBuilder ();
    LinkedList<String> stack = new LinkedList<>();
    StringBuilder abstext = new StringBuilder ();
    StringBuilder title = new StringBuilder ();
    PubMedDoc doc;
    Calendar cal = Calendar.getInstance();
    String idtype, ui, majorTopic, lang, pubstatus;
    MeshHeading mh;
    final MeshDb mesh;
    BiPredicate<PubMedSax, PubMedDoc> consumer;
    Map<String, Object> author = new LinkedHashMap<>();
    Map<String, Object> grant = new LinkedHashMap<>();
    Map<String, Object> reference = new LinkedHashMap<>();
    List<Long> deleteCitations = new ArrayList<>();
    
    CaptureInputStream cis;
    String source;
    Long timestamp;
    
    static class CaptureInputStream extends FilterInputStream {
        ByteArrayOutputStream buf = new ByteArrayOutputStream (1024);
        // other xml
        ByteArrayOutputStream xml = new ByteArrayOutputStream (1024);
        int start, stop;
        Consumer<byte[]> consumer;
        boolean done;

        CaptureInputStream (InputStream is) {
            this (is, null);
        }
        
        CaptureInputStream (InputStream is, Consumer<byte[]> consumer) {
            super (is);
            this.consumer = consumer;
            clear ();
        }

        void clear () {
            start = 0;
            stop = 0;
            buf.reset();
            try {
                buf.write(TAG_XML);
            }
            catch (IOException ex) {
                Logger.error("Can't write xml", ex);
            }
        }

        void publish () {
            if (buf.size() > TAG_XML.length) {
                byte[] xml = buf.toByteArray();
                if (consumer != null)
                    consumer.accept(xml);
                this.xml.reset();
            }
        }
        
        void add (byte b) {
            if (start == TAG_START_PA.length) {
                buf.write(b);
                xml.write(b);
                if (stop < TAG_STOP_PA.length && b == TAG_STOP_PA[stop]) {
                    if (++stop == TAG_STOP_PA.length) {
                        publish ();
                        clear ();
                    }
                }
                else stop = 0;
            }
            else if (start < TAG_START_PA.length && b == TAG_START_PA[start]) {
                buf.write(b);
                xml.write(b);
                ++start;
            }
            else {
                xml.write(b);
                clear ();
            }
        }

        public int read () throws IOException {
            if (done) return -1;
            int ch = super.read();
            if (ch != -1) {
                byte b = (byte)(ch & 0xff);
                add (b);
            }
            else {
                publish ();
            }
            return ch;
        }
        
        public int read (byte[] b) throws IOException {
            if (done) return -1;
            int nb = super.read(b);
            if (nb != -1) {
                for (int i = 0; i < nb; ++i)
                    add (b[i]);
            }
            else {
                publish ();
            }
                
            return nb;
        }
        
        public int read (byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            int nb = super.read(b, off, len);
            if (nb != -1) {
                for (int i = 0; i < nb; ++i)
                    add (b[off+i]);
            }
            else {
                publish ();
            }
            return nb;
        }

        public byte[] getOtherXML () { return xml.toByteArray(); }
        protected void setDone (boolean done) { this.done = done; }
    }
    
    public PubMedSax (BiPredicate<PubMedSax, PubMedDoc> consumer) {
        this (null, consumer);
    }

    public PubMedSax (MeshDb mesh, BiPredicate<PubMedSax, PubMedDoc> consumer) {
        this.mesh = mesh;
        this.consumer = consumer;
    }

    public void setSource (String source) { this.source = source; }
    public String getSource () { return source; }
    public void setTimestamp (Long timestamp) { this.timestamp = timestamp; }
    public Long getTimestamp () { return timestamp; }
    public List<Long> getDeleteCitations () {
        return Collections.unmodifiableList(deleteCitations);
    }
    
    public void parse (File file) throws Exception {
        source = file.getName();
        int pos = source.indexOf('.');
        if (pos > 0)
            source = source.substring(0, pos);
        timestamp = file.lastModified();
        parse (new java.util.zip.GZIPInputStream
               (new FileInputStream (file)));
    }
    
    public void parse (InputStream is) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setFeature
            ("http://apache.org/xml/features/nonvalidating/load-external-dtd",
             false);
        spf.setFeature("http://xml.org/sax/features/namespaces", false);
        spf.setFeature("http://xml.org/sax/features/validation", false);
        spf.setFeature
            ("http://apache.org/xml/features/nonvalidating/load-dtd-grammar",
            false);
        SAXParser sax = spf.newSAXParser();
        
        // we're doing this so that we can capture the PubmedArticle
        // xml for storage!
        cis = new CaptureInputStream (is, xml -> {
                doc = new PubMedDoc ();
                doc.xml = xml;
                doc.source = source;
                doc.timestamp = timestamp;
                try (InputStream iis = new ByteArrayInputStream (xml)) {
                    sax.parse(iis, PubMedSax.this);
                }
                catch (Exception ex) {
                    Logger.error("Can't parse XML:\n"+new String (xml), ex);
                }
            });
        
        byte[] buf = new byte[8192];
        for (int nb; (nb = cis.read(buf, 0, buf.length)) != -1;)
            ;
        
        byte[] xml = cis.getOtherXML();
        try {
            // catch delete citations
            sax.parse(new ByteArrayInputStream (xml), this);
        }
        catch (Exception ex) {
        }
    }
    
    public void startDocument () {
    }
    
    public void endDocument () {
    }
    
    public void startElement (String uri, String localName, String qName, 
                              Attributes attrs) {
        switch (qName) {
        case "DeleteCitation":
            deleteCitations.clear();
            break;
            
        case "PubmedArticle":
            break;
            
        case "PubDate":  case "DateRevised": case "PubMedPubDate":
            cal.clear();
            pubstatus = attrs.getValue("PubStatus");
            break;
            
        case "ArticleId":
            idtype = attrs.getValue("IdType");
            break;
            
        case "DescriptorName":
            majorTopic = attrs.getValue("MajorTopicYN");
            // fallthrough
            
        case "NameOfSubstance":
        case "QualifierName":
        case "PublicationType":
            ui = attrs.getValue("UI");
            break;
        
        case "MeshHeading":
            mh = null;
            break;

        case "Author":
        case "Investigator":
            author.clear();
            break;

        case "Grant":
            grant.clear();
            break;
            
        case "Reference":
            reference.clear();
            break;

        case "AbstractText":
            abstext.setLength(0);
            break;

        case "ArticleTitle":
            title.setLength(0);
            break;

        case "OtherAbstract":
            lang = attrs.getValue("Language");
            break;

        default:
            if (!stack.isEmpty()) {
                String parent = stack.peek();
                if ("AbstractText".equals(parent))
                    abstext.append(content.toString());
                else if ("ArticleTitle".equals(parent))
                    title.append(content.toString());
            }
        }
        stack.push(qName);
        content.setLength(0);
    }
    
    public void endElement (String uri, String localName, String qName) {
        stack.pop();
        String parent = stack.peek();
        String value = content.toString().trim();
        switch (qName) {
        case "PMID":
            try {
                Long pmid = Long.parseLong(value);
                if ("MedlineCitation".equals(parent)) {
                    doc.pmid = pmid;
                }
                else if ("DeleteCitation".equals(parent)) {
                    deleteCitations.add(pmid);
                }
            }
            catch (NumberFormatException ex) {
                Logger.error("Bogus PMID: "+content, ex);
            }
            break;
            
        case "Year":
            if ("PubDate".equals(parent)
                || "DateRevised".equals(parent)
                || "PubMedPubDate".equals(parent)) {
                cal.set(Calendar.YEAR, Integer.parseInt(value));
            }
            break;
        case "Month":
            if ("PubDate".equals(parent) || "DateRevised".equals(parent)
                || "PubMedPubDate".equals(parent))
                cal.set(Calendar.MONTH, PubMedDoc.parseMonth(value));
            break;
        case "Day":
            if ("PubDate".equals(parent) || "DateRevised".equals(parent)
                || "PubMedPubDate".equals(parent))
                cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(value));
            break;
            
        case "PubDate":
            doc.date = cal.getTime();
            break;

        case "DateRevised":
            doc.revised = cal.getTime();
            break;

        case "PubMedPubDate":
            if (doc.date.getTime() <= EPOCH
                || cal.getTime().compareTo(doc.date) < 0) {
                doc.date = cal.getTime();
            }
            break;
            
        case "Title":
            if ("Journal".equals(parent))
                doc.journal = value;
            break;
            
        case "AbstractText":
            abstext.append(value);
            doc.abs.add(abstext.toString());
            break;
            
        case "ArticleTitle":
            title.append(value);
            doc.title = title.toString();
            break;

        case "LastName":
        case "ForeName":
        case "Initials":
        case "CollectiveName":
            author.put(qName, value);
            break;

        case "Identifier":
            if ("Author".equals(parent)) {
                //Logger.debug("########### "+doc.pmid+": "+value);
                author.put(qName, value);
            }
            break;

        case "Affiliation":
            { Object affia = author.get(qName);
                if (affia != null) {
                    String[] vals = (String[])affia;
                    String[] newvals = new String[vals.length+1];
                    for (int i = 0; i < vals.length; ++i)
                        newvals[i] = vals[i];
                    newvals[vals.length] = value;
                    author.put(qName, newvals);
                }
                else 
                    author.put(qName, new String[]{value});
            }
            break;
            
        case "Author":
            doc.addAuthor(author);
            break;

        case "Investigator":
            doc.addInvestigator(author);
            break;

        case "GrantID":
        case "Acronym":
        case "Agency":
        case "Country":
            grant.put(qName, value);
            break;

        case "Grant":
            doc.addGrant(grant);
            break;
            
        case "PublicationType":
            if (ui != null && mesh != null) {
                Entry ptype = mesh.getEntry(ui);
                if (ptype != null)
                    doc.pubtypes.add(ptype);
            }
            break;

        case "Keyword":
            if (!"".equals(value))
                doc.keywords.add(value);
            break;
            
        case "ArticleId":
            if (stack.contains("Reference")) {
                if ("pubmed".equals(idtype)) {
                    long id = Long.parseLong(value);
                    Object old = reference.get("pubmed");
                    if (old != null) {
                        Long[] pmids = (Long[])old;
                        Long[] newpmids = new Long[pmids.length+1];
                        for (int i = 0; i < pmids.length; ++i)
                            newpmids[i] = pmids[i];
                        newpmids[pmids.length] = id;
                        reference.put("pubmed", newpmids);
                    }
                    else
                        reference.put("pubmed", new Long[]{id});
                }
            }
            else if ("doi".equals(idtype))
                doc.doi = value;
            else if ("pmc".equals(idtype))
                doc.pmc = value;
            break;
            
        case "NameOfSubstance":
            if (ui != null && mesh != null) {
                Entry chem = mesh.getEntry(ui);
                if (chem != null)
                    doc.chemicals.add(chem);
            }
            break;
            
        case "DescriptorName":
            if (mesh != null && "MeshHeading".equals(parent)) {
                Entry desc = mesh.getEntry(ui);
                if (desc != null) {
                    mh = new MeshHeading
                        (desc, "Y".equals(majorTopic));
                    doc.headings.add(mh);
                }
            }
            break;
            
        case "QualifierName":
            if (mh != null && mesh != null) {
                Entry qual = mesh.getEntry(ui);
                if (qual != null)
                    mh.qualifiers.add(qual);
            }
            break;
            
        case "PubmedArticle":
            if (consumer != null) {
                if (!consumer.test(this, doc))
                    cis.setDone(true);
            }
            break;

        case "Citation":
            reference.put(qName, value);
            break;

        case "Reference":
            doc.addReference(reference);
            break;
            
        case "OtherAbstract":
            doc.lang = lang;
            lang = null;
            break;
            
        default:
        }
    }
    
    public void characters (char[] ch, int start, int length) {
        content.append(ch, start, length);
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("Usage: blackboard.pubmed.PubMedSax FILES...");
            System.exit(1);
        }

        Calendar cal = Calendar.getInstance();
        PubMedSax pms = new PubMedSax ((s, d) -> {
                cal.setTime(d.date);
                Logger.debug(d.getPMID()+": ["+cal.get(Calendar.YEAR)+"] "
                             +d.getTitle()+"\n"+d.abs);
                if (d.revised == null) {
                    Logger.warn(d.getPMID()+" has no revised date!");
                    return false;
                }
                //Logger.debug("-------\n\""+new String (d.xml)+"\"");
                return true;
            });
        for (String a : argv) {
            pms.parse(new java.util.zip.GZIPInputStream
                      (new java.io.FileInputStream (a)));
            List<Long> delcit = pms.getDeleteCitations();
            Logger.debug("**** "+delcit.size()+" delete citations!\n"+delcit);
        }
    }
} // PubMedSax
