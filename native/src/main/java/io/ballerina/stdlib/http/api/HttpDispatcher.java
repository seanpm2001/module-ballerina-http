/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.stdlib.http.api;

import io.ballerina.runtime.api.creators.ErrorCreator;
import io.ballerina.runtime.api.types.ArrayType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.http.api.service.signature.AllHeaderParams;
import io.ballerina.stdlib.http.api.service.signature.AllQueryParams;
import io.ballerina.stdlib.http.api.service.signature.NonRecurringParam;
import io.ballerina.stdlib.http.api.service.signature.ParamHandler;
import io.ballerina.stdlib.http.api.service.signature.Parameter;
import io.ballerina.stdlib.http.api.service.signature.PayloadParam;
import io.ballerina.stdlib.http.api.service.signature.RemoteMethodParamHandler;
import io.ballerina.stdlib.http.transport.message.HttpCarbonMessage;
import io.ballerina.stdlib.http.uri.URIUtil;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.ballerina.runtime.api.TypeTags.ARRAY_TAG;
import static io.ballerina.stdlib.http.api.HttpConstants.DEFAULT_HOST;
import static io.ballerina.stdlib.http.api.HttpConstants.EXTRA_PATH_INDEX;
import static io.ballerina.stdlib.http.api.service.signature.ParamUtils.castParam;
import static io.ballerina.stdlib.http.api.service.signature.ParamUtils.castParamArray;

/**
 * {@code HttpDispatcher} is responsible for dispatching incoming http requests to the correct resource.
 *
 * @since 0.94
 */
public class HttpDispatcher {

    public static HttpService findService(HTTPServicesRegistry servicesRegistry, HttpCarbonMessage inboundReqMsg,
                                          boolean forInterceptors) {
        try {
            Map<String, HttpService> servicesOnInterface;
            List<String> sortedServiceURIs;
            String hostName = inboundReqMsg.getHeader(HttpHeaderNames.HOST.toString());

            if (hostName != null && servicesRegistry.getServicesMapHolder(hostName) != null) {
                servicesOnInterface = servicesRegistry.getServicesByHost(hostName);
                sortedServiceURIs = servicesRegistry.getSortedServiceURIsByHost(hostName);
            } else if (servicesRegistry.getServicesMapHolder(DEFAULT_HOST) != null) {
                servicesOnInterface = servicesRegistry.getServicesByHost(DEFAULT_HOST);
                sortedServiceURIs = servicesRegistry.getSortedServiceURIsByHost(DEFAULT_HOST);
            } else {
                inboundReqMsg.setHttpStatusCode(404);
                String localAddress = inboundReqMsg.getProperty(HttpConstants.LOCAL_ADDRESS).toString();
                throw new BallerinaConnectorException("no service has registered for listener : " + localAddress);
            }

            String rawUri = (String) inboundReqMsg.getProperty(HttpConstants.TO);
            Map<String, Map<String, String>> matrixParams = new HashMap<>();
            String uriWithoutMatrixParams = URIUtil.extractMatrixParams(rawUri, matrixParams);

            URI validatedUri = getValidatedURI(uriWithoutMatrixParams);

            String basePath = servicesRegistry.findTheMostSpecificBasePath(validatedUri.getRawPath(),
                                                                           servicesOnInterface, sortedServiceURIs);

            if (basePath == null) {
                inboundReqMsg.setHttpStatusCode(404);
                throw new BallerinaConnectorException("no matching service found for path : " +
                                                              validatedUri.getRawPath());
            }

            HttpService service = servicesOnInterface.get(basePath);
            if (!forInterceptors) {
                setInboundReqProperties(inboundReqMsg, validatedUri, basePath);
                inboundReqMsg.setProperty(HttpConstants.RAW_URI, rawUri);
                inboundReqMsg.setProperty(HttpConstants.TO, uriWithoutMatrixParams);
                inboundReqMsg.setProperty(HttpConstants.MATRIX_PARAMS, matrixParams);
            }
            return service;
        } catch (Exception e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
    }

    public static InterceptorService findInterceptorService(HTTPInterceptorServicesRegistry servicesRegistry,
                                                            HttpCarbonMessage inboundReqMsg) {
        try {
            Map<String, InterceptorService> servicesOnInterface;
            List<String> sortedServiceURIs;
            String hostName = inboundReqMsg.getHeader(HttpHeaderNames.HOST.toString());

            if (hostName != null && servicesRegistry.getServicesMapHolder(hostName) != null) {
                servicesOnInterface = servicesRegistry.getServicesByHost(hostName);
                sortedServiceURIs = servicesRegistry.getSortedServiceURIsByHost(hostName);
            } else if (servicesRegistry.getServicesMapHolder(DEFAULT_HOST) != null) {
                servicesOnInterface = servicesRegistry.getServicesByHost(DEFAULT_HOST);
                sortedServiceURIs = servicesRegistry.getSortedServiceURIsByHost(DEFAULT_HOST);
            } else {
                inboundReqMsg.setHttpStatusCode(404);
                String localAddress = inboundReqMsg.getProperty(HttpConstants.LOCAL_ADDRESS).toString();
                throw new BallerinaConnectorException("no service has registered for listener : " + localAddress);
            }

            String rawUri = (String) inboundReqMsg.getProperty(HttpConstants.TO);
            inboundReqMsg.setProperty(HttpConstants.RAW_URI, rawUri);
            Map<String, Map<String, String>> matrixParams = new HashMap<>();
            String uriWithoutMatrixParams = URIUtil.extractMatrixParams(rawUri, matrixParams);

            inboundReqMsg.setProperty(HttpConstants.TO, uriWithoutMatrixParams);
            inboundReqMsg.setProperty(HttpConstants.MATRIX_PARAMS, matrixParams);

            URI validatedUri = getValidatedURI(uriWithoutMatrixParams);

            String basePath = servicesRegistry.findTheMostSpecificBasePath(validatedUri.getRawPath(),
                                                                           servicesOnInterface, sortedServiceURIs);

            if (basePath == null) {
                inboundReqMsg.setHttpStatusCode(404);
                throw new BallerinaConnectorException("no matching service found for path : " +
                                                              validatedUri.getRawPath());
            }

            InterceptorService service = servicesOnInterface.get(basePath);
            setInboundReqProperties(inboundReqMsg, validatedUri, basePath);
            return service;
        } catch (Exception e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
    }

    private static void setInboundReqProperties(HttpCarbonMessage inboundReqMsg, URI requestUri, String basePath) {
        String subPath = URIUtil.getSubPath(requestUri.getRawPath(), basePath);
        inboundReqMsg.setProperty(HttpConstants.BASE_PATH, basePath);
        inboundReqMsg.setProperty(HttpConstants.SUB_PATH, subPath);
        inboundReqMsg.setProperty(HttpConstants.QUERY_STR, requestUri.getQuery());
        //store query params comes with request as it is
        inboundReqMsg.setProperty(HttpConstants.RAW_QUERY_STR, requestUri.getRawQuery());
    }

    public static URI getValidatedURI(String uriStr) {
        URI requestUri;
        try {
            requestUri = URI.create(uriStr);
        } catch (IllegalArgumentException e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
        return requestUri;
    }

    /**
     * This method finds the matching resource for the incoming request.
     *
     * @param servicesRegistry HTTP service registry
     * @param inboundMessage   incoming message.
     * @return matching resource.
     */
    public static HttpResource findResource(HTTPServicesRegistry servicesRegistry, HttpCarbonMessage inboundMessage) {
        String protocol = (String) inboundMessage.getProperty(HttpConstants.PROTOCOL);
        if (protocol == null) {
            throw new BallerinaConnectorException("protocol not defined in the incoming request");
        }

        try {
            // Find the Service TODO can be improved
            HttpService service = HttpDispatcher.findService(servicesRegistry, inboundMessage, false);
            if (service == null) {
                throw new BallerinaConnectorException("no Service found to handle the service request");
                // Finer details of the errors are thrown from the dispatcher itself, Ideally we shouldn't get here.
            }

            // Find the Resource
            return (HttpResource) ResourceDispatcher.findResource(service, inboundMessage);
        } catch (Exception e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
    }

    public static InterceptorResource findInterceptorResource(HTTPInterceptorServicesRegistry servicesRegistry,
                                                              HttpCarbonMessage inboundMessage) {
        String protocol = (String) inboundMessage.getProperty(HttpConstants.PROTOCOL);
        if (protocol == null) {
            throw new BallerinaConnectorException("protocol not defined in the incoming request");
        }

        try {
            // Find the Service TODO can be improved
            InterceptorService service = HttpDispatcher.findInterceptorService(servicesRegistry, inboundMessage);
            if (service == null) {
                throw new BallerinaConnectorException("no Service found to handle the service request");
                // Finer details of the errors are thrown from the dispatcher itself, Ideally we shouldn't get here.
            }

            // Find the Resource
            return (InterceptorResource) ResourceDispatcher.findResource(service, inboundMessage);
        } catch (Exception e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
    }

    public static Object[] getRemoteSignatureParameters(InterceptorService service, BObject response, BObject caller,
                                                        HttpCarbonMessage httpCarbonMessage) {
        BObject requestCtx = getRequestCtx(httpCarbonMessage);
        populatePropertiesForResponsePath(httpCarbonMessage, requestCtx);
        BError error = (BError) httpCarbonMessage.getProperty(HttpConstants.INTERCEPTOR_SERVICE_ERROR);
        RemoteMethodParamHandler paramHandler = service.getRemoteMethodParamHandler();
        int sigParamCount = paramHandler.getParamCount();
        Object[] paramFeed = new Object[sigParamCount * 2];
        for (Parameter param : paramHandler.getOtherParamList()) {
            String typeName = param.getTypeName();
            switch (typeName) {
                case HttpConstants.REQUEST_CONTEXT:
                    int index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = requestCtx;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.STRUCT_GENERIC_ERROR:
                    if (error == null) {
                        error = createError();
                    }
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = error;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.RESPONSE:
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = response;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.CALLER:
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = caller;
                    paramFeed[index] = true;
                    break;
                default:
                    break;
            }
        }
        return paramFeed;
    }

    private static void populatePropertiesForResponsePath(HttpCarbonMessage httpCarbonMessage, BObject requestCtx) {
        requestCtx.addNativeData(HttpConstants.INTERCEPTOR_SERVICE, true);
        int interceptorId = httpCarbonMessage.getProperty(HttpConstants.RESPONSE_INTERCEPTOR_INDEX) == null
                ? 0 : (int) httpCarbonMessage.getProperty(HttpConstants.RESPONSE_INTERCEPTOR_INDEX) + 1;
        requestCtx.addNativeData(HttpConstants.RESPONSE_INTERCEPTOR_INDEX, interceptorId);
        requestCtx.addNativeData(HttpConstants.INTERCEPTOR_SERVICE_TYPE,
                                 HttpConstants.RESPONSE_INTERCEPTOR);
        requestCtx.addNativeData(HttpConstants.REQUEST_CONTEXT_NEXT, false);
    }

    public static Object[] getSignatureParameters(Resource resource, HttpCarbonMessage httpCarbonMessage,
                                                  BMap<BString, Object> endpointConfig) {
        BObject inRequest = null;
        // Getting the same caller, request context and entity object to pass through interceptor services
        BObject requestCtx = getRequestCtx(httpCarbonMessage);
        populatePropertiesForRequestPath(resource, httpCarbonMessage, requestCtx);
        BObject entityObj = (BObject) httpCarbonMessage.getProperty(HttpConstants.ENTITY_OBJ);
        BError error = (BError) httpCarbonMessage.getProperty(HttpConstants.INTERCEPTOR_SERVICE_ERROR);
        BObject httpCaller = getCaller(resource, httpCarbonMessage, endpointConfig);
        ParamHandler paramHandler = resource.getParamHandler();
        int sigParamCount = resource.getBalResource().getParameterTypes().length;
        Object[] paramFeed = new Object[sigParamCount * 2];
        int pathParamCount = paramHandler.getPathParamTokenLength();
        boolean treatNilableAsOptional = resource.isTreatNilableAsOptional();
        // Path params are located initially in the signature before the other user provided signature params
        if (pathParamCount != 0) {
            // populate path params
            HttpResourceArguments resourceArgumentValues =
                    (HttpResourceArguments) httpCarbonMessage.getProperty(HttpConstants.RESOURCE_ARGS);
            updateWildcardToken(resource.getWildcardToken(), pathParamCount - 1, resourceArgumentValues.getMap());
            populatePathParams(resource, paramFeed, resourceArgumentValues, pathParamCount);
        }
        // Following was written assuming that they are validated
        for (Parameter param : paramHandler.getOtherParamList()) {
            String typeName = param.getTypeName();
            switch (typeName) {
                case HttpConstants.CALLER:
                    int index = ((NonRecurringParam) param).getIndex();
                    httpCaller.set(HttpConstants.CALLER_PRESENT_FIELD, true);
                    paramFeed[index++] = httpCaller;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.REQUEST_CONTEXT:
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = requestCtx;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.STRUCT_GENERIC_ERROR:
                    if (error == null) {
                        error = createError();
                    }
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = error;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.REQUEST:
                    if (inRequest == null) {
                        inRequest = createRequest(httpCarbonMessage, entityObj);
                    }
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = inRequest;
                    paramFeed[index] = true;
                    break;
                case HttpConstants.HEADERS:
                    if (inRequest == null) {
                        inRequest = createRequest(httpCarbonMessage, entityObj);
                    }
                    index = ((NonRecurringParam) param).getIndex();
                    paramFeed[index++] = createHeadersObject(inRequest);
                    paramFeed[index] = true;
                    break;
                case HttpConstants.QUERY_PARAM:
                    ((AllQueryParams) param).populateFeed(httpCarbonMessage, paramHandler, paramFeed,
                                                          treatNilableAsOptional);
                    break;
                case HttpConstants.HEADER_PARAM:
                    ((AllHeaderParams) param).populateFeed(httpCarbonMessage, paramFeed, treatNilableAsOptional);
                    break;
                case HttpConstants.PAYLOAD_PARAM:
                    if (inRequest == null) {
                        inRequest = createRequest(httpCarbonMessage, entityObj);
                    }
                    ((PayloadParam) param).populateFeed(inRequest, httpCarbonMessage, paramFeed);
                    break;
                default:
                    break;
            }
        }
        return paramFeed;
    }

    private static BObject getRequestCtx(HttpCarbonMessage httpCarbonMessage) {
        BObject requestCtx = (BObject) httpCarbonMessage.getProperty(HttpConstants.REQUEST_CONTEXT);
        return requestCtx != null ? requestCtx : createRequestContext(httpCarbonMessage);
    }

    private static void populatePropertiesForRequestPath(Resource resource, HttpCarbonMessage httpCarbonMessage,
                                                         BObject requestCtx) {
        if (resource instanceof InterceptorResource) {
            requestCtx.addNativeData(HttpConstants.INTERCEPTOR_SERVICE, true);
        } else {
            requestCtx.addNativeData(HttpConstants.INTERCEPTOR_SERVICE, false);
        }
        int interceptorId = httpCarbonMessage.getProperty(HttpConstants.REQUEST_INTERCEPTOR_INDEX) == null
                ? 0 : (int) httpCarbonMessage.getProperty(HttpConstants.REQUEST_INTERCEPTOR_INDEX) - 1;
        requestCtx.addNativeData(HttpConstants.REQUEST_INTERCEPTOR_INDEX, interceptorId);
        requestCtx.addNativeData(HttpConstants.REQUEST_CONTEXT_NEXT, false);
        requestCtx.addNativeData(HttpConstants.INTERCEPTOR_SERVICE_TYPE,
                                 HttpConstants.REQUEST_INTERCEPTOR);
    }

    private static BObject createRequest(HttpCarbonMessage httpCarbonMessage, BObject entityObj) {
        BObject inRequest = ValueCreatorUtils.createRequestObject();
        // Reuse the entity object in case it is consumed by an interceptor
        BObject inRequestEntity = entityObj == null ? ValueCreatorUtils.createEntityObject() : entityObj;
        HttpUtil.populateInboundRequest(inRequest, inRequestEntity, httpCarbonMessage);
        return inRequest;
    }

    static BObject getCaller(Resource resource, HttpCarbonMessage httpCarbonMessage,
                             BMap<BString, Object> endpointConfig) {
        final BObject httpCaller = httpCarbonMessage.getProperty(HttpConstants.CALLER) == null ?
                ValueCreatorUtils.createCallerObject(httpCarbonMessage) :
                (BObject) httpCarbonMessage.getProperty(HttpConstants.CALLER);
        HttpUtil.enrichHttpCallerWithConnectionInfo(httpCaller, httpCarbonMessage, resource, endpointConfig);
        HttpUtil.enrichHttpCallerWithNativeData(httpCaller, httpCarbonMessage, endpointConfig);
        httpCarbonMessage.setProperty(HttpConstants.CALLER, httpCaller);
        return httpCaller;
    }

    static BObject createRequestContext(HttpCarbonMessage httpCarbonMessage) {
        BObject requestContext = ValueCreatorUtils.createRequestContextObject();
        BArray interceptors = httpCarbonMessage.getProperty(HttpConstants.INTERCEPTORS) instanceof BArray ?
                              (BArray) httpCarbonMessage.getProperty(HttpConstants.INTERCEPTORS) : null;
        requestContext.addNativeData(HttpConstants.INTERCEPTORS, interceptors);
        requestContext.addNativeData(HttpConstants.TARGET_SERVICE, httpCarbonMessage.getProperty(
                                     HttpConstants.TARGET_SERVICE));
        requestContext.addNativeData(HttpConstants.REQUEST_CONTEXT_NEXT, false);
        httpCarbonMessage.setProperty(HttpConstants.REQUEST_CONTEXT, requestContext);
        return requestContext;
    }

    static BError createError() {
        return ErrorCreator.createError(StringUtils.fromString("new error"));
    }

    private static Object createHeadersObject(BObject inRequest) {
        BObject headers = ValueCreatorUtils.createHeadersObject();
        headers.set(HttpConstants.HEADER_REQUEST_FIELD, inRequest);
        return headers;
    }

    private static void populatePathParams(Resource resource, Object[] paramFeed,
                                           HttpResourceArguments resourceArgumentValues, int pathParamCount) {

        String[] pathParamTokens = Arrays.copyOfRange(resource.getBalResource().getParamNames(), 0, pathParamCount);
        int actualSignatureParamIndex = 0;
        for (String paramName : pathParamTokens) {
            String argumentValue = resourceArgumentValues.getMap().get(paramName).get(actualSignatureParamIndex);
            try {
                argumentValue = URLDecoder.decode(argumentValue, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // we can simply ignore and send the value to application and let the
                // application deal with the value.
            }
            int paramIndex = actualSignatureParamIndex * 2;
            Type pathParamType = resource.getBalResource().getParameterTypes()[actualSignatureParamIndex++];

            try {
                if (pathParamType.getTag() == ARRAY_TAG) {
                    int elementTypeTag = ((ArrayType) pathParamType).getElementType().getTag();
                    String[] segments = argumentValue.substring(1).split(HttpConstants.SINGLE_SLASH);
                    paramFeed[paramIndex++] = castParamArray(elementTypeTag, segments);
                } else {
                    paramFeed[paramIndex++] = castParam(pathParamType.getTag(), argumentValue);
                }
                paramFeed[paramIndex] = true;
            } catch (Exception ex) {
                throw new BallerinaConnectorException("Error in casting path param : " + ex.getMessage());
            }
        }
    }

    private static void updateWildcardToken(String wildcardToken, int wildCardIndex,
                                            Map<String, Map<Integer, String>> arguments) {
        if (wildcardToken == null) {
            return;
        }
        String wildcardPathSegment = arguments.get(HttpConstants.EXTRA_PATH_INFO).get(EXTRA_PATH_INDEX);
        if (arguments.containsKey(wildcardToken)) {
            Map<Integer, String> indexValueMap = arguments.get(wildcardToken);
            indexValueMap.put(wildCardIndex, wildcardPathSegment);
        } else {
            arguments.put(wildcardToken, Collections.singletonMap(wildCardIndex, wildcardPathSegment));
        }
    }


    public static boolean shouldDiffer(Resource resource) {
        return (resource != null && resource.getParamHandler().isPayloadBindingRequired());
    }

    private HttpDispatcher() {
    }
}
