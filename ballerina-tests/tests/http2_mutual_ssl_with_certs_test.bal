// Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/http;
import ballerina/test;

http:ListenerConfiguration sslConf = {
    secureSocket: {
        key: {
            certFile: "tests/certsandkeys/public.crt",
            keyFile: "tests/certsandkeys/private.key"
        },
        mutualSsl: {
            verifyClient: http:REQUIRE,
            cert: "tests/certsandkeys/public.crt"
        }
    },
    httpVersion: "2.0"
};

listener http:Listener mutualSslistener = new(9110, sslConf);

service /mutualSslService on mutualSslistener {

    resource function get .(http:Caller caller, http:Request req) returns error? {
        http:Response res = new;
        string expectedCert = "MIIDsTCCApmgAwIBAgIUAcBP5M4LISxVgyGsnJohqmsCN/kwDQYJKoZIhvcNAQELBQAwaDELMAkGA1UEBhMCTEsx"
                    + "EDAOBgNVBAgMB1dlc3Rlcm4xEDAOBgNVBAcMB0NvbG9tYm8xDTALBgNVBAoMBHdzbzIxEjAQBgNVBAsMCWJhbGxlcmluYTE"
                    + "SMBAGA1UEAwwJbG9jYWxob3N0MB4XDTE5MDgwNTA1MTMwNVoXDTI5MDgwMjA1MTMwNVowaDELMAkGA1UEBhMCTEsxEDAOBgN"
                    + "VBAgMB1dlc3Rlcm4xEDAOBgNVBAcMB0NvbG9tYm8xDTALBgNVBAoMBHdzbzIxEjAQBgNVBAsMCWJhbGxlcmluYTESMBAGA1U"
                    + "EAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqSC9N8U5R+gt1XvBEvMIzQY1wJyreBoEhIl"
                    + "WCzw2CtcJ4GxFk+sDe6NmuQ0td0JVmTFkv/G8Cns+30wU7KZLhNcRjTOTpHVAkj6zXYlfrz4z4J9y2vnbFKpftYgamOZXE3"
                    + "Lze/rApAwDVdQKcjAaSc9vQcf2810ou21NrO4RiyOHePJ9F6TQMXrBwFmC2TvCmj6W13txbwefa/O1QSEbVgLQjM7XU/QXF2"
                    + "2BlSrrzrynslf8o3eu2+Rk3sU6RxijyogRp9mMe0uTKU+LB252RqUfpDsdOnVNiccPQi/6sjCPPhFx81rYGrkH9CEvBcvzLe"
                    + "M4mBwOfncH1B3HpuBEYwIDAQABo1MwUTAdBgNVHQ4EFgQU8NRYN+9Cj1nCT/8/z69SdoosStQwHwYDVR0jBBgwFoAU8NRYN+"
                    + "9Cj1nCT/8/z69SdoosStQwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAHuS1UwR8yrh7QdmZRPuX6BQWET"
                    + "XXEsto0bnpDD+c5u5WptUg/bYkAGsnkuHYv7TQcN163SXpnExg1QnMcdF+XuwCVtGpqZrmcosvZPCf/CWoobBxsuiim5mY8z"
                    + "D3WwdzAO8kZheemiZM5FZYhaXkBymNNe7qvL/aC6CuwyC3n+4GOtV+xabmH4T/p7HEcvq2SY0YGTpJ0OcUlvJ3UqzhTieK67"
                    + "dSFKmDN3hOBrxacFV9ybzub67erPkQpV4GpJUW9HShp0qXr6WuX1hg7WA6RgneWkq3y2h6sts/c5S/dAP8KlqghvEdv8lnAc"
                    + "SqjN3kSTim/JMMe4kChtjUE7C1Ag==";
	    if req.mutualSslHandshake["status"] == "passed" {
            string? cert = req.mutualSslHandshake["base64EncodedCert"];
            if cert is string {
                if cert == expectedCert {
                    res.setTextPayload("Response received");
                } else {
                    res.setTextPayload("Expected cert not found");
                }
            } else {
                res.setTextPayload("Cert not found");
            }
        }
        check caller->respond( res);
    }
}

http:ClientConfiguration certsClientConf1 = {
    secureSocket: {
        cert: "tests/certsandkeys/public.crt",
        key: {
            keyFile: "tests/certsandkeys/private.key",
            certFile: "tests/certsandkeys/public.crt"
        }
    },
    httpVersion: "2.0"
};

@test:Config {}
public function mutualSslWithCerts1() returns error? {
    http:Client clientEP = check new("https://localhost:9110", certsClientConf1);
    http:Response|error resp = clientEP->get("/mutualSslService/");
    if resp is http:Response {
        assertTextPayload(resp.getTextPayload(), "Response received");
    } else {
        test:assertFail(msg = "Found unexpected output: " +  resp.message());
    }
}

http:ClientConfiguration certsClientConf2 = {
    secureSocket: {
        key: {
            keyFile: "tests/certsandkeys/private.key",
            certFile: "tests/certsandkeys/public.crt"
        }
    },
    httpVersion: "2.0"
};

// https://github.com/ballerina-platform/ballerina-standard-library/issues/483
@test:Config {}
public function mutualSslWithCerts2() {
    http:Client|error clientEP = new("https://localhost:9110", certsClientConf2);
    string expectedErrMsg = "Need to configure cert with client SSL certificates file for HTTP 2.0";
    if clientEP is error {
        test:assertEquals(clientEP.message(), expectedErrMsg);
    } else {
        test:assertFail(msg = "Expected mutual SSL error not found");
    }
}

http:ClientConfiguration certsClientConf3 = {
    secureSocket: {
        enable: false,
        key: {
            keyFile: "tests/certsandkeys/private.key",
            certFile: "tests/certsandkeys/public.crt"
        }
    },
    httpVersion: "2.0"
};

@test:Config {}
public function mutualSslWithCerts3() returns error? {
    http:Client clientEP = check new("https://localhost:9110", certsClientConf3);
    http:Response|error resp = clientEP->get("/mutualSslService/");
    if resp is http:Response {
        assertTextPayload(resp.getTextPayload(), "Response received");
    } else {
        test:assertFail(msg = "Found unexpected output: " +  resp.message());
    }
}
