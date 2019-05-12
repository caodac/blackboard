package blackboard.pubmed;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.Calendar;
import static java.util.Calendar.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import blackboard.mesh.MeshDb;
import blackboard.mesh.Descriptor;
import blackboard.mesh.Qualifier;
import blackboard.mesh.Entry;

public class PubMedDoc implements java.io.Serializable {
    static private final Long serialVerionUID = 0x010101l;
    public static final PubMedDoc EMPTY = new PubMedDoc ();

    public static class Author {
        public final String lastname;
        public final String forename;
        public final String initials;
        public final String collectiveName;
        public final String[] affiliations;

        Author (Element elm) {
            NodeList nodes = elm.getElementsByTagName("LastName");
            if (nodes.getLength() == 0) {
                nodes = elm.getElementsByTagName("CollectiveName");
                if (nodes.getLength() == 0)
                    throw new IllegalArgumentException
                        ("Author has no valid name!");
                else
                    collectiveName = getText (nodes.item(0));
            }
            else
                collectiveName = null;
            lastname = getText (nodes.item(0));
            nodes = elm.getElementsByTagName("Forename");
            forename = nodes == null || nodes.getLength() == 0
                ? null : getText (nodes.item(0));
            nodes = elm.getElementsByTagName("Initials");
            initials = nodes == null || nodes.getLength() == 0
                ? null : getText (nodes.item(0));
            
            nodes = elm.getElementsByTagName("Affiliation");
            affiliations = new String[nodes.getLength()];
            for (int i = 0; i < nodes.getLength(); ++i) {
                affiliations[i] = getText (nodes.item(i));
            }
        }

        Author (Map<String, Object> auth) {
            lastname = (String) auth.get("LastName");
            collectiveName = (String) auth.get("CollectiveName");
            if (lastname == null && collectiveName == null)
                throw new IllegalArgumentException
                    ("Author element doesn't have LastName!");
            forename = (String) auth.get("ForeName");
            initials = (String) auth.get("Initials");
            affiliations = (String[]) auth.get("Affiliation");
        }

        public String getName () {
            if (collectiveName != null)
                return collectiveName;
            if (forename == null && initials == null)
                return lastname;
            if (forename == null && initials != null)
                return lastname+", "+initials;
            return lastname+", "+forename;
        }
    }

    public static class Reference {
        public final String citation;
        public final Long[] pmids;

        Reference (Element elm) {
            NodeList nodes = elm.getElementsByTagName("Citation");
            if (nodes == null || nodes.getLength() == 0)
                throw new IllegalArgumentException
                    ("Reference elmenent has not citation!");
            citation = getText (nodes.item(0));
            nodes = elm.getElementsByTagName("ArticleId");
            List<Long> refs = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); ++i) {
                Element e = (Element)nodes.item(i);
                if ("pubmed".equals(e.getAttribute("IdType"))) {
                    try {
                        refs.add(Long.parseLong(getText(e)));
                    }
                    catch (NumberFormatException ex) {
                    }
                }
            }
            pmids = refs.toArray(new Long[0]);
        }
        Reference (Map<String, Object> ref) {
            citation = (String)ref.get("Citation");
            pmids = (Long[])ref.get("pubmed");
        }
    }
        
    public Long pmid;
    public String doi;
    public String pmc;
    public String title;
    public List<String> abs = new ArrayList<>();
    public List<Author> authors = new ArrayList<>();
    public List<String> keywords = new ArrayList<>();
    public String journal;
    public Date date;
    public List<Entry> pubtypes = new ArrayList<>();
    public List<MeshHeading> headings = new ArrayList<>();
    public List<Entry> chemicals = new ArrayList<>();
    public List<Reference> references = new ArrayList<>();

    static String getText (Node node) {
        if (node instanceof Element)
            return ((Element)node).getTextContent();
        return null;
    }
    
    protected PubMedDoc () {
    }
    
    protected PubMedDoc (Document doc, MeshDb mesh) {
        NodeList nodes = doc.getElementsByTagName("PMID");
        pmid = nodes.getLength() > 0
            ? Long.parseLong(((Element)nodes.item(0)).getTextContent()) : null;
        nodes = doc.getElementsByTagName("ArticleTitle");
        title = nodes.getLength() > 0
            ? ((Element)nodes.item(0)).getTextContent() : null;
        nodes = doc.getElementsByTagName("Abstract");
        if (nodes.getLength() > 0) {
            Element elm = (Element)nodes.item(0);
            nodes = elm.getElementsByTagName("AbstractText");
            if (nodes.getLength() > 0) {
                for (int i = 0; i < nodes.getLength(); ++i) {
                    elm = (Element)nodes.item(i);
                    String cat = elm.getAttribute("Label");
                    if (cat != null && !"".equals(cat))
                        cat = cat + ": ";
                    else
                        cat = "";
                    abs.add(cat+elm.getTextContent());
                }
            }
            else {
                abs.add(elm.getTextContent());
            }
        }

        /*
         * journal 
         */
        nodes = doc.getElementsByTagName("Journal");
        if (nodes.getLength() > 0) {
            Element elm = (Element)nodes.item(0);
            nodes = elm.getElementsByTagName("Title");
            journal = nodes.getLength() > 0
                ? ((Element)nodes.item(0)).getTextContent() : null;
            nodes = elm.getElementsByTagName("PubDate");
            if (nodes.getLength() > 0) {
                elm = (Element)nodes.item(0);
                
                Calendar cal = Calendar.getInstance();
                nodes = elm.getElementsByTagName("Year");
                if (nodes.getLength() > 0) {
                    cal.set(YEAR, Integer.parseInt
                            (((Element)nodes.item(0)).getTextContent()));
                }
                
                nodes = elm.getElementsByTagName("Month");
                int month = 0;
                if (nodes.getLength() > 0) {
                    String mon = ((Element)nodes.item(0)).getTextContent();
                    month = parseMonth (mon);
                }
                cal.set(MONTH, month);
                
                nodes = elm.getElementsByTagName("Day");
                if (nodes.getLength() > 0) {
                    cal.set(DAY_OF_MONTH, Integer.parseInt
                            (((Element)nodes.item(0)).getTextContent()));
                }
                else {
                    cal.set(DAY_OF_MONTH, 1);
                }
                date = cal.getTime();
            }
        }

        /*
         * author
         */
        nodes = doc.getElementsByTagName("Author");
        for (int i = 0; i < nodes.getLength(); ++i) {
            try {
                Author auth = new Author ((Element)nodes.item(i));
                authors.add(auth);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        /*
         * publication type
         */
        nodes = doc.getElementsByTagName("PublicationType");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element elm = (Element)nodes.item(i);
            String ui = elm.getAttribute("UI");
            Entry desc = mesh.getEntry(ui);
            if (desc != null)
                pubtypes.add(desc);
        }

        /*
         * references
         */
        nodes = doc.getElementsByTagName("Reference");
        for (int i = 0; i < nodes.getLength(); ++i) {
            try {
                Reference ref = new Reference ((Element)nodes.item(i));
                references.add(ref);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        /*
         * other identifiers
         */
        nodes = doc.getElementsByTagName("ArticleId");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element elm = (Element)nodes.item(i);
            String type = elm.getAttribute("IdType");
            if ("doi".equals(type)) {
                doi = elm.getTextContent();
            }
            else if ("pmc".equals(type)) {
                pmc = elm.getTextContent();
            }
        }

        nodes = doc.getElementsByTagName("MeshHeading");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element elm = (Element)nodes.item(i);
            NodeList nl = elm.getElementsByTagName("DescriptorName");
            MeshHeading mh = null;
            if (nl.getLength() > 0) {
                String ui = ((Element)nl.item(0)).getAttribute("UI");
                String major =
                    ((Element)nl.item(0)).getAttribute("MajorTopicYN");
                Entry desc = mesh.getEntry(ui);
                if (desc != null) {
                    mh = new MeshHeading (desc, "Y".equals(major));
                    headings.add(mh);
                }
            }

            if (mh != null) {
                nl = elm.getElementsByTagName("QualifierName");
                for (int j = 0; j < nl.getLength(); ++j) {
                    elm = (Element)nl.item(j);
                    String ui = elm.getAttribute("UI");
                    Entry qual = mesh.getEntry(ui);
                    if (qual != null)
                        mh.qualifiers.add(qual);
                }
            }
        }

        nodes = doc.getElementsByTagName("NameOfSubstance");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element elm = (Element)nodes.item(i);
            String ui = elm.getAttribute("UI");
            Entry chem = mesh.getEntry(ui);
            if (chem != null)
                chemicals.add(chem);
        }
    }

    public Long getPMID () { return pmid; }
    public String getTitle () { return title; }
    public List<String> getAbstract () { return abs; }
    public Date getDate () { return date; }
    public String getDOI () { return doi; }
    public String getPMC () { return pmc; }
    public String getJournal () { return journal; }
    public List<MeshHeading> getMeshHeadings () { return headings; }
    public List<Entry> getChemicals () { return chemicals; }

    public void addAuthor (Map<String, Object> author) {
        authors.add(new Author (author));
    }
    public void addReference (Map<String, Object> reference) {
        references.add(new Reference (reference));
    }

    public static int parseMonth (String mon) {
        int month = 0;
        switch (mon) {
        case "jan": case "Jan":
            month = JANUARY;
            break;
        case "feb": case "Feb":
            month = FEBRUARY;
            break;
        case "mar": case "Mar":
            month = MARCH;
            break;
        case "apr": case "Apr":
            month = APRIL;
            break;
        case "may": case "May":
            month = MAY;
            break;
        case "jun": case "Jun":
            month = JUNE;
            break;
        case "jul": case "Jul":
            month = JULY;
            break;
        case "aug": case "Aug":
            month = AUGUST;
            break;
        case "sep": case "Sep":
            month = SEPTEMBER;
            break;
        case "oct": case "Oct":
            month = OCTOBER;
            break;
        case "nov": case "Nov":
            month = NOVEMBER;
            break;
        case "dec": case "Dec":
            month = DECEMBER;
            break;
        default:
            try {
                int m = Integer.parseInt(mon);
                month = m - 1; // 0-based
            }
            catch (NumberFormatException ex) {
                throw new RuntimeException ("Unknown month: "+mon);
            }
        }
        return month;
    }
}
