package org.projectodd.stilts.conduit;

import java.util.ArrayList;
import java.util.List;

import org.projectodd.stilts.conduit.spi.MessageConduit;
import org.projectodd.stilts.conduit.spi.MessageConduitFactory;
import org.projectodd.stilts.stomp.Headers;
import org.projectodd.stilts.stomp.spi.AcknowledgeableMessageSink;

public class MockMessageConduitFactory implements MessageConduitFactory {

    @Override
    public MessageConduit createMessageConduit(AcknowledgeableMessageSink messageSink, Headers headers) throws Exception {
        MockMessageConduit conduit = new MockMessageConduit();
        this.conduits.add( conduit );
        return conduit;
    }
    
    public List<MockMessageConduit> getConduits() {
        return this.conduits;
    }
    
    private List<MockMessageConduit> conduits = new ArrayList<MockMessageConduit>();

}