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

package org.apache.cxf.ws.security.policy.interceptors;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.policy.AbstractPolicyInterceptorProvider;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.policy.SP11Constants;
import org.apache.cxf.ws.security.policy.SP12Constants;
import org.apache.cxf.ws.security.policy.model.IssuedToken;
import org.apache.cxf.ws.security.policy.model.Trust10;
import org.apache.cxf.ws.security.policy.model.Trust13;
import org.apache.cxf.ws.security.tokenstore.MemoryTokenStore;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.cxf.ws.security.trust.STSClient;
import org.apache.cxf.ws.security.trust.STSUtils;
import org.apache.cxf.ws.security.wss4j.PolicyBasedWSS4JInInterceptor;
import org.apache.cxf.ws.security.wss4j.PolicyBasedWSS4JOutInterceptor;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.cxf.ws.security.wss4j.policyvalidators.IssuedTokenPolicyValidator;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.apache.ws.security.handler.WSHandlerResult;
import org.apache.ws.security.saml.SAMLKeyInfo;
import org.apache.ws.security.saml.ext.AssertionWrapper;
import org.apache.ws.security.util.WSSecurityUtil;

/**
 * 
 */
public class IssuedTokenInterceptorProvider extends AbstractPolicyInterceptorProvider {

    public IssuedTokenInterceptorProvider() {
        super(Arrays.asList(SP11Constants.ISSUED_TOKEN, SP12Constants.ISSUED_TOKEN));
        
        //issued tokens can be attached as a supporting token without
        //any type of binding.  Make sure we can support that.
        this.getOutInterceptors().add(PolicyBasedWSS4JOutInterceptor.INSTANCE);
        this.getOutFaultInterceptors().add(PolicyBasedWSS4JOutInterceptor.INSTANCE);
        this.getInInterceptors().add(PolicyBasedWSS4JInInterceptor.INSTANCE);
        this.getInFaultInterceptors().add(PolicyBasedWSS4JInInterceptor.INSTANCE);
        
        this.getOutInterceptors().add(new IssuedTokenOutInterceptor());
        this.getOutFaultInterceptors().add(new IssuedTokenOutInterceptor());
        this.getInInterceptors().add(new IssuedTokenInInterceptor());
        this.getInFaultInterceptors().add(new IssuedTokenInInterceptor());
    }
    
    
    static final TokenStore getTokenStore(Message message) {
        TokenStore tokenStore = (TokenStore)message.getContextualProperty(TokenStore.class.getName());
        if (tokenStore == null) {
            tokenStore = new MemoryTokenStore();
            message.getExchange().get(Endpoint.class).getEndpointInfo()
                .setProperty(TokenStore.class.getName(), tokenStore);
        }
        return tokenStore;
    }

    static class IssuedTokenOutInterceptor extends AbstractPhaseInterceptor<Message> {
        public IssuedTokenOutInterceptor() {
            super(Phase.PREPARE_SEND);
        }
        public void handleMessage(Message message) throws Fault {
            AssertionInfoMap aim = message.get(AssertionInfoMap.class);
            // extract Assertion information
            if (aim != null) {
                Collection<AssertionInfo> ais = aim.get(SP12Constants.ISSUED_TOKEN);
                if (ais == null || ais.isEmpty()) {
                    return;
                }
                if (isRequestor(message)) {
                    IssuedToken itok = (IssuedToken)ais.iterator().next().getAssertion();
                    
                    SecurityToken tok = (SecurityToken)message.getContextualProperty(SecurityConstants.TOKEN);
                    if (tok == null) {
                        String tokId = (String)message.getContextualProperty(SecurityConstants.TOKEN_ID);
                        if (tokId != null) {
                            tok = getTokenStore(message).getToken(tokId);
                        }
                    }
                    if (tok == null) {
                        STSClient client = STSUtils.getClient(message, "sts");
                        AddressingProperties maps =
                            (AddressingProperties)message
                                .get("javax.xml.ws.addressing.context.outbound");
                        if (maps == null) {
                            maps = (AddressingProperties)message
                                .get("javax.xml.ws.addressing.context");
                        }
                        synchronized (client) {
                            try {
                                // Transpose ActAs info from original request to the STS client.
                                client.setActAs(
                                    message.getContextualProperty(SecurityConstants.STS_TOKEN_ACT_AS));

                                client.setTrust(getTrust10(aim));
                                client.setTrust(getTrust13(aim));
                                client.setTemplate(itok.getRstTemplate());
                                if (maps == null) {
                                    tok = client.requestSecurityToken();
                                } else {
                                    Object o = message
                                        .getContextualProperty(SecurityConstants.STS_APPLIES_TO);
                                    String s = o == null ? null : o.toString();
                                    s = s == null 
                                        ? message.getContextualProperty(Message.ENDPOINT_ADDRESS).toString()
                                            : s;
                                    client.setAddressingNamespace(maps.getNamespaceURI());
                                    tok = client.requestSecurityToken(s);
                                }
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new Fault(e);
                            } finally {
                                client.setTrust((Trust10)null);
                                client.setTrust((Trust13)null);
                                client.setTemplate(null);
                                client.setAddressingNamespace(null);
                            }
                        }
                    } else {
                        //renew token?
                    }
                    if (tok != null) {
                        for (AssertionInfo ai : ais) {
                            ai.setAsserted(true);
                        }
                        message.getExchange().get(Endpoint.class).put(SecurityConstants.TOKEN_ID, 
                                                                      tok.getId());
                        message.getExchange().put(SecurityConstants.TOKEN_ID, 
                                                  tok.getId());
                        getTokenStore(message).add(tok);
                    }
                } else {
                    //server side should be checked on the way in
                    for (AssertionInfo ai : ais) {
                        ai.setAsserted(true);
                    }                    
                }
            }
        }
        private Trust10 getTrust10(AssertionInfoMap aim) {
            Collection<AssertionInfo> ais = aim.get(SP11Constants.TRUST_10);
            if (ais == null || ais.isEmpty()) {
                return null;
            }
            return (Trust10)ais.iterator().next().getAssertion();
        }
        private Trust13 getTrust13(AssertionInfoMap aim) {
            Collection<AssertionInfo> ais = aim.get(SP12Constants.TRUST_13);
            if (ais == null || ais.isEmpty()) {
                return null;
            }
            return (Trust13)ais.iterator().next().getAssertion();
        }
    }
    
    static class IssuedTokenInInterceptor extends AbstractPhaseInterceptor<Message> {
        public IssuedTokenInInterceptor() {
            super(Phase.PRE_PROTOCOL);
            addAfter(WSS4JInInterceptor.class.getName());
            addAfter(PolicyBasedWSS4JInInterceptor.class.getName());
        }

        public void handleMessage(Message message) throws Fault {
            AssertionInfoMap aim = message.get(AssertionInfoMap.class);
            // extract Assertion information
            if (aim != null) {
                Collection<AssertionInfo> ais = aim.get(SP12Constants.ISSUED_TOKEN);
                if (ais == null) {
                    return;
                }
                if (!isRequestor(message)) {
                    List<WSHandlerResult> results = 
                        CastUtils.cast((List<?>)message.get(WSHandlerConstants.RECV_RESULTS));
                    if (results != null) {
                        parseHandlerResults(results, message, aim);
                    }
                } else {
                    //client side should be checked on the way out
                    for (AssertionInfo ai : ais) {
                        ai.setAsserted(true);
                    }                    
                }
            }
        }
        
        private void parseHandlerResults(
            List<WSHandlerResult> results,
            Message message,
            AssertionInfoMap aim
        ) {
            if (results != null) {
                for (WSHandlerResult rResult : results) {
                    WSSecurityEngineResult wser = 
                        findSecurityResult(rResult.getResults());
                    if (wser != null) {
                        List<WSSecurityEngineResult> signedResults = 
                            new ArrayList<WSSecurityEngineResult>();
                        WSSecurityUtil.fetchAllActionResults(
                            rResult.getResults(), WSConstants.SIGN, signedResults
                        );
                        
                        //
                        // Validate the Issued Token policy
                        //
                        IssuedTokenPolicyValidator issuedValidator = 
                            new IssuedTokenPolicyValidator(signedResults, message);
                        if (!issuedValidator.validatePolicy(aim, wser)) {
                            break;
                        }
                        
                        SecurityToken token = createSecurityToken(wser);
                        message.getExchange().put(SecurityConstants.TOKEN, token);
                    }
                }
            }
        }
        
        private WSSecurityEngineResult findSecurityResult(
            List<WSSecurityEngineResult> wsSecEngineResults
        ) {
            for (WSSecurityEngineResult wser : wsSecEngineResults) {
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                if (actInt.intValue() == WSConstants.ST_SIGNED) {
                    AssertionWrapper assertionWrapper = 
                        (AssertionWrapper)wser.get(WSSecurityEngineResult.TAG_SAML_ASSERTION);
                    if (assertionWrapper.getSubjectKeyInfo() != null) {
                        return wser;
                    }
                }
            }
            return null;
        }
        
        private SecurityToken createSecurityToken(
            WSSecurityEngineResult wser
        ) {
            AssertionWrapper assertionWrapper = 
                (AssertionWrapper)wser.get(WSSecurityEngineResult.TAG_SAML_ASSERTION);
            SAMLKeyInfo subjectKeyInfo = assertionWrapper.getSubjectKeyInfo();
            
            SecurityToken token = new SecurityToken(assertionWrapper.getId());
            token.setSecret(subjectKeyInfo.getSecret());
            X509Certificate[] certs = subjectKeyInfo.getCerts();
            if (certs != null && certs.length > 0) {
                token.setX509Certificate(certs[0], null);
            }
            if (assertionWrapper.getSaml1() != null) {
                token.setTokenType(WSConstants.WSS_SAML_TOKEN_TYPE);
            } else if (assertionWrapper.getSaml2() != null) {
                token.setTokenType(WSConstants.WSS_SAML2_TOKEN_TYPE);
            }
            return token;
        }
    }
}
