/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.transport.http.netty.websocket;

import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnection;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.client.websocket.WebSocketTestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebSocketServerHandshakeFunctionalityTestCase {

    private static final int countdownLatchTimeout = 10;
    private ServerConnector serverConnector;
    private WebSocketServerHandshakeFunctionalityListener listener;

    @BeforeClass
    public void setup() throws InterruptedException {
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setHost("localhost");
        listenerConfiguration.setPort(TestUtil.SERVER_CONNECTOR_PORT);
        DefaultHttpWsConnectorFactory httpConnectorFactory = new DefaultHttpWsConnectorFactory();
        serverConnector = httpConnectorFactory.createServerConnector(TestUtil.getDefaultServerBootstrapConfig(),
                                                                     listenerConfiguration);
        ServerConnectorFuture connectorFuture = serverConnector.start();
        connectorFuture.setWSConnectorListener(listener = new WebSocketServerHandshakeFunctionalityListener());
        connectorFuture.sync();
    }

    @Test(description = "Check whether the correct sub protocol is chosen by the server with the given sequence.")
    public void testServerSubProtocolNegotiationSuccessful() throws URISyntaxException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-negotiate-sub-protocols", "true");
        WebSocketTestClient testClient = new WebSocketTestClient("dummy1, xml, dummy2, json", headers);
        testClient.handshake();

        Assert.assertEquals(testClient.getHandshaker().actualSubprotocol(), "xml");

        testClient.closeChannel();
    }

    @Test(description = "Check whether no any sub protocol is negotiated when unsupported sub protocols are requested.",
          expectedExceptions = WebSocketHandshakeException.class,
          expectedExceptionsMessageRegExp = "Invalid subprotocol. Actual: null. Expected one of: dummy1, dummy2")
    public void testServerSubProtocolNegotiationFail() throws URISyntaxException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-negotiate-sub-protocols", "true");
        WebSocketTestClient testClient = new WebSocketTestClient("dummy1, dummy2", headers);
        testClient.handshake();
    }

    @Test(description = "Check the capability of sending custom headers in handshake response.")
    public void testServerHandshakeWithCustomHeadersResponse() throws URISyntaxException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-send-custom-header", "true");
        WebSocketTestClient testClient = new WebSocketTestClient(null, headers);
        testClient.handshake();

        Assert.assertEquals(testClient.getHandshakeResponse().headers().get("x-custom-header"), "custom-header-value");

        testClient.closeChannel();
    }

    @Test(description = "Check whether no any sub protocol is negotiated when unsupported sub protocols are requested.",
          expectedExceptions = WebSocketHandshakeException.class,
          expectedExceptionsMessageRegExp = "Invalid handshake response getStatus: 404 Not Found")
    public void testCancelHandshake() throws URISyntaxException, InterruptedException {
        WebSocketTestClient testClient = new WebSocketTestClient();
        testClient.handshake();
    }

    @Test
    public void testReadNextFrame() throws URISyntaxException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-wait-for-frame-read", "true");
        int noOfMsgs = 10;
        String testMsgArray[] = new String[noOfMsgs];
        for (int i = 0; i < noOfMsgs; i++) {
            testMsgArray[i] = "testMessage" + i;
        }

        WebSocketTestClient testClient = new WebSocketTestClient(null, headers);
        CountDownLatch handshakeCompleteCountDownLatch = new CountDownLatch(1);
        listener.setHandshakeCompleCountDownLatch(handshakeCompleteCountDownLatch);
        testClient.handshake();
        handshakeCompleteCountDownLatch.await(countdownLatchTimeout, TimeUnit.SECONDS);
        for (String testMsg: testMsgArray) {
            testClient.sendText(testMsg);
        }
        WebSocketConnection webSocketConnection = listener.getCurrentWebSocketConnection();

        Assert.assertNotNull(webSocketConnection);

        for (String testMsg: testMsgArray) {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            testClient.setCountDownLatch(countDownLatch);
            webSocketConnection.readNextFrame();
            countDownLatch.await(countdownLatchTimeout, TimeUnit.SECONDS);

            Assert.assertEquals(testClient.getTextReceived(), testMsg);
        }
    }

    @Test(description = "Test connection closure due to idle timeout in server.")
    public void testIdleTimeout() throws URISyntaxException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-set-connection-timeout", "true");
        WebSocketTestClient testClient = new WebSocketTestClient(null, headers);
        testClient.handshake();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        testClient.setCountDownLatch(countDownLatch);
        countDownLatch.await(countdownLatchTimeout, TimeUnit.SECONDS);
        CloseWebSocketFrame closeFrame = testClient.getReceivedCloseFrame();

        Assert.assertNotNull(closeFrame);
        Assert.assertEquals(closeFrame.statusCode(), 1001);
        Assert.assertEquals(closeFrame.reasonText(), "Connection Idle Timeout");

        testClient.sendCloseFrame(closeFrame.statusCode(), null).closeChannel();
    }

    @Test(description = "WebSocket server sends 400 Bad Request if a handshake request is received with " +
            "other than GET method")
    public void testHandshakeWithPostMethod() throws IOException {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        URL url = URI.create(String.format("http://%s:%d/%s", TestUtil.TEST_HOST, TestUtil.SERVER_CONNECTOR_PORT,
                                           "/websocket")).toURL();
        HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
        urlConn.setRequestMethod("POST");
        urlConn.setRequestProperty("Connection", "Upgrade");
        urlConn.setRequestProperty("Upgrade", "websocket");
        urlConn.setRequestProperty("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        urlConn.setRequestProperty("Sec-WebSocket-Version", "13");

        Assert.assertEquals(urlConn.getResponseCode(), 400);
        Assert.assertEquals(urlConn.getResponseMessage(), "Bad Request");
        Assert.assertNull(urlConn.getHeaderField("sec-websocket-accept"));
    }

    @AfterClass
    public void cleanup() {
        serverConnector.stop();
    }
}
