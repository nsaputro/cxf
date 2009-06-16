/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.jaxrs.interceptor;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.XMLEvent;

import org.apache.cxf.common.i18n.BundleUtils;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.interceptor.AbstractOutDatabindingInterceptor;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.jaxrs.ext.ResponseHandler;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.jaxrs.model.OperationResourceInfo;
import org.apache.cxf.jaxrs.model.ProviderInfo;
import org.apache.cxf.jaxrs.provider.ProviderFactory;
import org.apache.cxf.jaxrs.utils.InjectionUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageContentsList;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.staxutils.CachingXmlEventWriter;
import org.apache.cxf.staxutils.StaxUtils;

public class JAXRSOutInterceptor extends AbstractOutDatabindingInterceptor {
    private static final Logger LOG = LogUtils.getL7dLogger(JAXRSOutInterceptor.class);
    private static final ResourceBundle BUNDLE = BundleUtils.getBundle(JAXRSOutInterceptor.class);

    public JAXRSOutInterceptor() {
        super(Phase.MARSHAL);
    }

    public void handleMessage(Message message) {
        
        try {
            processResponse(message);
        } finally {
            ProviderFactory.getInstance(message).clearThreadLocalProxies();
            ClassResourceInfo cri =
                (ClassResourceInfo)message.getExchange().get(JAXRSInInterceptor.ROOT_RESOURCE_CLASS);
            if (cri != null) {
                cri.clearThreadLocalProxies();
            }
        }
            

    }
    
    private void processResponse(Message message) {
        
        MessageContentsList objs = MessageContentsList.getContentsList(message);
        if (objs == null || objs.size() == 0) {
            return;
        }
        
        if (objs.get(0) != null) {
            Object responseObj = objs.get(0);
            Response response = null;
            if (objs.get(0) instanceof Response) {
                response = (Response)responseObj;
            } else {    
                response = Response.ok(responseObj).build();
            }
            
            Exchange exchange = message.getExchange();
            OperationResourceInfo ori = (OperationResourceInfo)exchange.get(OperationResourceInfo.class
                .getName());

            List<ProviderInfo<ResponseHandler>> handlers = 
                ProviderFactory.getInstance(message).getResponseHandlers();
            for (ProviderInfo<ResponseHandler> rh : handlers) {
                Response r = rh.getProvider().handleResponse(message, ori, response);
                if (r != null) {
                    response = r;
                }
            }
            
            serializeMessage(message, response, ori, true);        
            
        } else {
            message.put(Message.RESPONSE_CODE, 204);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void serializeMessage(Message message, 
                                  Response response, 
                                  OperationResourceInfo ori,
                                  boolean firstTry) {
        message.put(Message.RESPONSE_CODE, response.getStatus());
        Map<String, List<String>> theHeaders = 
            (Map<String, List<String>>)message.get(Message.PROTOCOL_HEADERS);
        if (firstTry && theHeaders != null) {
            // some headers might've been setup by custom cxf interceptors
            theHeaders.putAll((Map)response.getMetadata());
        } else {
            message.put(Message.PROTOCOL_HEADERS, response.getMetadata());
        }
        
        Object responseObj = response.getEntity();
        if (responseObj == null) {
            return;
        }
        
        Class targetType = responseObj.getClass();
        List<MediaType> availableContentTypes = computeAvailableContentTypes(message, response);  
        
        Method invoked = null;
        if (firstTry) {
            invoked = ori == null ? null : ori.getMethodToInvoke();
        }
        
        MessageBodyWriter writer = null;
        MediaType responseType = null;
        for (MediaType type : availableContentTypes) { 
            writer = ProviderFactory.getInstance(message)
                .createMessageBodyWriter(targetType, 
                      invoked != null ? invoked.getGenericReturnType() : null, 
                      invoked != null ? invoked.getAnnotations() : new Annotation[]{}, 
                      type,
                      message);
            
            if (writer != null) {
                responseType = type;
                break;
            }
        }
    
        OutputStream outOriginal = message.getContent(OutputStream.class);
        if (writer == null) {
            message.put(Message.RESPONSE_CODE, 500);
            writeResponseErrorMessage(outOriginal, "NO_MSG_WRITER", targetType.getSimpleName());
            return;
        }
        boolean enabled = checkBufferingMode(message, writer, firstTry);
        try {
            
            responseType = checkFinalContentType(responseType);
            LOG.fine("Response content type is: " + responseType.toString());
            message.put(Message.CONTENT_TYPE, responseType.toString());
            
            LOG.fine("Response EntityProvider is: " + writer.getClass().getName());
            try {
                writer.writeTo(responseObj, targetType, 
                               invoked != null ? invoked.getGenericReturnType() : null, 
                               invoked != null ? invoked.getAnnotations() : new Annotation[]{}, 
                               responseType, 
                               response.getMetadata(), 
                               message.getContent(OutputStream.class));
                checkCachedStream(message, outOriginal, enabled);
            } finally {
                if (enabled) {
                    message.setContent(OutputStream.class, outOriginal);
                    message.put(XMLStreamWriter.class.getName(), null);
                }
            }
            
        } catch (IOException ex) {
            handleWriteException(message, response, ori, ex, responseObj, firstTry);
        } catch (Throwable ex) {
            handleWriteException(message, response, ori, ex, responseObj, firstTry);
        }
    }
    
    private boolean checkBufferingMode(Message m, MessageBodyWriter w, boolean firstTry) {
        if (!firstTry) {
            return false;
        }
        Object outBuf = m.getContextualProperty(OUT_BUFFERING);
        boolean enabled = Boolean.TRUE.equals(outBuf) || "true".equals(outBuf);
        if (!enabled && outBuf == null) {
            enabled = InjectionUtils.invokeBooleanGetter(w, "getEnableBuffering");
        }
        if (enabled) {
            boolean streamingOn = 
                "org.apache.cxf.jaxrs.provider.JAXBElementProvider".equals(w.getClass().getName())
                && InjectionUtils.invokeBooleanGetter(w, "getEnableStreaming");
            if (streamingOn) {
                m.put(XMLStreamWriter.class.getName(), new CachingXmlEventWriter());
            } else {
                m.setContent(OutputStream.class, new CachedOutputStream());
            }
        }
        return enabled;
    }
    
    private void checkCachedStream(Message m, OutputStream osOriginal, boolean enabled) throws Exception {
        if (!enabled) {
            return;
        }
        XMLStreamWriter writer = (XMLStreamWriter)m.get(XMLStreamWriter.class.getName());
        if (writer instanceof CachingXmlEventWriter) {
            CachingXmlEventWriter cache = (CachingXmlEventWriter)writer;
            if (cache.getEvents().size() != 0) {
                XMLStreamWriter origWriter = StaxUtils.createXMLStreamWriter(osOriginal);
                for (XMLEvent event : cache.getEvents()) {
                    StaxUtils.writeEvent(event, origWriter);
                }
            }
            m.put(XMLStreamWriter.class.getName(), null);
            return;
        }
        OutputStream os = m.getContent(OutputStream.class);
        if (os != osOriginal && os instanceof CachedOutputStream) {
            CachedOutputStream cos = (CachedOutputStream)os;
            if (cos.size() != 0) {
                cos.writeCacheTo(osOriginal);
            }
        }
    }
    
    private void handleWriteException(Message message, 
                                         Response response, 
                                         OperationResourceInfo ori,
                                         Throwable ex,
                                         Object responseObj,
                                         boolean firstTry) {
        OutputStream out = message.getContent(OutputStream.class);
        if (firstTry) {
            Response excResponse = JAXRSUtils.convertFaultToResponse(ex, message);
            if (excResponse != null) {
                serializeMessage(message, excResponse, ori, false);
            }
        } else {
            message.put(Message.RESPONSE_CODE, 500);
            writeResponseErrorMessage(out, "SERIALIZE_ERROR", 
                                      responseObj.getClass().getSimpleName()); 
        }    
    }
    
    
    private void writeResponseErrorMessage(OutputStream out, String errorString, 
                                           String parameter) {
        try {
            org.apache.cxf.common.i18n.Message message = 
                new org.apache.cxf.common.i18n.Message(errorString,
                                                   BUNDLE,
                                                   parameter);
            LOG.warning(message.toString());
            out.write(message.toString().getBytes("UTF-8"));
        } catch (IOException another) {
            // ignore
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<MediaType> computeAvailableContentTypes(Message message, Response response) {
        
        Object contentType = 
            response.getMetadata().getFirst(HttpHeaders.CONTENT_TYPE);
        Exchange exchange = message.getExchange();
        List<MediaType> produceTypes = null;
        OperationResourceInfo operation = exchange.get(OperationResourceInfo.class);
        if (contentType != null) {
            return Collections.singletonList(MediaType.valueOf(contentType.toString()));
        } else if (operation != null) {
            produceTypes = operation.getProduceTypes();
        } else {
            produceTypes = Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        }
        List<MediaType> acceptContentTypes = 
            (List<MediaType>)exchange.get(Message.ACCEPT_CONTENT_TYPE);
        if (acceptContentTypes == null) {
            acceptContentTypes = Collections.singletonList(MediaType.valueOf("*/*"));
        }        
        return JAXRSUtils.intersectMimeTypes(acceptContentTypes, produceTypes);
        
    }
    
    private MediaType checkFinalContentType(MediaType mt) {
        if (mt.isWildcardType() || mt.isWildcardSubtype()) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        } else if (mt.getParameters().containsKey("q")) {
            StringBuilder sb = new StringBuilder();
            sb.append(mt.getType()).append('/').append(mt.getSubtype());
            if (mt.getParameters().size() > 1) {
                for (String key : mt.getParameters().keySet()) {
                    if (!"q".equals(key)) {
                        sb.append(';').append(key).append('=').append(mt.getParameters().get(key));
                    }
                }
            }
            return MediaType.valueOf(sb.toString());
        } else {
            return mt;
        }
        
    }
}