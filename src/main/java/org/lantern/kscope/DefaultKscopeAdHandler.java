package org.lantern.kscope;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import org.lantern.XmppHandler;
import org.kaleidoscope.RandomRoutingTable;
import org.kaleidoscope.TrustGraphNodeId;
import org.kaleidoscope.TrustGraphNode;
import org.kaleidoscope.BasicTrustGraphNodeId;
import org.kaleidoscope.BasicTrustGraphAdvertisement;
import org.lantern.LanternTrustStore;
import org.lantern.ProxyTracker;
import org.lantern.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DefaultKscopeAdHandler implements KscopeAdHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final XmppHandler xmppHandler;
    
    /**
     * Map of kscope advertisements for which we are awaiting corresponding
     * certificates.
     */
    private final ConcurrentHashMap<String, LanternKscopeAdvertisement> awaitingCerts = 
        new ConcurrentHashMap<String, LanternKscopeAdvertisement>();
    private final ProxyTracker proxyTracker;
    private final LanternTrustStore trustStore;
    private final RandomRoutingTable routingTable;
    
    @Inject
    public DefaultKscopeAdHandler(final ProxyTracker proxyTracker,
        final LanternTrustStore trustStore,
        final RandomRoutingTable routingTable,
        final XmppHandler xmppHandler) {
        this.proxyTracker = proxyTracker;
        this.trustStore = trustStore;
        this.routingTable = routingTable;
        this.xmppHandler = xmppHandler;
    }
    
    @Override
    public void handleAd(final String from, 
            final LanternKscopeAdvertisement ad) {
        log.debug("*** got kscope ad from {} for {}", from, ad.getJid());
        awaitingCerts.put(ad.getJid(), ad);

        // do we want to relay this?
        int inboundTtl = ad.getTtl();
        if(inboundTtl <= 0) {
            log.debug("End of the line for kscope ad for {} from {}.", 
                ad.getJid(), from
            );
            return;
        }
        TrustGraphNodeId nid = new BasicTrustGraphNodeId(ad.getJid());
        TrustGraphNodeId nextNid = routingTable.getNextHop(nid);
        if(nextNid == null) {
            log.error("Could not relay ad: Node ID not in routing table");
            return;
        }
        LanternKscopeAdvertisement relayAd = 
            LanternKscopeAdvertisement.makeRelayAd(ad);

        final String relayAdPayload = JsonUtils.jsonify(relayAd);
        final BasicTrustGraphAdvertisement message =
            new BasicTrustGraphAdvertisement(nextNid, relayAdPayload, 
                relayAd.getTtl()
            );

        final TrustGraphNode tgn = 
            new LanternTrustGraphNode(xmppHandler);
        tgn.sendAdvertisement(message, nextNid, relayAd.getTtl()); 
    }
    
    @Override
    public void onBase64Cert(final String jid, final String base64Cert) {
        try {
            this.trustStore.addBase64Cert(jid, base64Cert);
        } catch (final IOException e) {
            log.error("Could not add cert?", e);
        }
        
        final LanternKscopeAdvertisement ad = awaitingCerts.get(jid);
        if (ad != null) {
            if (ad.hasMappedEndpoint()) {
                this.proxyTracker.addProxy(
                    InetSocketAddress.createUnresolved(ad.getAddress(), ad.getPort()));
            } else {
                this.proxyTracker.addJidProxy(ad.getJid());
            }
            awaitingCerts.remove(jid);
        } else {
            // This could happen if we negotiated certs in some way other than
            // in response to a kscope ad, such as for peers from the 
            // controller.
            log.info("No ad for cert?");
            this.proxyTracker.addJidProxy(jid);
        }
    }

}
