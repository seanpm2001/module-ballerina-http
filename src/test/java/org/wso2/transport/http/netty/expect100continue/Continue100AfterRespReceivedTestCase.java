/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.expect100continue;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.contract.Constants;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contract.config.ChunkConfig;
import org.wso2.transport.http.netty.contract.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.config.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.contract.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.DefaultHttpConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.listeners.Continue100AfterRespReceivedListener;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

/**
 * This class test client 100-continue implementation with content-length request.
 */
public class Continue100AfterRespReceivedTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(Continue100AfterRespReceivedTestCase.class);

    private ServerConnector serverConnector;
    private HttpClientConnector httpClientConnector;
    private HttpWsConnectorFactory httpWsConnectorFactory;

    @BeforeClass
    public void setup() throws InterruptedException {
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.SERVER_CONNECTOR_PORT);
        listenerConfiguration.setServerHeader(TestUtil.TEST_SERVER);

        ServerBootstrapConfiguration serverBootstrapConfig = new ServerBootstrapConfiguration(new HashMap<>());
        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();

        SenderConfiguration senderConfiguration = new SenderConfiguration();
        senderConfiguration.setChunkingConfig(ChunkConfig.NEVER);
        senderConfiguration.setSocketIdleTimeout(15000);
        httpClientConnector = httpWsConnectorFactory.createHttpClientConnector(new HashMap<>(), senderConfiguration);

        serverConnector = httpWsConnectorFactory.createServerConnector(serverBootstrapConfig, listenerConfiguration);
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(new Continue100AfterRespReceivedListener());
        serverConnectorFuture.sync();
    }

    @Test
    public void test100Continue() {
        try {
            HttpCarbonMessage requestMsg = new HttpCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                                                                                        HttpMethod.POST, ""));

            requestMsg.setProperty(Constants.HTTP_PORT, TestUtil.SERVER_CONNECTOR_PORT);
            requestMsg.setProperty(Constants.PROTOCOL, Constants.HTTP_SCHEME);
            requestMsg.setProperty(Constants.HTTP_HOST, TestUtil.TEST_HOST);
            requestMsg.setHttpMethod(Constants.HTTP_POST_METHOD);
            requestMsg.setHeader(HttpHeaderNames.EXPECT.toString(), HttpHeaderValues.CONTINUE);
            requestMsg.setHeader("X-Status", "Positive");

            CountDownLatch latch = new CountDownLatch(1);
            DefaultHttpConnectorListener listener = new DefaultHttpConnectorListener(latch);
            httpClientConnector.send(requestMsg).setHttpConnectorListener(listener);

            HttpMessageDataStreamer httpMessageDataStreamer = new HttpMessageDataStreamer(requestMsg);
            httpMessageDataStreamer.getOutputStream().write(TestUtil.largeEntity.getBytes());
            httpMessageDataStreamer.getOutputStream().close();

            latch.await(30, TimeUnit.SECONDS);

            String responseBody = TestUtil.getStringFromInputStream(
                    new HttpMessageDataStreamer(listener.getHttpResponseMessage()).getInputStream());

            assertEquals(responseBody, TestUtil.largeEntity);
        } catch (Exception e) {
            TestUtil.handleException("Exception occurred while running httpsGetTest", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        serverConnector.stop();
        try {
            httpWsConnectorFactory.shutdown();
        } catch (InterruptedException e) {
            LOG.error("Interrupted while waiting for HttpWsFactory to shutdown", e);
        }
    }
}
