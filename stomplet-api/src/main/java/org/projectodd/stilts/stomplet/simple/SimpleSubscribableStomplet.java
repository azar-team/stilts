/*
 * Copyright 2011 Red Hat, Inc, and individual contributors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.projectodd.stilts.stomplet.simple;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.projectodd.stilts.MessageSink;
import org.projectodd.stilts.stomp.StompException;
import org.projectodd.stilts.stomp.StompMessage;
import org.projectodd.stilts.stomplet.Subscriber;
import org.projectodd.stilts.stomplet.helpers.AbstractStomplet;

public abstract class SimpleSubscribableStomplet extends AbstractStomplet implements MessageSink {

    @Override
    public void send(StompMessage message) throws StompException {
        onMessage( message, null );
    }

    @Override
    public void onSubscribe(Subscriber subscriber) throws StompException {
        SubscriberList destinationSubscribers = this.destinations.get( subscriber.getDestination() );
        if ( destinationSubscribers == null ) {
        	SubscriberList newDestinationSubscribers = new SubscriberList();
        	destinationSubscribers = this.destinations.putIfAbsent(  subscriber.getDestination(), 
        			newDestinationSubscribers );
        	if (destinationSubscribers == null) {
        		destinationSubscribers = newDestinationSubscribers;
        	}
        }
        destinationSubscribers.addSubscriber( subscriber );
    }

    @Override
    public void onUnsubscribe(Subscriber subscriber) throws StompException {
        SubscriberList destinationSubscribers = this.destinations.get( subscriber.getDestination() );
        if ( destinationSubscribers != null ) {
            destinationSubscribers.removeSubscriber( subscriber );
        }
    }
    
    protected void sendToAllSubscribers(StompMessage message) throws StompException {
        SubscriberList destinationSubscribers = this.destinations.get( message.getDestination() );
        if ( destinationSubscribers != null ) {
            destinationSubscribers.sendToAllSubscribers( message );
        }
    }
    
    protected void sendToOneSubscriber(StompMessage message) throws StompException {
        SubscriberList destinationSubscribers = this.destinations.get( message.getDestination() );
        if ( destinationSubscribers != null ) {
            destinationSubscribers.sendToOneSubscriber( message );
        }
    }
    
    private ConcurrentMap<String,SubscriberList> destinations = new ConcurrentHashMap<String,SubscriberList>();

}
