package blackboard.pubmed.index;

import play.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import com.typesafe.config.Config;
import play.api.Configuration;
import play.inject.ApplicationLifecycle;

import akka.actor.ActorSystem;
import akka.actor.AbstractActor;
import akka.actor.AbstractActor.Receive;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.PoisonPill;
import akka.actor.Inbox;

import static blackboard.pubmed.index.PubMedIndex.*;

@Singleton
public class PubMedIndexManager implements AutoCloseable {
    public static class TextQuery {
        public final String field;
        public final String query;
        public final Map<String, Object> facets = new TreeMap<>();

        TextQuery (Map<String, Object> facets) {
            this (null, null, facets);
        }
        TextQuery (String query) {
            this (null, query, null);
        }
        TextQuery (String query, Map<String, Object> facets) {
            this (null, query, facets);
        }
        TextQuery (String field, String query, Map<String, Object> facets) {
            this.field = field;
            this.query = query;
            if (facets != null)
                this.facets.putAll(facets);
        }
        
        public String toString () {
            return "TextQuery{field="+field+",query="
                +query+",facets="+facets+"}";
        }
    }

    public static class PMIDQuery {
        public final Long pmid;
        PMIDQuery (Long pmid) {
            this.pmid = pmid;
        }
        public String toString () {
            return "PmidQuery{pmid="+pmid+"}";
        }
    }
    
    static class PubMedIndexActor extends AbstractActor {
        static Props props (PubMedIndexFactory pmif, File db) {
            return Props.create
                (PubMedIndexActor.class, () -> new PubMedIndexActor (pmif, db));
        }
        
        final PubMedIndex pmi;
        public PubMedIndexActor (PubMedIndexFactory pmif, File dir) {
            pmi = pmif.get(dir);
        }

        @Override
        public void preStart () {
            Logger.debug("### "+self ()+ "...initialized!");
        }

        @Override
        public void postStop () {
            try {
                pmi.close();
            }
            catch (Exception ex) {
                Logger.error("Can't close PubMedIndex: "+pmi.getDbFile(), ex);
            }
            Logger.debug("### "+self ()+"...stopped!");
        }

        @Override
        public Receive createReceive () {
            return receiveBuilder()
                .match(TextQuery.class, this::doTextSearch)
                .match(PMIDQuery.class, this::doPMIDSearch)
                .build();
        }

        void doTextSearch (TextQuery q) throws Exception {
            Logger.debug(self()+": searching "+q);

            SearchResult result = null;
            long start = System.currentTimeMillis();
            if (q.field != null) {
                result = pmi.search(q.field, q.query, q.facets); 
            }
            else {
                result = pmi.search(q.query, q.facets);
            }
            Logger.debug(self()+": search completed in "+String.format
                         ("%1$.3fs", 1e-3*(System.currentTimeMillis()-start)));
            
            getSender().tell(result, getSelf ());
        }

        void doPMIDSearch (PMIDQuery q) throws Exception {
            Logger.debug(self()+": fetching "+q.pmid);
            long start = System.currentTimeMillis();
            MatchedDoc doc = pmi.getMatchedDoc(q.pmid);
            Logger.debug(self()+": fetch completed in "+String.format
                         ("%1$.3fs", 1e-3*(System.currentTimeMillis()-start)));
            getSender().tell(doc, getSelf ());
        }
    }
    
    final List<ActorRef> indexes = new ArrayList<>();
    final ActorSystem actorSystem;
    final int maxTimeout, maxTries;
    
    @Inject
    public PubMedIndexManager (Configuration config, PubMedIndexFactory pmif,
                               ActorSystem actorSystem,
                               ApplicationLifecycle lifecycle) {
        Config conf = config.underlying().getConfig("app.pubmed");
        
        if (!conf.hasPath("indexes"))
            throw new IllegalArgumentException
                ("No app.pubmed.indexes property defined!");

        File dir = new File
            (conf.hasPath("base") ? conf.getString("base") : ".");
        if (!dir.exists())
            throw new IllegalArgumentException
                ("base path "+dir+" doesn't exist!");

        maxTimeout = conf.hasPath("max-timeout")
            ? conf.getInt("max-timeout") : 10;
        maxTries = conf.hasPath("max-tries") ? conf.getInt("max-tries") : 5;
        
        List<String> indexes = conf.getStringList("indexes");
        for (String idx : indexes) {
            File db = new File (dir, idx);
            try {
                ActorRef actorRef = actorSystem.actorOf
                    (PubMedIndexActor.props(pmif, db), idx);
                this.indexes.add(actorRef);
            }
            catch (Exception ex) {
                Logger.error("Can't load database: "+db, ex);
            }
        }

        lifecycle.addStopHook(() -> {
                close ();
                return CompletableFuture.completedFuture(null);
            });

        this.actorSystem = actorSystem;
        Logger.debug("$$$$ "+getClass().getName()
                     +": base="+dir+" indexes="+indexes
                     +" max-timeout="+maxTimeout);
    }

    public void close () throws Exception {
        for (ActorRef actorRef : indexes) {
            actorRef.tell(PoisonPill.getInstance(), ActorRef.noSender());
        }
        Logger.debug("$$ shutting down "+getClass().getName());
    }

    public SearchResult search (String query, Map<String, Object> facets) {
        TextQuery tq = new TextQuery (query, facets);
        Inbox inbox = Inbox.create(actorSystem);
        for (ActorRef actorRef : indexes)
            inbox.send(actorRef, tq);

        List<SearchResult> results = new ArrayList<>();        
        for (int i = 0, ntries = 0; i < indexes.size()
                 && ntries < maxTries;) {
            try {
                SearchResult result = (SearchResult)inbox.receive
                    (Duration.ofSeconds(maxTimeout));
                if (!result.isEmpty())
                    results.add(result);
                ++i;
            }
            catch (TimeoutException ex) {
                ++ntries;
                Logger.warn("Unable to receive result from Inbox"
                            +" within alloted time; retrying "+ntries);
            }
        }
        
        return PubMedIndex.merge(results.toArray(new SearchResult[0]));
    }

    public MatchedDoc getDoc (Long pmid, String format) {
        PMIDQuery q = new PMIDQuery (pmid);
        Inbox inbox = Inbox.create(actorSystem);
        List<MatchedDoc> docs = new ArrayList<>();
        for (ActorRef actorRef : indexes) {
            inbox.send(actorRef, q);
            try {
                MatchedDoc doc = (MatchedDoc)inbox.receive
                    (Duration.ofSeconds(2l));
                if (doc != EMPTY_DOC)
                    docs.add(doc);
            }
            catch (TimeoutException ex) {
                Logger.error("Unable to receive result from "+actorRef
                             +" within alloted time", ex);
            }
        }

        if (docs.size() > 1)
            Logger.warn(pmid+" has multiple ("+docs.size()+") documents!");
        return docs.isEmpty() ? null : docs.get(0);
    }
}
