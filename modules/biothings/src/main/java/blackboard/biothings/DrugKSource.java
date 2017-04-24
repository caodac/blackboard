package blackboard.biothings;

import java.util.*;
import java.util.function.BiConsumer;
import java.net.URLEncoder;
import java.util.concurrent.*;

import javax.inject.Inject;
import javax.inject.Named;

import play.Logger;
import play.Configuration;
import play.libs.ws.*;
import play.libs.Json;
import play.inject.ApplicationLifecycle;
import play.libs.F;
import akka.actor.ActorSystem;

import com.fasterxml.jackson.databind.JsonNode;

import blackboard.*;
import static blackboard.KEntity.*;

public class DrugKSource implements KSource {
    private final WSClient wsclient;
    private final KSourceProvider ksp;

    @Inject
    public DrugKSource (WSClient wsclient,
                        @Named("biothings") KSourceProvider ksp,
                        ApplicationLifecycle lifecycle) {
        this.wsclient = wsclient;
        this.ksp = ksp;

        lifecycle.addStopHook(() -> {
                wsclient.close();
                return F.Promise.pure(null);
            });
        
        Logger.debug("$"+ksp.getId()+": "+ksp.getName()
                     +" initialized; provider is "+ksp.getImplClass());
    }

    public void execute (KGraph kgraph) {
        Logger.debug("$"+ksp.getId()
                     +": executing on KGraph "+kgraph.getId()
                     +" \""+kgraph.getName()+"\"");
        KNode[] drugs = kgraph.nodes(n -> "drug".equals(n.getType()));
        for (KNode kn : drugs) {
            try {
                seedDrug (kn, kgraph);
            }
            catch (Exception ex) {
                Logger.error("Unable to resolve node "
                             +kn.getId()+": "+kn.getName(), ex);
            }
        }
        Logger.debug("$"+ksp.getId()+": "
                     +drugs.length+" drug nodes processed!");
    }

    void seedDrug (KNode kn, KGraph kg) throws Exception {
        String url = ksp.getUri()+"/query?q=unii.preferred_term:"
            +URLEncoder.encode(kn.getName(), "utf8");
        
        WSRequest req = wsclient.url(url);
        // if we use req.get().thenAccept(...) then we can potentially
        // generating too many threads
        WSResponse res = req.get().toCompletableFuture().get();
        
        JsonNode json = res.asJson().get("hits");
        Logger.debug("+++ resolving "+url+"..."+json.size()+" hit(s) found!");
        for (int i = 0; i < json.size(); ++i) {
            JsonNode node = json.get(i);
            if (node.hasNonNull("unii")) {
                /*
                 * we use the unii node as the anchor for all 
                 * derived nodes from from this knowledge source
                 */
                KEdge ke = unii
                    (node.get("unii"), kn, kg, "resolve");
                            
                // if this node is already resolved, then it's the
                // anchor
                KNode anchor = ke != null ? ke.other(kn) : kn;
                for (Iterator<Map.Entry<String, JsonNode>> it
                         = node.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> me = it.next();
                    if (!anchor.hasNeighbors(me.getKey())) {
                        JsonNode n = me.getValue();
                        switch (me.getKey()) {
                        case "aeolus":
                            aeolus (n, anchor, kg);
                            break;
                        case "chebi":
                            chebi (n, anchor, kg);
                            break;
                        case "drugbank":
                            drugbank (n, anchor, kg);
                            break;
                        }
                    }
                }
            }
        }

        //Logger.debug(Json.stringify(json));
    }

    // https://www.nature.com/articles/sdata201626
    void aeolus (JsonNode json, KNode kn, KGraph kg) {
        PriorityQueue<JsonNode> pq = new PriorityQueue<>
            (json.get("no_of_outcomes").asInt(), (p, q) -> {
                int n = q.get("case_count").asInt();
                int m = p.get("case_count").asInt();
                double d = (n * q.get("ror").asDouble()
                            - m * p.get("ror").asDouble());
                if (d < 0.) return -1;
                else if (d > 0.) return 1;
                else {
                    d = (n*q.get("ror").asDouble()
                         - m*p.get("prr").asDouble());
                    if (d < 0.) return -1;
                    else if (d > 0.) return 1;
                }
                return n - m;
            });
        
        String name = json.get("drug_name").asText();   
        JsonNode outcomes = json.get("outcomes");
        for (int i = 0; i < outcomes.size(); ++i) {
            JsonNode n = outcomes.get(i);
            if (n.get("case_count").asInt() > 0) {
                try {
                    pq.add(n);
                }
                catch (Exception ex) {
                    Logger.warn("Outcome \""+n.get("name").asText()+"\" for "
                                +name+" is not complete!");
                }
            }
        }

        // only do the top-10 for now.. 
        for (int i = 0; i < 10; ++i) {
            JsonNode node = pq.poll();
            // this is just a symbolic and not actual resolvable uri
            String uri = ksp.getUri()+"/aeolus/"+node.get("name").asText();
            Map<String, Object> props = new TreeMap<>();
            props.put("uri", uri);
            props.put(NAME_P, node.get("name").asText());
            props.put(TYPE_P, "adverse-event");
            KNode xn = kg.createNodeIfAbsent(props, "uri");
            xn.addTag("aeolus");
            props.clear();
            props.put("ror", node.get("ror").asDouble());
            props.put("prr", node.get("prr").asDouble());
            props.put("case_count", node.get("case_count").asInt());
            kg.createEdgeIfAbsent(kn, xn, "assertion", props, null);
        }
    }

    void chebi (JsonNode json, KNode kn, KGraph kg) {
    }

    void drugbank (JsonNode json, KNode kn, KGraph kg) {
        kn.putIfAbsent("synonyms", () -> {
                List<String> syns = new ArrayList<>();
                if (json.hasNonNull("accession_number"))
                    syns.add(json.get("accession_number").asText());
                else if (json.hasNonNull("drugbank_id"))
                    syns.add(json.get("drugbank_id").asText());
                JsonNode sn = json.get("synonyms");
                if (sn != null) {
                    for (int i = 0; i < sn.size(); ++i)
                        syns.add(sn.get(i).asText());
                }
                return syns.toArray(new String[0]);
            });
        
        JsonNode di = json.get("drug_interactions");
        if (di != null) {
            // drug-drug interactions.. only 10 for now
            for (int i = 0; i < Math.min(10,di.size()); ++i) {
                final JsonNode ddi = di.get(i);
                String url = ksp.getUri()
                    +"/drug/"+ddi.get("drugbank-id").asText();
                
                WSRequest req = wsclient.url(url);
                req.get().thenAccept(res -> {
                        try {
                            final JsonNode jn = res.asJson();
                            if (jn.hasNonNull("unii")) {
                                KEdge ke = unii (jn.get("unii"), kn, kg, "ddi");
                                ke.putIfAbsent("description", () -> {
                                        return ddi.get("description").asText();
                                    });
                            }
                        }
                        catch (Exception ex) {
                            Logger.error("Can't retrieve json from "+url, ex);
                        }
                    });
            }
        }
    }

    KEdge unii (JsonNode json, KNode kn, KGraph kg, String type) {
        String uri = ksp.getUri()+"/drug/"+json.get("unii").asText();
        Map<String, Object> props = new TreeMap<>();
        props.put(TYPE_P, "drug");
        props.put("uri", uri);
        props.put(NAME_P, json.get("preferred_term").asText());
        KNode xn = kg.createNodeIfAbsent(props, "uri");
        KEdge ke = null;
        if (xn.getId() != kn.getId()) {
            // tag this node with this knowledge source id  
            xn.addTag("KS:"+ksp.getId()); 
            ke = kg.createEdgeIfAbsent(kn, xn, type);
            Logger.debug(xn.getId()+":"+xn.getName()
                         + " <-["+ke.getId()+":"+type+"]-> "
                         +kn.getId()+":"+kn.getName());
        }
        return ke;
    }
}
