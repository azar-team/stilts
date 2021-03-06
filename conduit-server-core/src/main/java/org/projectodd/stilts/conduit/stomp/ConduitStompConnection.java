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

package org.projectodd.stilts.conduit.stomp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.transaction.NotSupportedException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.jboss.logging.Logger;
import org.projectodd.stilts.conduit.spi.MessageConduit;
import org.projectodd.stilts.stomp.Acknowledger;
import org.projectodd.stilts.stomp.Headers;
import org.projectodd.stilts.stomp.Heartbeat;
import org.projectodd.stilts.stomp.InvalidSubscriptionException;
import org.projectodd.stilts.stomp.InvalidTransactionException;
import org.projectodd.stilts.stomp.NotConnectedException;
import org.projectodd.stilts.stomp.StompException;
import org.projectodd.stilts.stomp.StompMessage;
import org.projectodd.stilts.stomp.Subscription;
import org.projectodd.stilts.stomp.protocol.StompFrame.Version;
import org.projectodd.stilts.stomp.server.protocol.HeartbeatRunnable;
import org.projectodd.stilts.stomp.spi.StompConnection;
import org.projectodd.stilts.stomp.spi.StompSession;
import org.projectodd.stilts.stomp.spi.StompTransaction;

public class ConduitStompConnection implements StompConnection {

    private static Logger log = Logger.getLogger(ConduitStompConnection.class);

    public ConduitStompConnection(ConduitStompProvider stompProvider, MessageConduit messageConduit, Version version, Heartbeat hb) throws StompException {
        log.debugf( "New connection: %s", messageConduit );
        this.stompProvider = stompProvider;
        this.messageConduit = messageConduit;
        this.version = version;
        this.heartbeat = hb;
    }

    public Heartbeat getHeartbeat() {
        return this.heartbeat;
    }

    @Override
    public StompSession getSession() {
        return this.messageConduit.getSession();
    }

    public Version getVersion() {
        return this.version;
    }

    public ConduitStompProvider getStompProvider() {
        return this.stompProvider;
    }

    public MessageConduit getMessageConduit() {
        return this.messageConduit;
    }

    public void send(StompMessage message, String transactionId) throws StompException {
        if (transactionId != null) {
            getTransaction( transactionId ).send( message );
        } else {
            send( message );
        }
    }

    protected void send(StompMessage message) throws StompException {
        try {
            this.messageConduit.send( message );
        } catch (Exception e) {
            log.errorf(e, "Cannot send message: %s", message);
            throw new StompException( e );
        }
    }

    void ack(Acknowledger acknowledger, String transactionId) throws StompException {
        if (transactionId != null) {
            getTransaction( transactionId ).ack( acknowledger );
        } else {
            try {
                acknowledger.ack();
            } catch (Exception e) {
                throw new StompException( e );
            }
        }
    }

    void nack(Acknowledger acknowledger, String transactionId) throws StompException {
        if (transactionId != null) {
            getTransaction( transactionId ).nack( acknowledger );
        } else {
            try {
                acknowledger.nack();
            } catch (Exception e) {
                throw new StompException( e );
            }
        }
    }

    synchronized ConduitStompTransaction getTransaction(String transactionId) throws InvalidTransactionException {

        ConduitStompTransaction transaction = this.namedTransactions.get( transactionId );

        if (transaction == null) {
            throw new InvalidTransactionException( transactionId );
        }

        return transaction;
    }

    synchronized ConduitStompTransaction removeTransaction(String transactionId) {
        return this.namedTransactions.remove( transactionId );
    }

    @Override
    public synchronized void begin(String transactionId, Headers headers) throws StompException {

        Transaction jtaTransaction = null;
        TransactionManager tm = getStompProvider().getTransactionManager();
        try {
            tm.begin();
            jtaTransaction = tm.getTransaction();
            tm.suspend();
        } catch (NotSupportedException e) {
            throw new StompException( e );
        } catch (SystemException e) {
            throw new StompException( e );
        }

        try {
            ConduitStompTransaction transaction = createTransaction( jtaTransaction, transactionId );
            this.namedTransactions.put( transactionId, transaction );
        } catch (Exception e) {
            throw new StompException( e );
        }
    }

    @Override
    public synchronized void commit(String transactionId) throws StompException {
        StompTransaction transaction = removeTransaction( transactionId );
        if (transaction == null) {
            throw new InvalidTransactionException( transactionId );
        }
        transaction.commit();
    }

    @Override
    public synchronized void abort(String transactionId) throws StompException {
        StompTransaction transaction = removeTransaction( transactionId );
        if (transaction == null) {
            throw new InvalidTransactionException( transactionId );
        }
        transaction.abort();
    }

    @Override
    public synchronized Subscription subscribe(String destination, String subscriptionId, Headers headers) throws StompException {
        try {
            Subscription subscription = createSubscription( destination, subscriptionId, headers );
            if (subscription == null) {
                log.debugf( "unable to create subscription for destination %s", destination );
                return null;
            }
            this.subscriptions.put( subscription.getId(), subscription );
            return subscription;
        } catch (Exception e) {
            throw new StompException( e );
        }
    }

    public Subscription createSubscription(String destination, String subscriptionId, Headers headers) throws Exception {
        return this.messageConduit.subscribe( subscriptionId, destination, headers );
    }

    @Override
    public synchronized void unsubscribe(String id, Headers headers) throws StompException {
        Subscription subscription = this.subscriptions.remove( id );
        if (subscription == null) {
            throw new InvalidSubscriptionException( id );
        }
        subscription.cancel();
    }

    @Override
    public synchronized void disconnect() throws NotConnectedException {
        if (heartbeat != null) {
            heartbeat.stop();;
        }

        for (StompTransaction each : this.namedTransactions.values()) {
            try {
                each.abort();
            } catch (StompException e) {
                log.errorf(e, "Cannot disconnect");
            }
        }

        this.namedTransactions.clear();

        for (Subscription each : this.subscriptions.values()) {
            try {
                each.cancel();
            } catch (StompException e) {
                log.errorf(e, "Cannot cancel subsrciption: %s", each);
            }
        }

        this.subscriptions.clear();

        this.stompProvider.unregister( this );
    }

    protected ConduitStompTransaction createTransaction(Transaction jtaTransaction, String transactionId) throws Exception {
        return new ConduitStompTransaction( this, jtaTransaction, transactionId );
    }

    private Map<String, Subscription> subscriptions = new HashMap<String, Subscription>();

    private Map<String, ConduitStompTransaction> namedTransactions = new HashMap<String, ConduitStompTransaction>();

    private MessageConduit messageConduit;
    private ConduitStompProvider stompProvider;
    private Version version;
    private Heartbeat heartbeat;
}
