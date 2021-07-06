/*
 * Copyright (c) 2021 Vantiq, Inc.
 *
 * All rights reserved.
 *
 * SPDX: MIT
 */

package io.vantiq.extsrc.senseLinkConnector;

import io.vantiq.extjsdk.FalseClient;

public class NoSendTestConnectorCore extends SenseLinkConnectorCore {

    FalseClient fClient;
    boolean closed = false;

    public NoSendTestConnectorCore(String sourceName, String authToken, String targetVantiqServer) {
        super(sourceName, authToken, targetVantiqServer);
    }

    /**
     * Tries to connect to a source and waits up to {@code timeout} seconds for it to succeed or fail.
     * @return  true if the source connection succeeds, false if it fails.
     */
    @Override
    public void start(int timeout) {
        closed = false;
        fClient = new FalseClient(sourceName);
        client = fClient;
        testConnectorHandleConfiguration = new SenseLinkConnectorHandleConfiguration(this);

        client.setConfigHandler(testConnectorHandleConfiguration);
        client.setReconnectHandler(reconnectHandler);
        client.setCloseHandler(closeHandler);
        client.initiateFullConnection(targetVantiqServer, authToken);

        fClient.completeAuthentication(true);
        fClient.completeWebSocketConnection(true);
        fClient.completeSourceConnection(true);

        exitIfConnectionFails(timeout);
    }

    @Override
    public void close() {
        super.close();
        closed = true;
    }

    @Override
    public void stop() {
        super.stop();
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
