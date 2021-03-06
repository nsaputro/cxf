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

package org.apache.cxf.ws.addressing;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.wsdl.extensions.ExtensibilityElement;
import javax.xml.namespace.QName;
import javax.xml.ws.WebFault;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapBindingConstants;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.interceptor.SoapActionInInterceptor;
import org.apache.cxf.binding.soap.model.SoapOperationInfo;
import org.apache.cxf.common.i18n.BundleUtils;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.ClientLifeCycleListener;
import org.apache.cxf.endpoint.ClientLifeCycleManager;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.feature.AbstractFeature;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.OneWayProcessorInterceptor;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.FaultMode;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.service.model.FaultInfo;
import org.apache.cxf.service.model.MessageInfo;
import org.apache.cxf.service.model.OperationInfo;
import org.apache.cxf.service.model.UnwrappedOperationInfo;
import org.apache.cxf.transport.Conduit;
import org.apache.cxf.transport.Destination;
import org.apache.cxf.transport.DestinationFactory;
import org.apache.cxf.transport.DestinationFactoryManager;
import org.apache.cxf.transport.MessageObserver;
import org.apache.cxf.transport.Observable;
import org.apache.cxf.ws.addressing.VersionTransformer.Names200408;
import org.apache.cxf.ws.addressing.policy.MetadataConstants;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.wsdl.EndpointReferenceUtils;


/**
 * Logical Handler responsible for aggregating the Message Addressing 
 * Properties for outgoing messages.
 */
public class MAPAggregator extends AbstractPhaseInterceptor<Message> {
    public static final String USING_ADDRESSING = MAPAggregator.class.getName() + ".usingAddressing";
    public static final String ADDRESSING_DISABLED = MAPAggregator.class.getName() + ".addressingDisabled";
    public static final String DECOUPLED_DESTINATION = MAPAggregator.class.getName() 
        + ".decoupledDestination";
    public static final String ACTION_VERIFIED = MAPAggregator.class.getName() + ".actionVerified";
    
    private static final Logger LOG = 
        LogUtils.getL7dLogger(MAPAggregator.class);
    private static final ResourceBundle BUNDLE = LOG.getResourceBundle();

    private static final ClientLifeCycleListener DECOUPLED_DEST_CLEANER = new ClientLifeCycleListener() {
        public void clientCreated(Client client) {
            //ignore
        }

        public void clientDestroyed(Client client) {
            Destination dest = client.getEndpoint().getEndpointInfo()
                .getProperty(DECOUPLED_DESTINATION, Destination.class);
            if (dest != null) {
                dest.setMessageObserver(null);
                dest.shutdown();
            }
        }
        
    };

    /**
     * The cache to use for enforcing uniqueness.  Defaults to {@link DefaultMessageIdCache}.
     */
    private MessageIdCache messageIdCache = new DefaultMessageIdCache();
    
    private boolean usingAddressingAdvisory = true;
    private boolean addressingRequired;

    private boolean allowDuplicates = true;
    
    private String addressingResponses = "ALL";
    
    /**
     * Constructor.
     */
    public MAPAggregator() {
        super(Phase.PRE_LOGICAL);
        addBefore(OneWayProcessorInterceptor.class.getName());
    }
    
    /**
     * Indicates if duplicate messageIDs are allowed.
     * @return true if duplicate messageIDs are allowed
     */
    public boolean allowDuplicates() {
        return allowDuplicates;
    }

    /**
     * Allows/disallows duplicate messageIdDs.  
     * @param ad whether duplicate messageIDs are allowed
     */
    public void setAllowDuplicates(boolean ad) {
        allowDuplicates = ad;
    }

    /**
     * Whether the presence of the <wsaw:UsingAddressing> element
     * in the WSDL is purely advisory, i.e. its absence doesn't prevent
     * the encoding of WS-A headers.
     *
     * @return true if the presence of the <wsaw:UsingAddressing> element is 
     * advisory
     */
    public boolean isUsingAddressingAdvisory() {
        return usingAddressingAdvisory;
    }

    /**
     * Controls whether the presence of the <wsaw:UsingAddressing> element
     * in the WSDL is purely advisory, i.e. its absence doesn't prevent
     * the encoding of WS-A headers.
     *
     * @param advisory true if the presence of the <wsaw:UsingAddressing>
     * element is to be advisory
     */
    public void setUsingAddressingAdvisory(boolean advisory) {
        usingAddressingAdvisory = advisory;
    }
    
    /**
     * Whether the use of addressing is completely required for this endpoint
     *
     * @return true if addressing is required
     */
    public boolean isAddressingRequired() {
        return addressingRequired;
    }
    /**
     * Sets whether the use of addressing is completely required for this endpoint
     *
     */
    public void setAddressingRequired(boolean required) {
        addressingRequired = required;
    }
    
    /**
     * Sets Addresing Response 
     *
     */
    public void setAddressingResponses(String responses) {
        addressingResponses = responses;
    }
    
    /**
     * Returns the cache used to enforce duplicate message IDs when
     * {@link #allowDuplicates()} returns {@code false}.
     *
     * @return the cache used to enforce duplicate message IDs
     */
    public MessageIdCache getMessageIdCache() {
        return messageIdCache;
    }

    /**
     * Sets the cache used to enforce duplicate message IDs when
     * {@link #allowDuplicates()} returns {@code false}.
     *
     * @param messageIdCache the cache to use
     *
     * @throws NullPointerException if {@code messageIdCache} is {@code null}
     */
    public void setMessageIdCache(MessageIdCache messageIdCache) {
        if (messageIdCache == null) {
            throw new NullPointerException("messageIdCache cannot be null.");
        }
        this.messageIdCache = messageIdCache;
    }

    /**
     * Invoked for normal processing of inbound and outbound messages.
     *
     * @param message the current message
     */
    public void handleMessage(Message message) {
        if (!MessageUtils.getContextualBoolean(message, ADDRESSING_DISABLED, false)) {
            mediate(message, ContextUtils.isFault(message));
        } else {
            //addressing is completely disabled manually, we need to assert the
            //assertions as the user is in control of those
            AssertionInfoMap aim = message.get(AssertionInfoMap.class);
            if (null == aim) {
                return;
            }
            QName[] types = new QName[] {
                MetadataConstants.ADDRESSING_ASSERTION_QNAME,
                MetadataConstants.USING_ADDRESSING_2004_QNAME,
                MetadataConstants.USING_ADDRESSING_2005_QNAME,
                MetadataConstants.USING_ADDRESSING_2006_QNAME,
                MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME,
                MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME,
                MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME_0705,
                MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME_0705
            };
            for (QName type : types) {
                assertAssertion(aim, type);
            }
        }
    }

    /**
     * Invoked when unwinding normal interceptor chain when a fault occurred.
     *
     * @param message the current message
     */
    public void  handleFault(Message message) {
        message.put(MAPAggregator.class.getName(), this);
    }

    /**
     * Determine if addressing is being used
     *
     * @param message the current message
     * @pre message is outbound
     */
    private boolean usingAddressing(Message message) {
        boolean ret = true;
        if (ContextUtils.isRequestor(message)) {
            if (hasUsingAddressing(message) 
                || hasAddressingAssertion(message)
                || hasUsingAddressingAssertion(message)) {
                return true;
            }
            if (!usingAddressingAdvisory
                || !WSAContextUtils.retrieveUsingAddressing(message)) {
                ret = false;
            }
        } else {
            ret = getMAPs(message, false, false) != null;
        }
        return ret;
    }
      
   /**
    * Determine if the use of addressing is indicated by the presence of a
    * the usingAddressing attribute.
    *
    * @param message the current message
    * @pre message is outbound
    * @pre requestor role
    */
    private boolean hasUsingAddressing(Message message) {
        boolean ret = false;
        Endpoint endpoint = message.getExchange().get(Endpoint.class);
        if (null != endpoint) {
            Boolean b = (Boolean)endpoint.get(USING_ADDRESSING);
            if (null == b) {
                EndpointInfo endpointInfo = endpoint.getEndpointInfo();
                List<ExtensibilityElement> endpointExts = endpointInfo != null ? endpointInfo
                    .getExtensors(ExtensibilityElement.class) : null;
                List<ExtensibilityElement> bindingExts = endpointInfo != null
                    && endpointInfo.getBinding() != null ? endpointInfo
                    .getBinding().getExtensors(ExtensibilityElement.class) : null;
                List<ExtensibilityElement> serviceExts = endpointInfo != null
                    && endpointInfo.getService() != null ? endpointInfo
                    .getService().getExtensors(ExtensibilityElement.class) : null;
                ret = hasUsingAddressing(endpointExts) || hasUsingAddressing(bindingExts)
                             || hasUsingAddressing(serviceExts);
                b = ret ? Boolean.TRUE : Boolean.FALSE;
                endpoint.put(USING_ADDRESSING, b);
            } else {
                ret = b.booleanValue();
            }
        }    
        return ret;
    }
    
    /**
     * Determine if the use of addressing is indicated by an Addressing assertion in the
     * alternative chosen for the current message.
     * 
     * @param message the current message
     * @pre message is outbound
     * @pre requestor role
     */
    private boolean hasAddressingAssertion(Message message) {
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (null == aim) {
            return false;            
        }
        if (null == aim.get(MetadataConstants.ADDRESSING_ASSERTION_QNAME)) {
            return false;
        }
        // no need to analyse the content of the Addressing assertion here
        
        return true;
    }
    
    /**
     * Determine if the use of addressing is indicated by a UsingAddressing in the
     * alternative chosen for the current message.
     * 
     * @param message the current message
     * @pre message is outbound
     * @pre requestor role
     */
    private boolean hasUsingAddressingAssertion(Message message) {
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (null == aim) {
            return false;
            
        }
        if (null != aim.get(MetadataConstants.USING_ADDRESSING_2004_QNAME)) {
            return true;
        }
        if (null != aim.get(MetadataConstants.USING_ADDRESSING_2005_QNAME)) {
            return true;
        }
        if (null != aim.get(MetadataConstants.USING_ADDRESSING_2006_QNAME)) {
            return true;
        } 
        return false;
    }

   
    private WSAddressingFeature getWSAddressingFeature(Message message) {
        if (message.getExchange() != null && message.getExchange().getEndpoint() != null) {
            Endpoint endpoint = message.getExchange().getEndpoint();
            if (endpoint.getActiveFeatures() != null) {
                for (AbstractFeature feature : endpoint.getActiveFeatures()) {
                    if (feature instanceof WSAddressingFeature) {
                        return (WSAddressingFeature)feature;
                    }
                }
            }
        }
        return null;
    }
    /**
     * If the isRequestor(message) == true and isAddressRequired() == false
     * Assert all the wsa related assertion to true
     * 
     * @param message the current message
     */
    private void assertAddressing(Message message) {
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (null == aim) {
            return;
        }
        QName[] types = new QName[] {
            MetadataConstants.ADDRESSING_ASSERTION_QNAME, MetadataConstants.USING_ADDRESSING_2004_QNAME,
            MetadataConstants.USING_ADDRESSING_2005_QNAME, MetadataConstants.USING_ADDRESSING_2006_QNAME
        };

        for (QName type : types) {
            assertAssertion(aim, type);
            if (type.equals(MetadataConstants.ADDRESSING_ASSERTION_QNAME)) {
                assertAssertion(aim, MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME);
                assertAssertion(aim, MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME);
            } else if (type.equals(MetadataConstants.ADDRESSING_ASSERTION_QNAME_0705)) {
                assertAssertion(aim, MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME_0705);
                assertAssertion(aim, MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME_0705);
            }
        }
    }

    /**
     * Asserts all Addressing assertions for the current message, regardless their nested 
     * Policies.
     * @param message the current message
     */
    private void assertAddressing(Message message, 
                                  EndpointReferenceType replyTo, 
                                  EndpointReferenceType faultTo) {
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (null == aim) {
            return;
        }
        if (faultTo == null) {
            faultTo = replyTo;
        }
        boolean anonReply = ContextUtils.isGenericAddress(replyTo);
        boolean anonFault = ContextUtils.isGenericAddress(faultTo);
        boolean onlyAnonymous = anonReply && anonFault;
        boolean hasAnonymous = anonReply || anonFault;
        
        QName[] types = new QName[] {
            MetadataConstants.ADDRESSING_ASSERTION_QNAME,
            MetadataConstants.USING_ADDRESSING_2004_QNAME,
            MetadataConstants.USING_ADDRESSING_2005_QNAME,
            MetadataConstants.USING_ADDRESSING_2006_QNAME
        };
        
        for (QName type : types) {
            assertAssertion(aim, type);
            if (type.equals(MetadataConstants.ADDRESSING_ASSERTION_QNAME)) {
                if (onlyAnonymous) {
                    assertAssertion(aim, MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME);
                } else if (!hasAnonymous) {
                    assertAssertion(aim, MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME);
                }        
            } else if (type.equals(MetadataConstants.ADDRESSING_ASSERTION_QNAME_0705)) {
                if (onlyAnonymous) {
                    assertAssertion(aim, MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME_0705);
                } else if (!hasAnonymous) {
                    assertAssertion(aim, MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME_0705);
                }        
            }
        }
        if (!MessageUtils.isRequestor(message) && !MessageUtils.isOutbound(message)) {
            //need to throw an appropriate fault for these
            Collection<AssertionInfo> aicNonAnon
                = aim.getAssertionInfo(MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME);
            Collection<AssertionInfo> aicNonAnon2
                = aim.getAssertionInfo(MetadataConstants.NON_ANON_RESPONSES_ASSERTION_QNAME_0705);
            Collection<AssertionInfo> aicAnon
                = aim.getAssertionInfo(MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME);
            Collection<AssertionInfo> aicAnon2
                = aim.getAssertionInfo(MetadataConstants.ANON_RESPONSES_ASSERTION_QNAME_0705);
            boolean hasAnon = (aicAnon != null && !aicAnon.isEmpty()) 
                        || (aicAnon2 != null && !aicAnon2.isEmpty());
            boolean hasNonAnon = (aicNonAnon != null && !aicNonAnon.isEmpty()) 
                        || (aicNonAnon2 != null && !aicNonAnon2.isEmpty());
                
            if (hasAnonymous && hasNonAnon && !hasAnon) {
                throw new SoapFault("Found anonymous address but non-anonymous required",
                                    new QName(Names.WSA_NAMESPACE_NAME,
                                              "OnlyNonAnonymousAddressSupported"));
            } else if (!onlyAnonymous && !hasNonAnon && hasAnon) {
                throw new SoapFault("Found non-anonymous address but only anonymous supported",
                                    new QName(Names.WSA_NAMESPACE_NAME,
                                              "OnlyAnonymousAddressSupported"));
            }
        }
        
    }

    private void assertAssertion(AssertionInfoMap aim, QName type) {
        Collection<AssertionInfo> aic = aim.getAssertionInfo(type);
        for (AssertionInfo ai : aic) {
            ai.setAsserted(true);
        }
    }

    /**
     * @param exts list of extension elements
     * @return true iff the UsingAddressing element is found
     */
    private boolean hasUsingAddressing(List<ExtensibilityElement> exts) {
        boolean found = false;
        if (exts != null) {
            Iterator<ExtensibilityElement> extensionElements = exts.iterator();
            while (extensionElements.hasNext() && !found) {
                ExtensibilityElement ext = 
                    (ExtensibilityElement)extensionElements.next();
                found = Names.WSAW_USING_ADDRESSING_QNAME.equals(ext.getElementType());    
            }
        }
        return found;
    }

    /**
     * Mediate message flow.
     *
     * @param message the current message
     * @param isFault true if a fault is being mediated
     * @return true if processing should continue on dispatch path 
     */
    protected boolean mediate(Message message, boolean isFault) {    
        boolean continueProcessing = true;
        if (ContextUtils.isOutbound(message)) {
            if (usingAddressing(message)) {
                // request/response MAPs must be aggregated
                aggregate(message, isFault);
            }
            AddressingPropertiesImpl theMaps = 
                ContextUtils.retrieveMAPs(message, false, ContextUtils.isOutbound(message));
            if (null != theMaps && ContextUtils.isRequestor(message)) {            
                assertAddressing(message, 
                                 theMaps.getReplyTo(),
                                 theMaps.getFaultTo());
            }
        } else if (!ContextUtils.isRequestor(message)) {
            //responder validates incoming MAPs
            AddressingPropertiesImpl maps = getMAPs(message, false, false);
            //check responses          
            if (maps != null) {
                checkAddressingResponses(maps.getReplyTo(), maps.getFaultTo());
                assertAddressing(message, 
                                 maps.getReplyTo(),
                                 maps.getFaultTo());
            }
            boolean isOneway = message.getExchange().isOneWay();
            if (null == maps && !addressingRequired) {
                return false;
            }
            continueProcessing = validateIncomingMAPs(maps, message);
            if (maps != null) {
                AddressingPropertiesImpl theMaps = 
                    ContextUtils.retrieveMAPs(message, false, ContextUtils.isOutbound(message));
                if (null != theMaps) {            
                    assertAddressing(message, theMaps.getReplyTo(), theMaps.getFaultTo());
                }

                if (isOneway
                    || !ContextUtils.isGenericAddress(maps.getReplyTo())) {
                    ContextUtils.rebaseResponse(maps.getReplyTo(),
                                                maps,
                                                message);
                } 
                if (!isOneway) {
                    // if ReplyTo address is none then 202 response status is expected
                    // However returning a fault is more appropriate for request-response MEP
                    if (ContextUtils.isNoneAddress(maps.getReplyTo())) {
                        continueProcessing = false;
                    } else {
                        // ensure the inbound MAPs are available in both the full & fault
                        // response messages (used to determine relatesTo etc.)
                        ContextUtils.propogateReceivedMAPs(maps,
                                                       message.getExchange());
                    }
                }
            }
            if (continueProcessing) {
                // any faults thrown from here on can be correlated with this message
                message.put(FaultMode.class, FaultMode.LOGICAL_RUNTIME_FAULT);
            } else {
                // validation failure => dispatch is aborted, response MAPs 
                // must be aggregated
                //isFault = true;
                //aggregate(message, isFault);
                throw new SoapFault(ContextUtils.retrieveMAPFaultReason(message),
                                    new QName(Names.WSA_NAMESPACE_NAME,
                                              ContextUtils.retrieveMAPFaultName(message)));
            }
        } else {
            AddressingPropertiesImpl theMaps = 
                ContextUtils.retrieveMAPs(message, false, ContextUtils.isOutbound(message));
            if (null != theMaps) {            
                assertAddressing(message, theMaps.getReplyTo(), theMaps.getFaultTo());
            }
            // If the wsa policy is enabled , but the client sets the
            // WSAddressingFeature.isAddressingRequired to false , we need to assert all WSA assertion to true
            if (!ContextUtils.isOutbound(message) && ContextUtils.isRequestor(message)
                && getWSAddressingFeature(message) != null
                && !getWSAddressingFeature(message).isAddressingRequired()) {
                assertAddressing(message);
            }
            //CXF-3060 :If wsa policy is not enforced, AddressingProperties map is null and
            // AddressingFeature.isRequired, requestor checks inbound message and throw exception
            if (null == theMaps
                && !ContextUtils.isOutbound(message) 
                && ContextUtils.isRequestor(message)
                && getWSAddressingFeature(message) != null
                && getWSAddressingFeature(message).isAddressingRequired()) {
                boolean missingWsaHeader = false;
                AssertionInfoMap aim = message.get(AssertionInfoMap.class);
                if (aim == null || aim.size() == 0) {
                    missingWsaHeader = true;
                }
                if (aim != null && aim.size() > 0) {
                    missingWsaHeader = true;
                    QName[] types = new QName[] {
                        MetadataConstants.ADDRESSING_ASSERTION_QNAME,
                        MetadataConstants.USING_ADDRESSING_2004_QNAME,
                        MetadataConstants.USING_ADDRESSING_2005_QNAME,
                        MetadataConstants.USING_ADDRESSING_2006_QNAME
                    };
                    for (QName type : types) {
                        for (AssertionInfo assertInfo : aim.getAssertionInfo(type)) {
                            if (assertInfo.isAsserted()) {
                                missingWsaHeader = false;
                            }
                        }
                    }
                }
                if (missingWsaHeader) {
                    String reason = BUNDLE.getString("MISSING_ACTION_MESSAGE");
                    throw new SoapFault(reason, new QName(Names.WSA_NAMESPACE_NAME,
                                                          Names.HEADER_REQUIRED_NAME));
                }
            }
        }
        return continueProcessing;
    }

    private void checkAddressingResponses(EndpointReferenceType replyTo, EndpointReferenceType faultTo) {
        if (this.addressingResponses.equals("ALL")) {
            return;
        }
        boolean passed = false;
        boolean anonReply = ContextUtils.isGenericAddress(replyTo);
        boolean anonFault = ContextUtils.isGenericAddress(faultTo);
        boolean isAnonymous = anonReply && anonFault;
        if ("ANONYMOUS".equals(addressingResponses) && isAnonymous) {
            passed = true;
        } else if ("NON_ANONYMOUS".equals(addressingResponses)
                   && (!anonReply && (faultTo.getAddress() != null && !anonFault) 
                       || !anonReply && faultTo.getAddress() == null)) {
            passed = true;
        }
        if (!passed) {
            String reason = BUNDLE.getString("INVALID_ADDRESSING_PROPERTY_MESSAGE");
            QName detail = "ANONYMOUS".equals(addressingResponses)
                ? Names.ONLY_ANONYMOUS_ADDRESS_SUPPORTED_QNAME
                : Names.ONLY_NONANONYMOUS_ADDRESS_SUPPORTED_QNAME;
            throw new SoapFault(reason, detail);
        }            
    }
    /**
     * Perform MAP aggregation.
     *
     * @param message the current message
     * @param isFault true if a fault is being mediated
     */
    private void aggregate(Message message, boolean isFault) {
        boolean isRequestor = ContextUtils.isRequestor(message);

        AddressingPropertiesImpl maps = assembleGeneric(message);
        addRoleSpecific(maps, message, isRequestor, isFault);
        // outbound property always used to store MAPs, as this handler 
        // aggregates only when either:
        // a) message really is outbound
        // b) message is currently inbound, but we are about to abort dispatch
        //    due to an incoming MAPs validation failure, so the dispatch
        //    will shortly traverse the outbound path
        ContextUtils.storeMAPs(maps, message, true, isRequestor);
    }

    /**
     * Assemble the generic MAPs (for both requests and responses).
     *
     * @param message the current message
     * @return AddressingProperties containing the generic MAPs
     */
    private AddressingPropertiesImpl assembleGeneric(Message message) {
        AddressingPropertiesImpl maps = getMAPs(message, true, true);
        // MessageID
        if (maps.getMessageID() == null) {
            String messageID = ContextUtils.generateUUID();
            maps.setMessageID(ContextUtils.getAttributedURI(messageID));
        }

        // Action
        if (ContextUtils.hasEmptyAction(maps)) {
            maps.setAction(ContextUtils.getAction(message));

            if (ContextUtils.hasEmptyAction(maps)
                && ContextUtils.isOutbound(message)) {
                maps.setAction(ContextUtils.getAttributedURI(getActionUri(message, true)));
            }
        }

        return maps;
    }
    
    private String getActionFromInputMessage(final OperationInfo operation) {
        MessageInfo inputMessage = operation.getInput();

        if (inputMessage.getExtensionAttributes() != null) {
            String inputAction = ContextUtils.getAction(inputMessage);
            if (!StringUtils.isEmpty(inputAction)) {
                return inputAction;
            }
        }
        return null;
    }
    
    private String getActionFromOutputMessage(final OperationInfo operation) {
        MessageInfo outputMessage = operation.getOutput();
        if (outputMessage != null && outputMessage.getExtensionAttributes() != null) {
            String outputAction = ContextUtils.getAction(outputMessage);
            if (!StringUtils.isEmpty(outputAction)) {
                return outputAction;
            }
        }
        return null;
    }

    private boolean isSameFault(final FaultInfo faultInfo, String faultName) {
        if (faultInfo.getName() == null || faultName == null) {
            return false;
        }
        String faultInfoName = faultInfo.getName().getLocalPart();
        return faultInfoName.equals(faultName) 
            || faultInfoName.equals(StringUtils.uncapitalize(faultName));
    }

    private String getActionBaseUri(final OperationInfo operation) {
        String interfaceName = operation.getInterface().getName().getLocalPart();
        return addPath(operation.getName().getNamespaceURI(), interfaceName);
    }

    private String getActionFromFaultMessage(final OperationInfo operation, final String faultName) {
        if (operation.getFaults() != null) {
            for (FaultInfo faultInfo : operation.getFaults()) {
                if (isSameFault(faultInfo, faultName)) {
                    if (faultInfo.getExtensionAttributes() != null) {
                        String faultAction = ContextUtils.getAction(faultInfo);
                        if (!StringUtils.isEmpty(faultAction)) {
                            return faultAction;
                        }
                    }
                    return addPath(addPath(addPath(getActionBaseUri(operation),
                                                   operation.getName().getLocalPart()),
                                           "Fault"), 
                                   faultInfo.getFaultName().getLocalPart());
                }
            }
        }
        return addPath(addPath(addPath(getActionBaseUri(operation),
                                       operation.getName().getLocalPart()), "Fault"), faultName);
    }

    private String getFaultNameFromMessage(final Message message) {
        Exception e = message.getContent(Exception.class);
        Throwable cause = e.getCause();
        if (cause == null) {
            cause = e;
        }
        if (e instanceof Fault) {
            WebFault t = cause.getClass().getAnnotation(WebFault.class);
            if (t != null) {
                return t.name();
            }
        }
        return cause.getClass().getSimpleName();    
    }

    protected String getActionUri(Message message, boolean checkMessage) {
        BindingOperationInfo bop = message.getExchange().get(BindingOperationInfo.class);
        if (bop == null || Boolean.TRUE.equals(bop.getProperty("operation.is.synthetic"))) {
            return null;
        }
        OperationInfo op = bop.getOperationInfo();
        if (op.isUnwrapped()) {
            op = ((UnwrappedOperationInfo)op).getWrappedOperation();
        }
        //CXF-2836:To correct the wsa:action header value for dispatch client
        if (bop.getProperty("dispatchToOperation") != null) {
            //modifies the bop and bp to the value of dispatch client really invokes, 
            //This helps corrct the wsa:action header value
            QName opName = (QName)bop.getProperty("dispatchToOperation");
            OperationInfo dispatchOP = bop.getBinding().getService().getInterface().getOperation(opName);
            BindingOperationInfo dispachBop = null;
            for (BindingOperationInfo binfo : bop.getBinding().getOperations()) {
                if (binfo.getOperationInfo().getName().toString().equals(opName.toString())) {
                    dispachBop = binfo;
                }
            }
            if (dispachBop != null && dispatchOP != null) {
                bop = dispachBop;
                op = dispatchOP;
            }
        }
        
        String actionUri = null;
        if (checkMessage) {
            actionUri = (String) message.get(ContextUtils.ACTION);
            if (actionUri == null) {
                actionUri = (String) message.get(SoapBindingConstants.SOAP_ACTION);
            }
        }
        if (actionUri != null) {
            return actionUri;
        }
        String opNamespace = getActionBaseUri(op);
        
        boolean inbound = !ContextUtils.isOutbound(message);
        boolean requestor = ContextUtils.isRequestor(message);
        boolean inMsg = requestor ^ inbound;
        if (ContextUtils.isFault(message)) {
            String faultName = getFaultNameFromMessage(message);
            actionUri = getActionFromFaultMessage(op, faultName);
        } else if (inMsg) {
            String explicitAction = getActionFromInputMessage(op);
            if (StringUtils.isEmpty(explicitAction)) {
                SoapOperationInfo soi = ContextUtils.getSoapOperationInfo(bop);
                explicitAction = soi == null ? null : soi.getAction();
            }            
            
            if (!StringUtils.isEmpty(explicitAction)) {
                actionUri = explicitAction;
            } else if (null == op.getInputName()) {
                actionUri = addPath(opNamespace, op.getName().getLocalPart() + "Request");
            } else {
                actionUri = addPath(opNamespace, op.getInputName());
            }
        } else {
            String explicitAction = getActionFromOutputMessage(op);
            if (explicitAction != null) {
                actionUri = explicitAction;
            } else if (null == op.getOutputName()) {
                actionUri = addPath(opNamespace, op.getName().getLocalPart() + "Response");
            } else {
                actionUri = addPath(opNamespace, op.getOutputName());
            }
        }
        return actionUri;
    }


    private String getDelimiter(String uri) {
        if (uri.startsWith("urn")) {
            return ":";
        }
        return "/";
    }

    private String addPath(String uri, String path) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(uri);
        String delimiter = getDelimiter(uri);
        if (!uri.endsWith(delimiter) && !path.startsWith(delimiter)) {
            buffer.append(delimiter);
        }
        buffer.append(path);
        return buffer.toString();
    }
    
    /**
     * Add MAPs which are specific to the requestor or responder role.
     *
     * @param maps the MAPs being assembled
     * @param message the current message
     * @param isRequestor true iff the current messaging role is that of 
     * requestor 
     * @param isFault true if a fault is being mediated
     */
    private void addRoleSpecific(AddressingPropertiesImpl maps, 
                                 Message message,
                                 boolean isRequestor,
                                 boolean isFault) {
        if (isRequestor) {
            Exchange exchange = message.getExchange();
            
            // add request-specific MAPs
            boolean isOneway = exchange.isOneWay();
            boolean isOutbound = ContextUtils.isOutbound(message);
            
            // To
            if (maps.getTo() == null) {
                Conduit conduit = null;
                if (isOutbound) {
                    conduit = ContextUtils.getConduit(conduit, message);
                }
                String s = (String)message.get(Message.ENDPOINT_ADDRESS);
                EndpointReferenceType reference = conduit != null
                                                  ? conduit.getTarget()
                                                  : ContextUtils.getNoneEndpointReference();
                if (conduit != null && !StringUtils.isEmpty(s) 
                    && !reference.getAddress().getValue().equals(s)) {
                    EndpointReferenceType ref = new EndpointReferenceType();
                    AttributedURIType tp = new AttributedURIType();
                    tp.setValue(s);
                    ref.setAddress(tp);
                    ref.setMetadata(reference.getMetadata());
                    ref.setReferenceParameters(reference.getReferenceParameters());
                    ref.getOtherAttributes().putAll(reference.getOtherAttributes());
                    reference = ref;
                }
                maps.setTo(reference);
            }

            // ReplyTo, set if null in MAPs or if set to a generic address
            // (anonymous or none) that may not be appropriate for the
            // current invocation
            EndpointReferenceType replyTo = maps.getReplyTo();
            if (ContextUtils.isGenericAddress(replyTo)) {
                replyTo = getReplyTo(message, replyTo);
                if (replyTo == null
                    || (isOneway
                        && (replyTo.getAddress() == null
                            || !Names.WSA_NONE_ADDRESS.equals(
                                    replyTo.getAddress().getValue())))) {
                    AttributedURIType address =
                        ContextUtils.getAttributedURI(isOneway
                                                      ? Names.WSA_NONE_ADDRESS
                                                      : Names.WSA_ANONYMOUS_ADDRESS);
                    replyTo =
                        ContextUtils.WSA_OBJECT_FACTORY.createEndpointReferenceType();
                    replyTo.setAddress(address);
                }
                maps.setReplyTo(replyTo);
            }

            // FaultTo
            if (maps.getFaultTo() == null) {
                maps.setFaultTo(maps.getReplyTo());
            } else if (maps.getFaultTo().getAddress() == null) {
                maps.setFaultTo(null);
            }
        } else {
            // add response-specific MAPs
            AddressingPropertiesImpl inMAPs = getMAPs(message, false, false);
            maps.exposeAs(inMAPs.getNamespaceURI());
            // To taken from ReplyTo or FaultTo in incoming MAPs (depending
            // on the fault status of the response)
            if (isFault && inMAPs.getFaultTo() != null) {
                maps.setTo(inMAPs.getFaultTo());
            } else if (maps.getTo() == null && inMAPs.getReplyTo() != null) {
                maps.setTo(inMAPs.getReplyTo());
            }

            // RelatesTo taken from MessageID in incoming MAPs
            if (inMAPs.getMessageID() != null
                && !Boolean.TRUE.equals(message.get(Message.PARTIAL_RESPONSE_MESSAGE))) {
                String inMessageID = inMAPs.getMessageID().getValue();
                maps.setRelatesTo(ContextUtils.getRelatesTo(inMessageID));
            } else {
                maps.setRelatesTo(ContextUtils
                                  .getRelatesTo(Names.WSA_UNSPECIFIED_RELATIONSHIP));
            }

            // fallback fault action
            if (isFault && maps.getAction() == null) {
                maps.setAction(ContextUtils.getAttributedURI(
                    Names.WSA_DEFAULT_FAULT_ACTION));
            }
 
            if (isFault
                && !ContextUtils.isGenericAddress(inMAPs.getFaultTo())) {
                ContextUtils.rebaseResponse(inMAPs.getFaultTo(),
                                            inMAPs,
                                            message);
            }
        }
    }

    private EndpointReferenceType getReplyTo(Message message, 
                                             EndpointReferenceType originalReplyTo) {
        Exchange exchange = message.getExchange();
        Endpoint info = exchange.get(Endpoint.class);
        if (info == null) {
            return originalReplyTo;
        }
        synchronized (info) {
            EndpointInfo ei = info.getEndpointInfo();
            Destination dest = ei.getProperty(DECOUPLED_DESTINATION, Destination.class);
            if (dest == null) {
                dest = createDecoupledDestination(message);
                if (dest != null) {
                    info.getEndpointInfo().setProperty(DECOUPLED_DESTINATION, dest);
                }
            }
            if (dest != null) {
                return dest.getAddress();
            }
        }
        return originalReplyTo;
    }

    private Destination createDecoupledDestination(Message message) {
        String replyToAddress = (String)message.getContextualProperty(WSAContextUtils.REPLYTO_PROPERTY);
        if (replyToAddress != null) {
            return setUpDecoupledDestination(message.getExchange().get(Bus.class),
                                             replyToAddress, 
                                             message);
        }
        return null;
    }
    /**
     * Set up the decoupled Destination if necessary.
     */
    private Destination setUpDecoupledDestination(Bus bus, String replyToAddress, Message message) {        
        EndpointReferenceType reference =
            EndpointReferenceUtils.getEndpointReference(replyToAddress);
        if (reference != null) {
            String decoupledAddress = reference.getAddress().getValue();
            LOG.info("creating decoupled endpoint: " + decoupledAddress);
            try {
                Destination dest = getDestination(bus, replyToAddress, message);
                bus.getExtension(ClientLifeCycleManager.class).registerListener(DECOUPLED_DEST_CLEANER);
                return dest;
            } catch (Exception e) {
                // REVISIT move message to localizable Messages.properties
                LOG.log(Level.WARNING, 
                        "decoupled endpoint creation failed: ", e);
            }
        }
        return null;
    }

    /**
     * @param address the address
     * @return a Destination for the address
     */
    private Destination getDestination(Bus bus, String address, Message message) throws IOException {
        Destination destination = null;
        DestinationFactoryManager factoryManager =
            bus.getExtension(DestinationFactoryManager.class);
        DestinationFactory factory =
            factoryManager.getDestinationFactoryForUri(address);
        if (factory != null) {
            Endpoint ep = message.getExchange().get(Endpoint.class);
            
            EndpointInfo ei = new EndpointInfo();
            ei.setName(new QName(ep.getEndpointInfo().getName().getNamespaceURI(),
                                 ep.getEndpointInfo().getName().getLocalPart() + ".decoupled"));
            ei.setAddress(address);
            destination = factory.getDestination(ei);
            Conduit conduit = ContextUtils.getConduit(null, message);
            if (conduit instanceof Observable) {
                MessageObserver ob = ((Observable)conduit).getMessageObserver();
                ob = new InterposedMessageObserver(bus, ob);
                destination.setMessageObserver(ob);
            }
        }
        return destination;
    }
    protected static class InterposedMessageObserver implements MessageObserver {
        Bus bus;
        MessageObserver observer;
        public InterposedMessageObserver(Bus b, MessageObserver o) {
            bus = b;
            observer = o;
        }
        
        /**
         * Called for an incoming message.
         * 
         * @param inMessage
         */
        public void onMessage(Message inMessage) {
            // disposable exchange, swapped with real Exchange on correlation
            inMessage.setExchange(new ExchangeImpl());
            inMessage.getExchange().put(Bus.class, bus);
            inMessage.put(Message.DECOUPLED_CHANNEL_MESSAGE, Boolean.TRUE);
            inMessage.put(Message.RESPONSE_CODE, HttpURLConnection.HTTP_OK);

            // remove server-specific properties
            //inMessage.remove(AbstractHTTPDestination.HTTP_REQUEST);
            //inMessage.remove(AbstractHTTPDestination.HTTP_RESPONSE);
            inMessage.remove(Message.ASYNC_POST_RESPONSE_DISPATCH);
            updateResponseCode(inMessage);

            //cache this inputstream since it's defer to use in case of async
            try {
                InputStream in = inMessage.getContent(InputStream.class);
                if (in != null) {
                    CachedOutputStream cos = new CachedOutputStream();
                    IOUtils.copy(in, cos);
                    inMessage.setContent(InputStream.class, cos.getInputStream());
                }
                observer.onMessage(inMessage);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        private void updateResponseCode(Message message) {
            Object o = message.get("HTTP.RESPONSE");
            if (o != null) {
                try {
                    o.getClass().getMethod("setStatus", Integer.TYPE)
                        .invoke(o, HttpURLConnection.HTTP_ACCEPTED);
                } catch (Throwable t) {
                    //ignore
                }
            }
            
        }
        
    }
    
    /**
     * Get the starting point MAPs (either empty or those set explicitly
     * by the application on the binding provider request context).
     *
     * @param message the current message
     * @param isProviderContext true if the binding provider request context
     * available to the client application as opposed to the message context
     * visible to handlers
     * @param isOutbound true iff the message is outbound
     * @return AddressingProperties retrieved MAPs
     */
    private AddressingPropertiesImpl getMAPs(Message message,
                                             boolean isProviderContext,
                                             boolean isOutbound) {

        AddressingPropertiesImpl maps = null;
        maps = ContextUtils.retrieveMAPs(message, 
                                         isProviderContext,
                                         isOutbound);
        LOG.log(Level.FINE, "MAPs retrieved from message {0}", maps);

        if (maps == null && isProviderContext) {
            maps = new AddressingPropertiesImpl();
            setupNamespace(maps, message);
        }
        return maps;
    }

    private void setupNamespace(AddressingPropertiesImpl maps, Message message) {
        AssertionInfoMap aim = message.get(AssertionInfoMap.class);
        if (null == aim) {
            return;
        }
        Collection<AssertionInfo> aic = aim.getAssertionInfo(MetadataConstants.USING_ADDRESSING_2004_QNAME);
        if (aic != null && !aic.isEmpty()) {
            maps.exposeAs(Names200408.WSA_NAMESPACE_NAME);
        }
    }
    
    /**
     * Validate incoming MAPs
     * @param maps the incoming MAPs
     * @param message the current message
     * @return true if incoming MAPs are valid
     * @pre inbound message, not requestor
     */
    private boolean validateIncomingMAPs(AddressingProperties maps,
                                         Message message) {
        boolean valid = true;
        
        if (maps != null) {
            //WSAB spec, section 4.2 validation (SOAPAction must match action
            String sa = SoapActionInInterceptor.getSoapAction(message);
            String s1 = this.getActionUri(message, false);
            
            if (maps.getAction() == null || maps.getAction().getValue() == null) {
                String reason =
                    BUNDLE.getString("MISSING_ACTION_MESSAGE");

                ContextUtils.storeMAPFaultName(Names.HEADER_REQUIRED_NAME,
                                               message);
                ContextUtils.storeMAPFaultReason(reason, message);
                valid = false;
            }
            
            if (!StringUtils.isEmpty(sa) && valid 
                && !MessageUtils.isTrue(message.get(MAPAggregator.ACTION_VERIFIED))) {
                if (sa.startsWith("\"")) {
                    sa = sa.substring(1, sa.lastIndexOf('"'));
                }
                String action = maps.getAction() == null ? "" : maps.getAction().getValue();
                if (!StringUtils.isEmpty(sa)
                    && !sa.equals(action)) {
                    //don't match, must send fault back....
                    String reason =
                        BUNDLE.getString("INVALID_ADDRESSING_PROPERTY_MESSAGE");
    
                    ContextUtils.storeMAPFaultName(Names.ACTION_MISMATCH_NAME,
                                                   message);
                    ContextUtils.storeMAPFaultReason(reason, message);
                    valid = false;
                } else if (!StringUtils.isEmpty(s1)
                    && !action.equals(s1)
                    && !action.equals(s1 + "Request")
                    && !s1.equals(action + "Request")) {
                    //if java first, it's likely to have "Request", if wsdl first,
                    //it will depend if the wsdl:input has a name or not. Thus, we'll
                    //check both plain and with the "Request" trailer
                    
                    //doesn't match what's in the wsdl/annotations
                    String reason =
                        BundleUtils.getFormattedString(BUNDLE,
                                "ACTION_NOT_SUPPORTED_MSG", action);
                    
                    ContextUtils.storeMAPFaultName(Names.ACTION_NOT_SUPPORTED_NAME,
                                                   message);
                    ContextUtils.storeMAPFaultReason(reason, message);
                    valid = false;
                }
            }
            
            AttributedURIType messageID = maps.getMessageID();
            
            if (!message.getExchange().isOneWay() 
                    && (messageID == null || messageID.getValue() == null)
                    && valid) {
                String reason =
                    BUNDLE.getString("MISSING_ACTION_MESSAGE");

                ContextUtils.storeMAPFaultName(Names.HEADER_REQUIRED_NAME,
                                               message);
                ContextUtils.storeMAPFaultReason(reason, message);
                
                valid = false;
            }
        
            // Always cache message IDs, even when the message is not valid for some
            // other reason.
            if (!allowDuplicates && messageID != null && messageID.getValue() != null
                && !messageIdCache.checkUniquenessAndCacheId(messageID.getValue())) {

                LOG.log(Level.WARNING,
                        "DUPLICATE_MESSAGE_ID_MSG",
                        messageID.getValue());
                
                // Only throw the fault if something else has not already marked the
                // message as invalid.
                if (valid) {
                    String reason =
                        BUNDLE.getString("DUPLICATE_MESSAGE_ID_MSG");
                    String l7dReason = 
                        MessageFormat.format(reason, messageID.getValue());
                    ContextUtils.storeMAPFaultName(Names.DUPLICATE_MESSAGE_ID_NAME,
                                                   message);
                    ContextUtils.storeMAPFaultReason(l7dReason, message);
                }
                
                valid = false;
            }
        } else if (usingAddressingAdvisory) {
            String reason =
                BUNDLE.getString("MISSING_ACTION_MESSAGE");

            ContextUtils.storeMAPFaultName(Names.HEADER_REQUIRED_NAME,
                                           message);
            ContextUtils.storeMAPFaultReason(reason, message);
            valid = false;
        }
        
        if (Names.INVALID_CARDINALITY_NAME.equals(ContextUtils.retrieveMAPFaultName(message))) {
            valid = false;
        }
        
        return valid;
    }
}

