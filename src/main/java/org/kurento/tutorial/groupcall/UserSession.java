/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
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
 *
 */

package org.kurento.tutorial.groupcall;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Ivan Gracia (izanmail@gmail.com)
 * @since 4.3.1
 */
public class UserSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(UserSession.class);

    private final String name;
    private final WebSocketSession session;

    private final MediaPipeline pipeline;

    private final String roomName;
    private final WebRtcEndpoint outgoingMedia;
    private final WebRtcEndpoint outgoingMediaScreen;
    private final ConcurrentMap<String, WebRtcEndpoint> incomingMedia = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebRtcEndpoint> incomingMediaScreen = new ConcurrentHashMap<>();

    UserSession(final String name, String roomName, final WebSocketSession session,
                MediaPipeline pipeline) {

        this.pipeline = pipeline;
        this.name = name;
        this.session = session;
        this.roomName = roomName;
        this.outgoingMedia = new WebRtcEndpoint.Builder(pipeline).build();
        this.outgoingMediaScreen = new WebRtcEndpoint.Builder(pipeline).build();

        this.outgoingMedia.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidate");
                response.addProperty("name", name);
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(response.toString()));
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });
        this.outgoingMediaScreen.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

            @Override
            public void onEvent(IceCandidateFoundEvent event) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "iceCandidateScreen");
                response.addProperty("name", name);
                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(response.toString()));
                    }
                } catch (IOException e) {
                    log.debug(e.getMessage());
                }
            }
        });
    }

    private WebRtcEndpoint getOutgoingWebRtcPeer() {
        return outgoingMedia;
    }

    private WebRtcEndpoint getOutgoingScreenWebRtcPeer() {
        return outgoingMediaScreen;
    }

    public String getName() {
        return name;
    }

    WebSocketSession getSession() {
        return session;
    }

    /**
     * The room to which the user is currently attending.
     *
     * @return The room
     */
    String getRoomName() {
        return this.roomName;
    }

    void receiveFrom(UserSession sender, String sdpOffer, String Answer) throws IOException {
        log.info("USER {}: connecting with {} in room {}", this.name, sender.getName(), this.roomName);

        log.trace("USER {}: SdpOffer for {} is {}", this.name, sender.getName(), sdpOffer);
        String ipSdpAnswer = null;
        if ("receiveVideoAnswer".equals(Answer)) {
            ipSdpAnswer = this.getEndpointForUser(sender).processOffer(sdpOffer);
        } else if ("receiveScreenAnswer".equals(Answer)) {
            ipSdpAnswer = this.getScreenEndpointForUser(sender).processOffer(sdpOffer);
        }
        final JsonObject scParams = new JsonObject();
        scParams.addProperty("id", Answer);
        scParams.addProperty("name", sender.getName());
        scParams.addProperty("sdpAnswer", ipSdpAnswer);

        log.trace("USER {}: SdpAnswer for {} is {}", this.name, sender.getName(), ipSdpAnswer);
        this.sendMessage(scParams);
        log.debug("gather candidates");
        if ("receiveVideoAnswer".equals(Answer)) {
            this.getEndpointForUser(sender).gatherCandidates();
        } else if ("receiveScreenAnswer".equals(Answer)) {
            this.getScreenEndpointForUser(sender).gatherCandidates();
        }
    }

    private WebRtcEndpoint getEndpointForUser(final UserSession sender) {
        return getWebRtcEndpointForUser(sender, outgoingMedia, incomingMedia, "iceCandidate");
    }


    private WebRtcEndpoint getScreenEndpointForUser(final UserSession sender) {
        return getWebRtcEndpointForUser(sender, outgoingMediaScreen, incomingMediaScreen, "iceCandidateScreen");
    }

    private WebRtcEndpoint getWebRtcEndpointForUser(final UserSession sender,
                                                    WebRtcEndpoint outgoingMedia,
                                                    ConcurrentMap<String, WebRtcEndpoint> incomingMedia,
                                                    final String iceCandidateId) {
        if (sender.getName().equals(name)) {
            log.debug("PARTICIPANT {}: configuring loopback", this.name);
            return outgoingMedia;
        }

        log.debug("PARTICIPANT {}: receiving video from {}", this.name, sender.getName());

        WebRtcEndpoint incoming = incomingMedia.get(sender.getName());
        if (incoming == null) {
            log.debug("PARTICIPANT {}: creating new endpoint for {}", this.name, sender.getName());
            incoming = new WebRtcEndpoint.Builder(pipeline).build();

            incoming.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("id", iceCandidateId);
                    response.addProperty("name", sender.getName());
                    response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(response.toString()));
                        }
                    } catch (IOException e) {
                        log.debug(e.getMessage());
                    }
                }
            });
            incomingMedia.put(sender.getName(), incoming);
        }

        log.debug("PARTICIPANT {}: obtained endpoint for {}", this.name, sender.getName());
        if ("iceCandidate".equals(iceCandidateId)) {
            sender.getOutgoingWebRtcPeer().connect(incoming);
        } else if ("iceCandidateScreen".equals(iceCandidateId)) {
            sender.getOutgoingScreenWebRtcPeer().connect(incoming);
        }
        return incoming;
    }

    void cancelVideoFrom(final String senderName) {
        log.debug("PARTICIPANT {}: canceling video reception from {}", this.name, senderName);
        incomingMedia.remove(senderName);
        log.debug("PARTICIPANT {}: removing endpoint for {}", this.name, senderName);
        incomingMediaScreen.remove(senderName);
    }

    @Override
    public void close() throws IOException {
        log.debug("PARTICIPANT {}: Releasing resources", this.name);
        for (final String remoteParticipantName : incomingMedia.keySet()) {

            log.trace("PARTICIPANT {}: Released incoming EP for {}", this.name, remoteParticipantName);

            final WebRtcEndpoint ep = this.incomingMedia.get(remoteParticipantName);

            ep.release(new Continuation<Void>() {

                @Override
                public void onSuccess(Void result) {
                    log.trace("PARTICIPANT {}: Released successfully incoming EP for {}",
                            UserSession.this.name, remoteParticipantName);
                }

                @Override
                public void onError(Throwable cause) {
                    log.warn("PARTICIPANT {}: Could not release incoming EP for {}", UserSession.this.name,
                            remoteParticipantName);
                }
            });
        }
        for (final String remoteParticipantName : incomingMediaScreen.keySet()) {

            log.trace("PARTICIPANT {}: Released incoming EP for {}", this.name, remoteParticipantName);

            final WebRtcEndpoint ep = this.incomingMediaScreen.get(remoteParticipantName);

            ep.release(new Continuation<Void>() {

                @Override
                public void onSuccess(Void result) {
                    log.trace("PARTICIPANT {}: Released successfully incoming screen EP for {}",
                            UserSession.this.name, remoteParticipantName);
                }

                @Override
                public void onError(Throwable cause) {
                    log.warn("PARTICIPANT {}: Could not release incoming screen EP for {}", UserSession.this.name,
                            remoteParticipantName);
                }
            });
        }

        outgoingMedia.release(new Continuation<Void>() {

            @Override
            public void onSuccess(Void result) {
                log.trace("PARTICIPANT {}: Released outgoing EP", UserSession.this.name);
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("USER {}: Could not release outgoing EP", UserSession.this.name);
            }
        });
        outgoingMediaScreen.release(new Continuation<Void>() {

            @Override
            public void onSuccess(Void result) {
                log.trace("PARTICIPANT {}: Released outgoingScreen EP", UserSession.this.name);
            }

            @Override
            public void onError(Throwable cause) {
                log.warn("USER {}: Could not release outgoingScreen EP", UserSession.this.name);
            }
        });
    }

    void sendMessage(JsonObject message) throws IOException {
        log.debug("USER {}: Sending message {}", name, message);
        synchronized (session) {
            session.sendMessage(new TextMessage(message.toString()));
        }
    }

    void addCandidate(IceCandidate candidate, String name) {
        addCandidate(candidate, name, outgoingMedia, incomingMedia);
    }

    void addCandidateScreen(IceCandidate candidate, String name) {
        addCandidate(candidate, name, outgoingMediaScreen, incomingMediaScreen);
    }

    private void addCandidate(IceCandidate candidate,
                              String name,
                              WebRtcEndpoint outgoingMedia,
                              ConcurrentMap<String, WebRtcEndpoint> incomingMedia) {
        if (this.name.compareTo(name) == 0) {
            outgoingMedia.addIceCandidate(candidate);
        } else {
            WebRtcEndpoint webRtc = incomingMedia.get(name);
            if (webRtc != null) {
                webRtc.addIceCandidate(candidate);
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {

        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UserSession)) {
            return false;
        }
        UserSession other = (UserSession) obj;
        boolean eq = name.equals(other.name);
        eq &= roomName.equals(other.roomName);
        return eq;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + name.hashCode();
        result = 31 * result + roomName.hashCode();
        return result;
    }
}
