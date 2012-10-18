// Segment.java
// (C) 2005-2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2005 on http://yacy.net; full redesign for segments 28.5.2009
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.search.index;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import org.apache.solr.common.SolrInputDocument;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.federate.solr.YaCySchema;
import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.federate.yacy.CacheStrategy;
import net.yacy.cora.order.Base64Order;
import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.LookAheadIterator;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.crawler.data.CrawlQueues;
import net.yacy.crawler.retrieval.Response;
import net.yacy.document.Condenser;
import net.yacy.document.Document;
import net.yacy.document.Parser;
import net.yacy.kelondro.data.citation.CitationReference;
import net.yacy.kelondro.data.citation.CitationReferenceFactory;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.meta.URIMetadataNode;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceFactory;
import net.yacy.kelondro.data.word.WordReferenceRow;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.rwi.IndexCell;
import net.yacy.kelondro.rwi.ReferenceContainer;
import net.yacy.kelondro.rwi.ReferenceFactory;
import net.yacy.kelondro.util.Bitfield;
import net.yacy.kelondro.util.ISO639;
import net.yacy.repository.LoaderDispatcher;
import net.yacy.search.Switchboard;
import net.yacy.search.query.RWIProcess;
import net.yacy.search.query.SearchEvent;

public class Segment {

    // catchall word
    public final static String catchallString = "yacyall"; // a word that is always in all indexes; can be used for zero-word searches to find ALL documents
    public final static byte[] catchallHash;
    final static Word   catchallWord = new Word(0, 0, 0);
    static {
        catchallHash = Word.word2hash(catchallString); // "KZzU-Vf6h5k-"
        catchallWord.flags = new Bitfield(4);
        for (int i = 0; i < catchallWord.flags.length(); i++) catchallWord.flags.set(i, true);
    }

    // environment constants
    public static final long wCacheMaxAge    = 1000 * 60 * 30; // milliseconds; 30 minutes
    public static final int  wCacheMaxChunk  =  800;           // maximum number of references for each urlhash
    public static final int  lowcachedivisor =  900;
    public static final long targetFileSize  = 64 * 1024 * 1024; // 256 MB
    public static final int  writeBufferSize = 4 * 1024 * 1024;
    public static final String UrlDbName = "text.urlmd";
    public static final String termIndexName = "text.index";
    public static final String citationIndexName = "citation.index";

    // the reference factory
    public static final ReferenceFactory<WordReference> wordReferenceFactory = new WordReferenceFactory();
    public static final ReferenceFactory<CitationReference> citationReferenceFactory = new CitationReferenceFactory();
    public static final ByteOrder wordOrder = Base64Order.enhancedCoder;

    private   final Log                            log;
    private   final File                           segmentPath;
    protected final Fulltext                       fulltext;
    protected       IndexCell<WordReference>       termIndex;
    protected       IndexCell<CitationReference>   urlCitationIndex;

    public Segment(final Log log, final File segmentPath, final SolrConfiguration solrScheme) {
        log.logInfo("Initializing Segment '" + segmentPath + ".");
        this.log = log;
        this.segmentPath = segmentPath;

        // create LURL-db
        this.fulltext = new Fulltext(segmentPath, solrScheme);
    }

    public boolean connectedRWI() {
        return this.termIndex != null;
    }

    public void connectRWI(final int entityCacheMaxSize, final long maxFileSize) throws IOException {
        if (this.termIndex != null) return;
        this.termIndex = new IndexCell<WordReference>(
                        this.segmentPath,
                        termIndexName,
                        wordReferenceFactory,
                        wordOrder,
                        Word.commonHashLength,
                        entityCacheMaxSize,
                        targetFileSize,
                        maxFileSize,
                        writeBufferSize);
    }

    public void disconnectRWI() {
        if (this.termIndex == null) return;
        this.termIndex.close();
        this.termIndex = null;
    }

    public boolean connectedCitation() {
        return this.urlCitationIndex != null;
    }

    public void connectCitation(final int entityCacheMaxSize, final long maxFileSize) throws IOException {
        if (this.urlCitationIndex != null) return;
        this.urlCitationIndex = new IndexCell<CitationReference>(
                        this.segmentPath,
                        citationIndexName,
                        citationReferenceFactory,
                        wordOrder,
                        Word.commonHashLength,
                        entityCacheMaxSize,
                        targetFileSize,
                        maxFileSize,
                        writeBufferSize);
    }

    public void disconnectCitation() {
        if (this.urlCitationIndex == null) return;
        this.urlCitationIndex.close();
        this.urlCitationIndex = null;
    }

    public void connectUrlDb(final boolean useTailCache, final boolean exceed134217727) {
        this.fulltext.connectUrlDb(UrlDbName, useTailCache, exceed134217727);
    }

    public Fulltext fulltext() {
        return this.fulltext;
    }

    public IndexCell<WordReference> termIndex() {
        return this.termIndex;
    }

    public IndexCell<CitationReference> urlCitation() {
        return this.urlCitationIndex;
    }

    public long URLCount() {
        return this.fulltext.size();
    }

    public long RWICount() {
        if (this.termIndex == null) return 0;
        return this.termIndex.sizesMax();
    }

    public int RWIBufferCount() {
        if (this.termIndex == null) return 0;
        return this.termIndex.getBufferSize();
    }

    public int getQueryCount(StringBuilder wordsb) {
        return getQueryCount(wordsb.toString());
    }

    public int getQueryCount(String word) {
        if (word == null || word.indexOf(':') >= 0 || word.indexOf(' ') >= 0 || word.indexOf('/') >= 0) return 0;
        int count = this.termIndex == null ? 0 : this.termIndex.count(Word.word2hash(word));
        try {count += this.fulltext.getSolr().getQueryCount(YaCySchema.text_t.name() + ':' + word);} catch (IOException e) {}
        return count;
    }

    public boolean exists(final byte[] urlhash) {
        return this.fulltext.exists(urlhash);
    }

    /**
     * discover all urls that start with a given url stub
     * @param stub
     * @return an iterator for all matching urls
     */
    public Iterator<DigestURI> urlSelector(MultiProtocolURI stub) {
        final String host = stub.getHost();
        String hh = DigestURI.hosthash(host);
        final BlockingQueue<String> hostQueue = this.fulltext.getSolr().concurrentIDs(YaCySchema.host_id_s + ":" + hh, 0, Integer.MAX_VALUE, 10000);

        final String urlstub = stub.toNormalform(true);

        // now filter the stub from the iterated urls
        return new LookAheadIterator<DigestURI>() {
            @Override
            protected DigestURI next0() {
                while (true) {
                    String id;
                    try {
                        id = hostQueue.take();
                    } catch (InterruptedException e) {
                        Log.logException(e);
                        return null;
                    }
                    if (id == null || id == AbstractSolrConnector.POISON_ID) return null;
                    DigestURI u = Segment.this.fulltext.getMetadata(ASCII.getBytes(id)).url();
                    if (u.toNormalform(true).startsWith(urlstub)) return u;
                }
            }
        };
    }

    public void clear() {
        try {
            if (this.termIndex != null) this.termIndex.clear();
            if (this.fulltext != null) this.fulltext.clear();
            if (this.urlCitationIndex != null) this.urlCitationIndex.clear();
        } catch (final IOException e) {
            Log.logException(e);
        }
        if (Switchboard.getSwitchboard() != null &&
            Switchboard.getSwitchboard().peers != null &&
            Switchboard.getSwitchboard().peers.mySeed() != null) Switchboard.getSwitchboard().peers.mySeed().resetCounters();
    }

    public File getLocation() {
        return this.segmentPath;
    }

    private int addCitationIndex(final DigestURI url, final Date urlModified, final Map<MultiProtocolURI, Properties> anchors) {
    	if (anchors == null) return 0;
    	int refCount = 0;

        // iterate over all outgoing links, this will create a context for those links
        final byte[] urlhash = url.hash();
        final long urldate = urlModified.getTime();
        for (Map.Entry<MultiProtocolURI, Properties> anchorEntry: anchors.entrySet()) {
        	MultiProtocolURI anchor = anchorEntry.getKey();
        	byte[] refhash = DigestURI.toDigestURI(anchor).hash();
        	//System.out.println("*** addCitationIndex: urlhash = " + ASCII.String(urlhash) + ", refhash = " + ASCII.String(refhash) + ", urldate = " + urlModified.toString());
        	if (this.urlCitationIndex != null) try {
                this.urlCitationIndex.add(refhash, new CitationReference(urlhash, urldate));
            } catch (final Exception e) {
                Log.logException(e);
            }
            refCount++;
        }
        return refCount;
    }

    public synchronized void close() {
    	if (this.termIndex != null) this.termIndex.close();
        if (this.fulltext != null) this.fulltext.close();
        if (this.urlCitationIndex != null) this.urlCitationIndex.close();
    }

    private static String votedLanguage(
                    final DigestURI url,
                    final String urlNormalform,
                    final Document document,
                    final Condenser condenser) {
     // do a identification of the language
        String language = condenser.language(); // this is a statistical analysation of the content: will be compared with other attributes
        final String bymetadata = document.dc_language(); // the languageByMetadata may return null if there was no declaration
        if (language == null) {
            // no statistics available, we take either the metadata (if given) or the TLD
            language = (bymetadata == null) ? url.language() : bymetadata;
        } else {
            if (bymetadata == null) {
                // two possible results: compare and report conflicts
                if (!language.equals(url.language())) {
                    // see if we have a hint in the url that the statistic was right
                    final String u = urlNormalform.toLowerCase();
                    if (!u.contains("/" + language + "/") && !u.contains("/" + ISO639.country(language).toLowerCase() + "/")) {
                        // no confirmation using the url, use the TLD
                        language = url.language();
                    } else {
                        // this is a strong hint that the statistics was in fact correct
                    }
                }
            } else {
                // here we have three results: we can do a voting
                if (language.equals(bymetadata)) {
                    //if (log.isFine()) log.logFine("LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFIRMED - METADATA IDENTICAL: " + language);
                } else if (language.equals(url.language())) {
                    //if (log.isFine()) log.logFine("LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFIRMED - TLD IS IDENTICAL: " + language);
                } else if (bymetadata.equals(url.language())) {
                    //if (log.isFine()) log.logFine("LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFLICTING: " + language + " BUT METADATA AND TLD ARE IDENTICAL: " + bymetadata + ")");
                    language = bymetadata;
                } else {
                    //if (log.isFine()) log.logFine("LANGUAGE-BY-STATISTICS: " + entry.url() + " CONFLICTING: ALL DIFFERENT! statistic: " + language + ", metadata: " + bymetadata + ", TLD: + " + entry.url().language() + ". taking metadata.");
                    language = bymetadata;
                }
            }
        }
        return language;
    }

    public void storeRWI(final ReferenceContainer<WordReference> wordContainer) throws IOException, SpaceExceededException {
        if (this.termIndex != null) this.termIndex.add(wordContainer);
    }

    public void storeRWI(final byte[] termHash, final WordReference entry) throws IOException, SpaceExceededException {
        if (this.termIndex != null) this.termIndex.add(termHash, entry);
    }

    public SolrInputDocument storeDocument(
            final DigestURI url,
            final DigestURI referrerURL,
            final CrawlProfile profile,
            final ResponseHeader responseHeader,
            final Document document,
            final Condenser condenser,
            final SearchEvent searchEvent,
            final String sourceName,
            final boolean storeToRWI
            ) {
        final long startTime = System.currentTimeMillis();

        // CREATE INDEX

        // load some document metadata
        final Date loadDate = new Date();
        final String id = ASCII.String(url.hash());
        final String dc_title = document.dc_title();
        final String urlNormalform = url.toNormalform(true);
        final String language = votedLanguage(url, urlNormalform, document, condenser); // identification of the language

        // STORE URL TO LOADED-URL-DB
        Date modDate = responseHeader.lastModified();
        if (modDate.getTime() > loadDate.getTime()) modDate = loadDate;
        char docType = Response.docType(document.dc_format());
        
        // STORE TO SOLR
        final SolrInputDocument solrInputDoc = this.fulltext.getSolrScheme().yacy2solr(id, profile, responseHeader, document, condenser, referrerURL, language);
        try {
            this.fulltext.putDocument(solrInputDoc);
        } catch ( final IOException e ) {
            Log.logWarning("SOLR", "failed to send " + urlNormalform + " to solr: " + e.getMessage());
        }

        final long storageEndTime = System.currentTimeMillis();

        // STORE PAGE INDEX INTO WORD INDEX DB
        int outlinksSame = document.inboundLinks().size();
        int outlinksOther = document.outboundLinks().size();
        final RWIProcess rankingProcess = (searchEvent == null) ? null : searchEvent.getRankingResult();
        int wordCount = 0;
        final int urlLength = urlNormalform.length();
        final int urlComps = MultiProtocolURI.urlComps(url.toString()).length;

        // create a word prototype which is re-used for all entries
        if ((this.termIndex != null && storeToRWI) || searchEvent != null) {
            final int len = (document == null) ? urlLength : document.dc_title().length();
            final WordReferenceRow ientry = new WordReferenceRow(
                            url.hash(),
                            urlLength, urlComps, len,
                            condenser.RESULT_NUMB_WORDS,
                            condenser.RESULT_NUMB_SENTENCES,
                            modDate.getTime(),
                            System.currentTimeMillis(),
                            UTF8.getBytes(language),
                            docType,
                            outlinksSame, outlinksOther);
    
            // iterate over all words of content text
            Word wprop = null;
            byte[] wordhash;
            String word;
            for (Map.Entry<String, Word> wentry: condenser.words().entrySet()) {
                word = wentry.getKey();
                wprop = wentry.getValue();
                assert (wprop.flags != null);
                ientry.setWord(wprop);
                wordhash = Word.word2hash(word);
                if (this.termIndex != null && storeToRWI) try {
                    this.termIndex.add(wordhash, ientry);
                } catch (final Exception e) {
                    Log.logException(e);
                }
                wordCount++;
    
                // during a search event it is possible that a heuristic is used which aquires index
                // data during search-time. To transfer indexed data directly to the search process
                // the following lines push the index data additionally to the search process
                // this is done only for searched words
                if (searchEvent != null && !searchEvent.getQuery().query_exclude_hashes.has(wordhash) && searchEvent.getQuery().query_include_hashes.has(wordhash)) {
                    // if the page was added in the context of a heuristic this shall ensure that findings will fire directly into the search result
                    ReferenceContainer<WordReference> container;
                    try {
                        container = ReferenceContainer.emptyContainer(Segment.wordReferenceFactory, wordhash, 1);
                        container.add(ientry);
                        rankingProcess.add(container, true, sourceName, -1, 5000);
                    } catch (final SpaceExceededException e) {
                        continue;
                    }
                }
            }
            if (rankingProcess != null) rankingProcess.addFinalize();
    
            // assign the catchall word
            ientry.setWord(wprop == null ? catchallWord : wprop); // we use one of the word properties as template to get the document characteristics
            if (this.termIndex != null) try {
                this.termIndex.add(catchallHash, ientry);
            } catch (final Exception e) {
                Log.logException(e);
            }
        }

        // STORE PAGE REFERENCES INTO CITATION INDEX
        final int refs = addCitationIndex(url, modDate, document.getAnchors());

        // finish index time
        final long indexingEndTime = System.currentTimeMillis();

        if (this.log.isInfo()) {
            this.log.logInfo("*Indexed " + wordCount + " words in URL " + url +
                    " [" + id + "]" +
                    "\n\tDescription:  " + dc_title +
                    "\n\tMimeType: "  + document.dc_format() + " | Charset: " + document.getCharset() + " | " +
                    "Size: " + document.getTextLength() + " bytes | " +
                    "Anchors: " + refs +
                    "\n\tLinkStorageTime: " + (storageEndTime - startTime) + " ms | " +
                    "indexStorageTime: " + (indexingEndTime - storageEndTime) + " ms");
        }

        // finished
        return solrInputDoc;
    }

    public void removeAllUrlReferences(final HandleSet urls, final LoaderDispatcher loader, final CacheStrategy cacheStrategy) {
        for (final byte[] urlhash: urls) removeAllUrlReferences(urlhash, loader, cacheStrategy);
    }

    /**
     * find all the words in a specific resource and remove the url reference from every word index
     * finally, delete the url entry
     * @param urlhash the hash of the url that shall be removed
     * @param loader
     * @param cacheStrategy
     * @return number of removed words
     */
    public int removeAllUrlReferences(final byte[] urlhash, final LoaderDispatcher loader, final CacheStrategy cacheStrategy) {

        if (urlhash == null) return 0;
        // determine the url string
        final URIMetadataNode entry = fulltext().getMetadata(urlhash);
        if (entry == null) return 0;
        if (entry.url() == null) return 0;

        try {
            // parse the resource
            final Document document = Document.mergeDocuments(entry.url(), null, loader.loadDocuments(loader.request(entry.url(), true, false), cacheStrategy, Integer.MAX_VALUE, null, CrawlQueues.queuedMinLoadDelay));
            if (document == null) {
                // delete just the url entry
                fulltext().remove(urlhash);
                return 0;
            }
            // get the word set
            Set<String> words = null;
            words = new Condenser(document, true, true, null, null, false).words().keySet();

            // delete all word references
            int count = 0;
            if (words != null) count = termIndex().remove(Word.words2hashesHandles(words), urlhash);

            // finally delete the url entry itself
            fulltext().remove(urlhash);
            return count;
        } catch (final Parser.Failure e) {
            return 0;
        } catch (final IOException e) {
            Log.logException(e);
            return 0;
        }
    }

}
