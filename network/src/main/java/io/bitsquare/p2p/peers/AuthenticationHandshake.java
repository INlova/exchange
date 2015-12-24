package io.bitsquare.p2p.peers;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.bitsquare.app.Log;
import io.bitsquare.common.UserThread;
import io.bitsquare.p2p.Address;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.network.Connection;
import io.bitsquare.p2p.network.ConnectionPriority;
import io.bitsquare.p2p.network.MessageListener;
import io.bitsquare.p2p.network.NetworkNode;
import io.bitsquare.p2p.peers.messages.auth.AuthenticationChallenge;
import io.bitsquare.p2p.peers.messages.auth.AuthenticationMessage;
import io.bitsquare.p2p.peers.messages.auth.AuthenticationRequest;
import io.bitsquare.p2p.peers.messages.auth.AuthenticationResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

// Authentication protocol: 
// client: send AuthenticationRequest to seedNode
// seedNode: close connection
// seedNode: send AuthenticationChallenge to client on a new connection to test if address is correct
// client: authentication to seedNode done if nonce verification is ok
// client: AuthenticationResponse to seedNode
// seedNode: authentication to client done if nonce verification is ok

public class AuthenticationHandshake implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationHandshake.class);

    private final NetworkNode networkNode;
    private final PeerGroup peerGroup;
    private final Address myAddress;
    private final Address peerAddress;

    private final long startAuthTs;
    private Optional<SettableFuture<Connection>> resultFutureOptional = Optional.empty();
    private long nonce;
    private boolean stopped;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public AuthenticationHandshake(NetworkNode networkNode, PeerGroup peerGroup, Address myAddress, Address peerAddress) {
        Log.traceCall("peerAddress " + peerAddress);
        this.networkNode = networkNode;
        this.peerGroup = peerGroup;
        this.myAddress = myAddress;
        this.peerAddress = peerAddress;

        startAuthTs = System.currentTimeMillis();
        stopped = false;
        nonce = 0;

        networkNode.addMessageListener(this);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(Message message, Connection connection) {
        if (stopped) {
            log.warn("AuthenticationHandshake already shut down but still got onMessage called. That must not happen.");
            return;
        }

        if (message instanceof AuthenticationMessage) {
            // We are listening on all connections, so we need to filter out only our peer
            if (((AuthenticationMessage) message).senderAddress.equals(peerAddress)) {
                Log.traceCall(message.toString());
                if (message instanceof AuthenticationChallenge) {
                    // Requesting peer
                    AuthenticationChallenge authenticationChallenge = (AuthenticationChallenge) message;
                    // We need to set the address to the connection, otherwise we will not find the connection when sending
                    // the next message and we would create a new outbound connection instead using the inbound.
                    connection.setPeerAddress(authenticationChallenge.senderAddress);
                    // We use the active connectionType if we started the authentication request to another peer
                    connection.setConnectionPriority(ConnectionPriority.ACTIVE);
                    log.trace("Received authenticationResponse from " + peerAddress);
                    boolean verified = nonce != 0 && nonce == authenticationChallenge.requesterNonce;
                    if (verified) {
                        AuthenticationResponse authenticationResponse = new AuthenticationResponse(myAddress,
                                authenticationChallenge.responderNonce,
                                new HashSet<>(peerGroup.getAuthenticatedAndReportedPeers()));
                        SettableFuture<Connection> future = networkNode.sendMessage(peerAddress, authenticationResponse);
                        log.trace("Sent GetPeersAuthRequest {} to {}", authenticationResponse, peerAddress);
                        Futures.addCallback(future, new FutureCallback<Connection>() {
                            @Override
                            public void onSuccess(Connection connection) {
                                log.trace("Successfully sent GetPeersAuthRequest to {}", peerAddress);

                                log.info("AuthenticationComplete: Peer with address " + peerAddress
                                        + " authenticated (" + connection.getUid() + "). Took "
                                        + (System.currentTimeMillis() - startAuthTs) + " ms.");
                                completed(connection);
                            }

                            @Override
                            public void onFailure(@NotNull Throwable throwable) {
                                log.info("GetPeersAuthRequest sending failed " + throwable.getMessage());
                                failed(throwable);
                            }
                        });

                        // now we add the reported peers to our list 
                        peerGroup.addToReportedPeers(authenticationChallenge.reportedPeers, connection);
                    } else {
                        log.warn("Verification of nonce failed. AuthenticationResponse=" + authenticationChallenge + " / nonce=" + nonce);
                        failed(new Exception("Verification of nonce failed. AuthenticationResponse=" + authenticationChallenge + " / nonceMap=" + nonce));
                    }
                } else if (message instanceof AuthenticationResponse) {
                    // Responding peer
                    AuthenticationResponse authenticationResponse = (AuthenticationResponse) message;
                    log.trace("Received GetPeersAuthRequest from " + peerAddress + " at " + myAddress);
                    boolean verified = nonce != 0 && nonce == authenticationResponse.responderNonce;
                    if (verified) {
                        peerGroup.addToReportedPeers(authenticationResponse.reportedPeers, connection);
                        log.info("AuthenticationComplete: Peer with address " + peerAddress
                                + " authenticated (" + connection.getUid() + "). Took "
                                + (System.currentTimeMillis() - startAuthTs) + " ms.");
                        completed(connection);
                    } else {
                        log.warn("Verification of nonce failed. getPeersMessage=" + authenticationResponse + " / nonce=" + nonce);
                        failed(new Exception("Verification of nonce failed. getPeersMessage=" + authenticationResponse + " / nonce=" + nonce));
                    }
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Authentication initiated by requesting peer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SettableFuture<Connection> requestAuthentication() {
        Log.traceCall("peerAddress " + peerAddress);
        // Requesting peer

        if (stopped) {
            log.warn("AuthenticationHandshake already shut down but still got requestAuthentication called. That must not happen.");
        }

        resultFutureOptional = Optional.of(SettableFuture.create());
        AuthenticationRequest authenticationRequest = new AuthenticationRequest(myAddress, getAndSetNonce());
        SettableFuture<Connection> future = networkNode.sendMessage(peerAddress, authenticationRequest);
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                log.trace("send AuthenticationRequest to " + peerAddress + " succeeded.");

                // We protect that connection from getting closed by maintenance cleanup...
                connection.setConnectionPriority(ConnectionPriority.AUTH_REQUEST);
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                log.info("Send AuthenticationRequest to " + peerAddress + " failed." +
                        "\nException:" + throwable.getMessage());
                failed(throwable);
            }
        });

        return resultFutureOptional.get();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Responding to authentication request
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SettableFuture<Connection> respondToAuthenticationRequest(AuthenticationRequest authenticationRequest,
                                                                     Connection connection) {
        Log.traceCall("peerAddress " + peerAddress);
        // Responding peer

        if (stopped) {
            log.warn("AuthenticationHandshake already shut down but still got respondToAuthenticationRequest called. That must not happen.");
        }

        resultFutureOptional = Optional.of(SettableFuture.create());

        log.info("We shut down inbound connection from peer {} to establish a new " +
                "connection with his reported address to verify if his address is correct.", peerAddress);

        connection.shutDown(() -> {
            UserThread.runAfter(() -> {
                if (!stopped) {
                    // we delay a bit as listeners for connection.onDisconnect are on other threads and might lead to 
                    // inconsistent state
                    log.trace("respondToAuthenticationRequest: connection.shutDown complete. peerAddress=" + peerAddress + " / myAddress=" + myAddress);

                    // we send additionally the reported and authenticated peers to save one message in the protocol.
                    AuthenticationChallenge authenticationChallenge = new AuthenticationChallenge(myAddress,
                            authenticationRequest.requesterNonce,
                            getAndSetNonce(),
                            new HashSet<>(peerGroup.getAuthenticatedAndReportedPeers()));
                    SettableFuture<Connection> future = networkNode.sendMessage(peerAddress, authenticationChallenge);
                    Futures.addCallback(future, new FutureCallback<Connection>() {
                        @Override
                        public void onSuccess(Connection connection) {
                            log.trace("AuthenticationResponse successfully sent");

                            // We use passive connectionType for connections created from received authentication 
                            // requests from other peers 
                            connection.setConnectionPriority(ConnectionPriority.PASSIVE);
                        }

                        @Override
                        public void onFailure(@NotNull Throwable throwable) {
                            log.warn("onFailure sending AuthenticationResponse. " + throwable.getMessage());
                            failed(throwable);
                        }
                    });
                } else {
                    log.warn("AuthenticationHandshake already shut down before we could sent AuthenticationResponse. That might happen in rare cases.");
                }
            }, 1000, TimeUnit.MILLISECONDS); // Don't set the delay too short as the CloseConnectionMessage might arrive too late at the peer
        });
        return resultFutureOptional.get();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Cancel if we send reject message
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void cancel() {
        failed(new CancelAuthenticationException());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private long getAndSetNonce() {
        Log.traceCall();
        nonce = new Random().nextLong();
        while (nonce == 0)
            nonce = new Random().nextLong();

        return nonce;
    }

    private void failed(@NotNull Throwable throwable) {
        Log.traceCall(throwable.toString());
        shutDown();
        if (resultFutureOptional.isPresent())
            resultFutureOptional.get().setException(throwable);
        else
            log.warn("failed called but resultFuture = null. That must never happen.");
    }

    private void completed(Connection connection) {
        Log.traceCall();
        shutDown();
        if (resultFutureOptional.isPresent())
            resultFutureOptional.get().set(connection);
        else
            log.warn("completed called but resultFuture = null. That must never happen.");
    }

    private void shutDown() {
        Log.traceCall();
        stopped = true;
        networkNode.removeMessageListener(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthenticationHandshake)) return false;

        AuthenticationHandshake that = (AuthenticationHandshake) o;

        return !(peerAddress != null ? !peerAddress.equals(that.peerAddress) : that.peerAddress != null);

    }

    @Override
    public int hashCode() {
        return peerAddress != null ? peerAddress.hashCode() : 0;
    }

}
