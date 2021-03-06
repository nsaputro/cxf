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

package org.apache.cxf.ws.security.sts.provider;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.util.JAXBSource;
import javax.xml.namespace.QName;
import javax.xml.soap.Detail;
import javax.xml.soap.DetailEntry;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;
import javax.xml.soap.SOAPFault;
import javax.xml.transform.Source;
import javax.xml.ws.Provider;
import javax.xml.ws.Service;
import javax.xml.ws.ServiceMode;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.soap.SOAPFaultException;

import org.apache.cxf.binding.soap.saaj.SAAJFactoryResolver;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxb.JAXBContextCache;
import org.apache.cxf.jaxb.JAXBContextCache.CachedContextAndSchemas;
import org.apache.cxf.ws.security.sts.provider.model.ObjectFactory;
import org.apache.cxf.ws.security.sts.provider.model.RequestSecurityTokenCollectionType;
import org.apache.cxf.ws.security.sts.provider.model.RequestSecurityTokenResponseCollectionType;
import org.apache.cxf.ws.security.sts.provider.model.RequestSecurityTokenResponseType;
import org.apache.cxf.ws.security.sts.provider.model.RequestSecurityTokenType;
import org.apache.cxf.ws.security.sts.provider.operation.CancelOperation;
import org.apache.cxf.ws.security.sts.provider.operation.IssueOperation;
import org.apache.cxf.ws.security.sts.provider.operation.KeyExchangeTokenOperation;
import org.apache.cxf.ws.security.sts.provider.operation.RenewOperation;
import org.apache.cxf.ws.security.sts.provider.operation.RequestCollectionOperation;
import org.apache.cxf.ws.security.sts.provider.operation.ValidateOperation;

@ServiceMode(value = Service.Mode.PAYLOAD)
public class SecurityTokenServiceProvider implements Provider<Source> {

    private static final String WSTRUST_13_NAMESPACE = "http://docs.oasis-open.org/ws-sx/ws-trust/200512";
    private static final String WSTRUST_REQUESTTYPE_ELEMENTNAME = "RequestType";
    private static final String WSTRUST_REQUESTTYPE_ISSUE = WSTRUST_13_NAMESPACE
            + "/Issue";
    private static final String WSTRUST_REQUESTTYPE_CANCEL = WSTRUST_13_NAMESPACE
            + "/Cancel";
    private static final String WSTRUST_REQUESTTYPE_RENEW = WSTRUST_13_NAMESPACE
            + "/Renew";
    private static final String WSTRUST_REQUESTTYPE_VALIDATE = WSTRUST_13_NAMESPACE
            + "/Validate";
    private static final String WSTRUST_REQUESTTYPE_REQUESTCOLLECTION = WSTRUST_13_NAMESPACE
            + "/RequestCollection";
    private static final String WSTRUST_REQUESTTYPE_KEYEXCHANGETOKEN = WSTRUST_13_NAMESPACE
            + "/KeyExchangeToken";
    
    private static final Map<String, Method> OPERATION_METHODS = new HashMap<String, Method>();
    static {
        try {
            Method m = IssueOperation.class.getDeclaredMethod("issue", 
                                                              RequestSecurityTokenType.class, 
                                                              WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_ISSUE, m);
            
            m = IssueOperation.class.getDeclaredMethod("cancel", 
                                                       RequestSecurityTokenType.class, 
                                                       WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_CANCEL, m);
            
            m = IssueOperation.class.getDeclaredMethod("renew", 
                                                       RequestSecurityTokenType.class, 
                                                       WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_RENEW, m);
            
            m = IssueOperation.class.getDeclaredMethod("validate", 
                                                       RequestSecurityTokenType.class, 
                                                       WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_VALIDATE, m);
            
            m = IssueOperation.class.getDeclaredMethod("keyExchangeToken", 
                                                       RequestSecurityTokenType.class, 
                                                       WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_KEYEXCHANGETOKEN, m);
            
            m = IssueOperation.class.getDeclaredMethod("requestCollection", 
                                                       RequestSecurityTokenCollectionType.class, 
                                                       WebServiceContext.class);
            OPERATION_METHODS.put(WSTRUST_REQUESTTYPE_REQUESTCOLLECTION, m);
        } catch (Exception ex) {
            //Should not happen as nothing will work.  All operations will
            //end up throwing UnsupportedOpertation
        }
    }
    

    
    protected JAXBContext jaxbContext;
    protected Set<Class<?>> jaxbContextClasses;
    protected SOAPFactory soapFactory;
    
    private CancelOperation cancelOperation;
    private IssueOperation issueOperation;
    private KeyExchangeTokenOperation keyExchangeTokenOperation;
    private RenewOperation renewOperation;
    private RequestCollectionOperation requestCollectionOperation;
    private ValidateOperation validateOperation;
    private Map<String, Object> operationMap = new HashMap<String, Object>();

    @Resource
    private WebServiceContext context;

    public SecurityTokenServiceProvider() throws Exception {
        CachedContextAndSchemas cache = JAXBContextCache.getCachedContextAndSchemas(ObjectFactory.class);
        jaxbContext = cache.getContext();
        jaxbContextClasses = cache.getClasses();
        soapFactory = SAAJFactoryResolver.createSOAPFactory(null);
    }
    
    public void setCancelOperation(CancelOperation cancelOperation) {
        this.cancelOperation = cancelOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_CANCEL, cancelOperation);
    }

    public void setIssueOperation(IssueOperation issueOperation) {
        this.issueOperation = issueOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_ISSUE, issueOperation);
    }

    public void setKeyExchangeTokenOperation(
            KeyExchangeTokenOperation keyExchangeTokenOperation) {
        this.keyExchangeTokenOperation = keyExchangeTokenOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_KEYEXCHANGETOKEN,
                keyExchangeTokenOperation);
    }

    public void setRenewOperation(RenewOperation renewOperation) {
        this.renewOperation = renewOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_RENEW, renewOperation);
    }

    public void setRequestCollectionOperation(
            RequestCollectionOperation requestCollectionOperation) {
        this.requestCollectionOperation = requestCollectionOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_REQUESTCOLLECTION,
                requestCollectionOperation);
    }

    public void setValidateOperation(ValidateOperation validateOperation) {
        this.validateOperation = validateOperation;
        operationMap.put(WSTRUST_REQUESTTYPE_VALIDATE, validateOperation);
    }

    

    public Source invoke(Source request) {
        Source response = null;
        try {
            Object obj = convertToJAXBObject(request);
            Object operationImpl = null;
            Method method = null;
            if (obj instanceof RequestSecurityTokenCollectionType) {
                operationImpl = operationMap.get(WSTRUST_REQUESTTYPE_REQUESTCOLLECTION);
                method = OPERATION_METHODS.get(WSTRUST_REQUESTTYPE_REQUESTCOLLECTION);
            } else {
                RequestSecurityTokenType rst = (RequestSecurityTokenType)obj;
                List<?> objectList = rst.getAny();
                for (Object o : objectList) {
                    if (o instanceof JAXBElement) {
                        QName qname = ((JAXBElement<?>) o).getName();
                        if (qname.equals(new QName(WSTRUST_13_NAMESPACE,
                                WSTRUST_REQUESTTYPE_ELEMENTNAME))) {
                            String val = ((JAXBElement<?>) o).getValue().toString();
                            operationImpl = operationMap.get(val);
                            method =  OPERATION_METHODS.get(val);
                            break;
                        }
                    }
                }
            }

            if (operationImpl == null || method == null) {
                throw new Exception(
                        "Implementation for this operation not found.");
            }
            obj = method.invoke(operationImpl, obj, context);
            if (obj == null) {
                throw new Exception("Error in implementation class.");
            }
            if (obj instanceof RequestSecurityTokenResponseCollectionType) {
                RequestSecurityTokenResponseCollectionType tokenResponse =
                    (RequestSecurityTokenResponseCollectionType)obj;
                response = new JAXBSource(jaxbContext, 
                                          new ObjectFactory()
                                          .createRequestSecurityTokenResponseCollection(tokenResponse));
            } else {
                RequestSecurityTokenResponseType tokenResponse = 
                    (RequestSecurityTokenResponseType)obj;
                response = new JAXBSource(jaxbContext, 
                                          new ObjectFactory()
                                          .createRequestSecurityTokenResponse(tokenResponse));
            }

        } catch (Exception e) {
            try {
                SOAPFault fault = soapFactory.createFault();
                if (e.getMessage() == null) {
                    fault.setFaultString(e.getCause().getMessage());
                } else {
                    fault.setFaultString(e.getMessage());
                }
                Detail detail = fault.addDetail();
                detail = fault.getDetail();
                QName qName = new QName(WSTRUST_13_NAMESPACE, "Fault", "ns");
                DetailEntry de = detail.addDetailEntry(qName);
                qName = new QName(WSTRUST_13_NAMESPACE, "ErrorCode", "ns");
                SOAPElement errorElement = de.addChildElement(qName);
                StackTraceElement[] ste = e.getStackTrace();
                errorElement.setTextContent(ste[0].toString());
                throw new SOAPFaultException(fault);
            } catch (SOAPException e1) {
                throw new Fault(e1);
            }

        }

        return response;
    }

    private Object convertToJAXBObject(Source source) throws Exception {
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        JAXBElement<?> jaxbElement = (JAXBElement<?>) unmarshaller
                .unmarshal(source);
        return jaxbElement.getValue();
    }

    public CancelOperation getCancelOperation() {
        return cancelOperation;
    }

    public IssueOperation getIssueOperation() {
        return issueOperation;
    }

    public KeyExchangeTokenOperation getKeyExchangeTokenOperation() {
        return keyExchangeTokenOperation;
    }

    public RenewOperation getRenewOperation() {
        return renewOperation;
    }

    public RequestCollectionOperation getRequestCollectionOperation() {
        return requestCollectionOperation;
    }

    public ValidateOperation getValidateOperation() {
        return validateOperation;
    }

}
