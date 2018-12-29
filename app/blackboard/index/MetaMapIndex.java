package blackboard.index;

import play.Logger;
import play.libs.Json;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import gov.nih.nlm.nls.metamap.AcronymsAbbrevs;
import gov.nih.nlm.nls.metamap.ConceptPair;
import gov.nih.nlm.nls.metamap.Ev;
import gov.nih.nlm.nls.metamap.MatchMap;
import gov.nih.nlm.nls.metamap.Mapping;
import gov.nih.nlm.nls.metamap.MetaMapApi;
import gov.nih.nlm.nls.metamap.MetaMapApiImpl;
import gov.nih.nlm.nls.metamap.Negation;
import gov.nih.nlm.nls.metamap.PCM;
import gov.nih.nlm.nls.metamap.Phrase;
import gov.nih.nlm.nls.metamap.Position;
import gov.nih.nlm.nls.metamap.Result;
import gov.nih.nlm.nls.metamap.Utterance;

import org.apache.lucene.document.*;
import org.apache.lucene.facet.*;
import org.apache.lucene.util.BytesRef;
import blackboard.umls.MetaMap;

public class MetaMapIndex extends Index {
    protected ObjectMapper mapper = new ObjectMapper ();    
    protected MetaMap metamap;

    protected MetaMapIndex (File dir) throws IOException {
        super (dir);
    }

    public void setMetaMap (MetaMap metamap) {
        this.metamap = metamap;
    }
    public MetaMap getMetaMap () { return metamap; }

    protected JsonNode metamap (Document doc, String text) {
        JsonNode json = null;
        if (metamap != null) {
            try {
                ArrayNode nodes = mapper.createArrayNode();
                for (Result r : metamap.annotate(text)) {
                    for (AcronymsAbbrevs abrv : r.getAcronymsAbbrevsList()) {
                        for (String cui : abrv.getCUIList()) {
                            doc.add(new StringField
                                    (FIELD_CUI, cui, Field.Store.NO));
                        }
                    }
                    
                    for (Utterance utter : r.getUtteranceList()) {
                        for (PCM pcm : utter.getPCMList()) {
                            for (Mapping map : pcm.getMappingList())
                                for (Ev ev : map.getEvList()) {
                                    doc.add(new StringField
                                            (FIELD_CUI, ev.getConceptId(),
                                             Field.Store.NO));
                                    doc.add(new FacetField
                                            (FIELD_CONCEPT, ev.getConceptId()));
                                    for (String t : ev.getSemanticTypes())
                                        doc.add(new FacetField
                                                (FIELD_SEMTYPE, t));
                                    for (String s : ev.getSources())
                                        doc.add(new FacetField
                                                (FIELD_SOURCE, s));
                                }
                        }
                    }
                    json = MetaMap.toJson(r);
                    //Logger.debug(">>> "+json);
                    nodes.add(json);
                }
                
                json = nodes;
            }
            catch (Exception ex) {
                Logger.error("Can't annotate doc "
                             +doc.get(FIELD_PMID)+" with MetaMap", ex);
            }
        }
        return json;
    }

    protected byte[] toCompressedBytes (JsonNode json) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream (1000);
             GZIPOutputStream gzip = new GZIPOutputStream (bos);) {
            byte[] data = mapper.writeValueAsBytes(json);
            gzip.write(data, 0, data.length);
            gzip.close();
            return bos.toByteArray();
        }
    }

    protected JsonNode[] toJson (Document doc, String field)
        throws IOException {
        BytesRef[] brefs = doc.getBinaryValues(field);
        List<JsonNode> json = new ArrayList<>();
        for (BytesRef ref : brefs) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream
                 (ref.bytes, ref.offset, ref.length);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream (1000);
                 GZIPInputStream gzip = new GZIPInputStream (bis)) {
                byte[] buf = new byte[1024];
                for (int nb; (nb = gzip.read(buf, 0, buf.length)) != -1; ) {
                    bos.write(buf, 0, nb);
                }
                JsonNode n = mapper.readTree(bos.toByteArray());
                json.add(n);
            }
        }
        return json.toArray(new JsonNode[0]);
    }
}