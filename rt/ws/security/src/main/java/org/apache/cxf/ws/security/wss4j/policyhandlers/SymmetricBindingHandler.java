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

package org.apache.cxf.ws.security.wss4j.policyhandlers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.xml.crypto.dsig.Reference;
import javax.xml.soap.SOAPMessage;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.policy.SP11Constants;
import org.apache.cxf.ws.security.policy.SP12Constants;
import org.apache.cxf.ws.security.policy.SPConstants;
import org.apache.cxf.ws.security.policy.model.AlgorithmSuite;
import org.apache.cxf.ws.security.policy.model.IssuedToken;
import org.apache.cxf.ws.security.policy.model.SecureConversationToken;
import org.apache.cxf.ws.security.policy.model.SymmetricBinding;
import org.apache.cxf.ws.security.policy.model.Token;
import org.apache.cxf.ws.security.policy.model.TokenWrapper;
import org.apache.cxf.ws.security.policy.model.X509Token;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSEncryptionPart;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.conversation.ConversationConstants;
import org.apache.ws.security.conversation.ConversationException;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.apache.ws.security.handler.WSHandlerResult;
import org.apache.ws.security.message.WSSecBase;
import org.apache.ws.security.message.WSSecDKEncrypt;
import org.apache.ws.security.message.WSSecDKSign;
import org.apache.ws.security.message.WSSecEncrypt;
import org.apache.ws.security.message.WSSecEncryptedKey;
import org.apache.ws.security.message.WSSecHeader;
import org.apache.ws.security.message.WSSecSignature;
import org.apache.ws.security.message.WSSecTimestamp;
import org.apache.ws.security.message.token.SecurityTokenReference;
import org.apache.ws.security.util.Base64;
import org.apache.ws.security.util.WSSecurityUtil;

/**
 * 
 */
public class SymmetricBindingHandler extends AbstractBindingBuilder {
    SymmetricBinding sbinding;
    TokenStore tokenStore;
    
    public SymmetricBindingHandler(WSSConfig config, 
                                   SymmetricBinding binding,
                                    SOAPMessage saaj,
                                    WSSecHeader secHeader,
                                    AssertionInfoMap aim,
                                    SoapMessage message) {
        super(config, binding, saaj, secHeader, aim, message);
        this.sbinding = binding;
        tokenStore = getTokenStore();
        protectionOrder = binding.getProtectionOrder();
    }
    
    private TokenWrapper getSignatureToken() {
        if (sbinding.getProtectionToken() != null) {
            return sbinding.getProtectionToken();
        }
        return sbinding.getSignatureToken();
    }
    
    private TokenWrapper getEncryptionToken() {
        if (sbinding.getProtectionToken() != null) {
            return sbinding.getProtectionToken();
        }
        return sbinding.getEncryptionToken();
    }
    
    public void handleBinding() {
        WSSecTimestamp timestamp = createTimestamp();
        handleLayout(timestamp);
        
        if (isRequestor()) {
            //Setup required tokens
            initializeTokens();
        }
        
        if (sbinding.getProtectionOrder() == SPConstants.ProtectionOrder.EncryptBeforeSigning) {
            doEncryptBeforeSign();
        } else {
            doSignBeforeEncrypt();
        }
        //REVIST - what to do with these policies?
        policyAsserted(SP11Constants.TRUST_10);
        policyAsserted(SP12Constants.TRUST_13);
    }
    
    private void initializeTokens()  {
        //Setting up encryption token and signature token
        Token sigTok = getSignatureToken().getToken();
        //Token encrTok = getEncryptionToken().getToken();
        
        if (sigTok instanceof IssuedToken) {
            //IssuedToken issuedToken = (IssuedToken)sigTok;
            
            //REVISIT - WS-Trust STS token retrieval
        } else if (sigTok instanceof SecureConversationToken) {
            //REVISIT - SecureConversation token retrieval
        }
    }
    
    private void doEncryptBeforeSign() {
        try {
            TokenWrapper encryptionWrapper = getEncryptionToken();
            Token encryptionToken = encryptionWrapper.getToken();
            List<WSEncryptionPart> encrParts = getEncryptedParts();
            List<WSEncryptionPart> sigParts = getSignedParts();
            
            if (encryptionToken == null && encrParts.size() > 0) {
                //REVISIT - nothing to encrypt?
            }
            
            if (encryptionToken != null && encrParts.size() > 0) {
                //The encryption token can be an IssuedToken or a 
                //SecureConversationToken
                String tokenId = null;
                SecurityToken tok = null;
                if (encryptionToken instanceof IssuedToken) {
                    tok = getSecurityToken();
                } else if (encryptionToken instanceof SecureConversationToken) {
                    tok = getSecurityToken();
                } else if (encryptionToken instanceof X509Token) {
                    if (isRequestor()) {
                        tokenId = setupEncryptedKey(encryptionWrapper, encryptionToken);
                    } else {
                        tokenId = getEncryptedKey();
                    }
                }
                if (tok == null) {
                    if (tokenId == null || tokenId.length() == 0) {
                        //REVISIT - no tokenId?   Exception?
                    }
                    if (tokenId.startsWith("#")) {
                        tokenId = tokenId.substring(1);
                    }
                    
                    /*
                     * Get hold of the token from the token storage
                     */
                    tok = tokenStore.getToken(tokenId);
                }
    
                boolean attached = false;
                
                if (SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS == encryptionToken.getInclusion()
                    || SPConstants.IncludeTokenType.INCLUDE_TOKEN_ONCE == encryptionToken.getInclusion()
                    || (isRequestor() 
                        && SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS_TO_RECIPIENT 
                            == encryptionToken.getInclusion())) {
                    
                    Element el = tok.getToken();
                    this.addEncryptedKeyElement(cloneElement(el));
                    attached = true;
                } else if (encryptionToken instanceof X509Token && isRequestor()) {
                    Element el = tok.getToken();
                    this.addEncryptedKeyElement(cloneElement(el));
                    attached = true;
                }
                
                WSSecBase encr = doEncryption(encryptionWrapper, tok, attached, encrParts, true);
                
                handleEncryptedSignedHeaders(encrParts, sigParts);
                
                if (timestampEl != null) {
                    WSEncryptionPart timestampPart = 
                        convertToEncryptionPart(timestampEl.getElement());
                    sigParts.add(timestampPart);        
                }
                
                if (isRequestor()) {
                    this.addSupportingTokens(sigParts);
                } else {
                    addSignatureConfirmation(sigParts);
                }
                
                //Sign the message
                //We should use the same key in the case of EncryptBeforeSig
                if (sigParts.size() > 0) {
                    signatures.add(this.doSignature(sigParts, encryptionWrapper, encryptionToken, 
                                                    tok, attached));
                }
                
                if (isRequestor()) {
                    this.doEndorse();
                }
                
                //Check for signature protection and encryption of UsernameToken
                if (sbinding.isSignatureProtection() && this.mainSigId != null 
                    || encryptedTokensIdList.size() > 0 && isRequestor()) {
                    List<WSEncryptionPart> secondEncrParts = new ArrayList<WSEncryptionPart>();
                    
                    //Now encrypt the signature using the above token
                    if (sbinding.isSignatureProtection()) {
                        WSEncryptionPart sigPart = 
                            new WSEncryptionPart(this.mainSigId, "Element");
                        sigPart.setElement(bottomUpElement);
                        secondEncrParts.add(sigPart);
                    }
                    
                    if (isRequestor()) {
                        for (String s : encryptedTokensIdList) {
                            secondEncrParts.add(new WSEncryptionPart(s, "Element"));
                        }
                    }
                    
                    Element secondRefList = null;
                    
                    if (encryptionToken.isDerivedKeys()) {
                        secondRefList = ((WSSecDKEncrypt)encr).encryptForExternalRef(null, 
                                secondEncrParts);
                        this.addDerivedKeyElement(secondRefList);
                    } else {
                        //Encrypt, get hold of the ref list and add it
                        secondRefList = ((WSSecEncrypt)encr).encryptForRef(null, encrParts);
                        this.addDerivedKeyElement(secondRefList);
                    }
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Fault(ex);
        }
    }
    
    private void doSignBeforeEncrypt() {
        TokenWrapper sigTokenWrapper = getSignatureToken();
        Token sigToken = sigTokenWrapper.getToken();
        
        
        String sigTokId = null;
        Element sigTokElem = null;
        
        try {
            SecurityToken sigTok = null;
            if (sigToken != null) {
                if (sigToken instanceof SecureConversationToken) {
                    sigTok = getSecurityToken();
                } else if (sigToken instanceof IssuedToken) {
                    sigTok = getSecurityToken();
                } else if (sigToken instanceof X509Token) {
                    if (isRequestor()) {
                        sigTokId = setupEncryptedKey(sigTokenWrapper, sigToken);
                    } else {
                        sigTokId = getEncryptedKey();
                    }
                }
            } else {
                policyNotAsserted(sbinding, "No signature token");
                return;
            }
            
            if (sigTok == null && StringUtils.isEmpty(sigTokId)) {
                policyNotAsserted(sigTokenWrapper, "No signature token id");
                return;
            } else {
                policyAsserted(sigTokenWrapper);
            }
            if (sigTok == null) {
                sigTok = tokenStore.getToken(sigTokId);
            }
            if (sigTok == null) {
                //REVISIT - no token?
            }
            boolean tokIncluded = true;
            if (SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS == sigToken.getInclusion()
                || SPConstants.IncludeTokenType.INCLUDE_TOKEN_ONCE == sigToken.getInclusion()
                || (isRequestor() 
                    && SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS_TO_RECIPIENT 
                        == sigToken.getInclusion())) {
                
                Element el = sigTok.getToken();
                sigTokElem = cloneElement(el);
                this.addEncryptedKeyElement((Element)sigTokElem);
            } else if (isRequestor() && sigToken instanceof X509Token) {
                Element el = sigTok.getToken();
                sigTokElem = (Element)secHeader.getSecurityHeader().getOwnerDocument()
                        .importNode(el, true);
                this.addEncryptedKeyElement((Element)sigTokElem);
            } else {
                tokIncluded = false;
            }
        
            List<WSEncryptionPart> sigs = getSignedParts();
            //Add timestamp
            if (timestampEl != null) {
                WSEncryptionPart timestampPart = convertToEncryptionPart(timestampEl.getElement());
                sigs.add(timestampPart);        
            }

            if (isRequestor()) {
                addSupportingTokens(sigs);
                if (!sigs.isEmpty()) {
                    signatures.add(doSignature(sigs, sigTokenWrapper, sigToken, sigTok, tokIncluded));
                }
                doEndorse();
            } else {
                //confirm sig
                assertSupportingTokens(sigs);
                addSignatureConfirmation(sigs);
                if (!sigs.isEmpty()) {
                    doSignature(sigs, sigTokenWrapper, sigToken, sigTok, tokIncluded);
                }
            }

            //Encryption
            TokenWrapper encrTokenWrapper = getEncryptionToken();
            Token encrToken = encrTokenWrapper.getToken();
            SecurityToken encrTok = null;
            if (sigToken.equals(encrToken)) {
                //Use the same token
                encrTok = sigTok;
            } else {
                String encrTokId = null;
                //REVISIT - issued token from trust? 
                encrTok = tokenStore.getToken(encrTokId);
                
                if (SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS == encrToken.getInclusion()
                    || SPConstants.IncludeTokenType.INCLUDE_TOKEN_ONCE == encrToken.getInclusion()
                    || (isRequestor() 
                            && SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS_TO_RECIPIENT 
                            == encrToken.getInclusion())) {
                    Element encrTokElem = (Element)encrTok.getToken();
                    
                    //Add the encrToken element before the sigToken element
                    secHeader.getSecurityHeader().insertBefore(encrTokElem, sigTokElem);
                }
            }
            
            List<WSEncryptionPart> enc = getEncryptedParts();
            
            //Check for signature protection
            if (sbinding.isSignatureProtection() && mainSigId != null) {
                WSEncryptionPart sigPart = new WSEncryptionPart(mainSigId, "Element");
                sigPart.setElement(bottomUpElement);
                enc.add(sigPart);
            }
            
            if (isRequestor()) {
                for (String id : encryptedTokensIdList) {
                    enc.add(new WSEncryptionPart(id, "Element"));
                }
            }
            doEncryption(encrTokenWrapper,
                         encrTok,
                         tokIncluded,
                         enc,
                         false);
        } catch (Exception e) {
            throw new Fault(e);
        }
    }
    
    private WSSecBase doEncryptionDerived(TokenWrapper recToken,
                                          SecurityToken encrTok,
                                          Token encrToken,
                                          boolean attached,
                                          List<WSEncryptionPart> encrParts,
                                          boolean atEnd) {
        try {
            WSSecDKEncrypt dkEncr = new WSSecDKEncrypt(wssConfig);
            if (recToken.getToken().getSPConstants() == SP12Constants.INSTANCE) {
                dkEncr.setWscVersion(ConversationConstants.VERSION_05_12);
            }

            if (attached && encrTok.getAttachedReference() != null) {
                dkEncr.setExternalKey(encrTok.getSecret(),
                                      (Element)saaj.getSOAPPart()
                                          .importNode((Element) encrTok.getAttachedReference(),
                                true));
            } else if (encrTok.getUnattachedReference() != null) {
                dkEncr.setExternalKey(encrTok.getSecret(), (Element)saaj.getSOAPPart()
                        .importNode((Element) encrTok.getUnattachedReference(),
                                true));
            } else if (!isRequestor()) { 
                // If the Encrypted key used to create the derived key is not
                // attached use key identifier as defined in WSS1.1 section
                // 7.7 Encrypted Key reference
                SecurityTokenReference tokenRef = new SecurityTokenReference(saaj.getSOAPPart());
                if (encrTok.getSHA1() != null) {
                    tokenRef.setKeyIdentifierEncKeySHA1(encrTok.getSHA1());
                    tokenRef.addTokenType(WSConstants.WSS_ENC_KEY_VALUE_TYPE);
                }
                dkEncr.setExternalKey(encrTok.getSecret(), tokenRef.getElement());
            } else {
                if (attached) {
                    String id = encrTok.getWsuId();
                    if (id == null && encrToken instanceof SecureConversationToken) {
                        dkEncr.setTokenIdDirectId(true);
                        id = encrTok.getId();
                    } else if (id == null) {
                        id = encrTok.getId();
                    }
                    if (id.startsWith("#")) {
                        id = id.substring(1);
                    }
                    dkEncr.setExternalKey(encrTok.getSecret(), id);
                } else {
                    dkEncr.setTokenIdDirectId(true);
                    dkEncr.setExternalKey(encrTok.getSecret(), encrTok.getId());
                }
            }
            
            if (encrTok.getSHA1() != null) {
                dkEncr.setCustomValueType(WSConstants.SOAPMESSAGE_NS11 + "#"
                        + WSConstants.ENC_KEY_VALUE_TYPE);
            } else {
                dkEncr.setCustomValueType(encrTok.getTokenType());
            }
            
            dkEncr.setSymmetricEncAlgorithm(sbinding.getAlgorithmSuite().getEncryption());
            dkEncr.setDerivedKeyLength(sbinding.getAlgorithmSuite()
                                           .getEncryptionDerivedKeyLength() / 8);
            dkEncr.prepare(saaj.getSOAPPart());
            Element encrDKTokenElem = null;
            encrDKTokenElem = dkEncr.getdktElement();
            addDerivedKeyElement(encrDKTokenElem);
            Element refList = dkEncr.encryptForExternalRef(null, encrParts);
            if (atEnd) {
                this.insertBeforeBottomUp(refList);
            } else {
                this.addDerivedKeyElement(refList);                        
            }
            return dkEncr;
        } catch (Exception e) {
            policyNotAsserted(recToken, e);
        }
        return null;
    }
    
    private WSSecBase doEncryption(TokenWrapper recToken,
                                   SecurityToken encrTok,
                                   boolean attached,
                                   List<WSEncryptionPart> encrParts,
                                   boolean atEnd) {
        //Do encryption
        if (recToken != null && recToken.getToken() != null && encrParts.size() > 0) {
            Token encrToken = recToken.getToken();
            policyAsserted(recToken);
            policyAsserted(encrToken);
            AlgorithmSuite algorithmSuite = sbinding.getAlgorithmSuite();
            if (encrToken.isDerivedKeys()) {
                return doEncryptionDerived(recToken, encrTok, encrToken,
                                           attached, encrParts, atEnd);
            } else {
                try {
                    WSSecEncrypt encr = new WSSecEncrypt(wssConfig);
                    String encrTokId = encrTok.getId();
                    if (attached) {
                        encrTokId = encrTok.getWsuId();
                        if (encrTokId == null && encrToken instanceof SecureConversationToken) {
                            encr.setEncKeyIdDirectId(true);
                            encrTokId = encrTok.getId();
                        } else if (encrTokId == null) {
                            encrTokId = encrTok.getId();
                        }
                        if (encrTokId.startsWith("#")) {
                            encrTokId = encrTokId.substring(1);
                        }
                    } else {
                        encr.setEncKeyIdDirectId(true);
                    }
                    if (encrTok.getTokenType() != null) {
                        encr.setCustomReferenceValue(encrTok.getTokenType());
                    }
                    encr.setEncKeyId(encrTokId);
                    encr.setEphemeralKey(encrTok.getSecret());
                    Crypto crypto = getEncryptionCrypto(recToken);
                    if (crypto != null) {
                        this.message.getExchange().put(SecurityConstants.ENCRYPT_CRYPTO, crypto);
                        setEncryptionUser(encr, recToken, false, crypto);
                    }
                    
                    encr.setDocument(saaj.getSOAPPart());
                    encr.setEncryptSymmKey(false);
                    encr.setSymmetricEncAlgorithm(algorithmSuite.getEncryption());
                    
                    if (!isRequestor()) {
                        if (encrTok.getSHA1() != null) {
                            encr.setCustomReferenceValue(encrTok.getSHA1());
                            encr.setKeyIdentifierType(WSConstants.ENCRYPTED_KEY_SHA1_IDENTIFIER);
                        } else {
                            encr.setKeyIdentifierType(WSConstants.EMBED_SECURITY_TOKEN_REF);
                        }
                    } else {
                        if (encrToken instanceof IssuedToken) {
                            encr.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
                            String tokenType = encrTok.getTokenType();
                            if (WSConstants.WSS_SAML_TOKEN_TYPE.equals(tokenType)) {
                                encr.setCustomReferenceValue(WSConstants.WSS_SAML_KI_VALUE_TYPE);
                            } else if (WSConstants.WSS_SAML2_TOKEN_TYPE.equals(tokenType)) {
                                encr.setCustomReferenceValue(WSConstants.WSS_SAML2_KI_VALUE_TYPE);
                            } else {
                                encr.setCustomReferenceValue(tokenType);
                            }
                        }
                    }

                    encr.prepare(saaj.getSOAPPart(), crypto);
                   
                    if (encr.getBSTTokenId() != null) {
                        encr.prependBSTElementToHeader(secHeader);
                    }
                   
                   
                    Element refList = encr.encryptForRef(null, encrParts);
                    if (atEnd) {
                        this.insertBeforeBottomUp(refList);
                    } else {
                        this.addDerivedKeyElement(refList);                        
                    }
                    return encr;
                } catch (WSSecurityException e) {
                    policyNotAsserted(recToken, e.getMessage());
                }    
            }
        }
        return null;
    }    
    
    private byte[] doSignatureDK(List<WSEncryptionPart> sigs,
                               TokenWrapper policyTokenWrapper, 
                               Token policyToken, 
                               SecurityToken tok,
                               boolean included) throws WSSecurityException {
        Document doc = saaj.getSOAPPart();
        WSSecDKSign dkSign = new WSSecDKSign(wssConfig);
        if (policyTokenWrapper.getToken().getSPConstants() == SP12Constants.INSTANCE) {
            dkSign.setWscVersion(ConversationConstants.VERSION_05_12);
        }
        
        //Check for whether the token is attached in the message or not
        boolean attached = false;
        
        if (SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS == policyToken.getInclusion()
            || SPConstants.IncludeTokenType.INCLUDE_TOKEN_ONCE == policyToken.getInclusion()
            || (isRequestor() && SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS_TO_RECIPIENT 
                    == policyToken.getInclusion())) {
            attached = true;
        }
        
        // Setting the AttachedReference or the UnattachedReference according to the flag
        Element ref;
        if (attached) {
            ref = tok.getAttachedReference();
        } else {
            ref = tok.getUnattachedReference();
        }
        
        if (ref != null) {
            dkSign.setExternalKey(tok.getSecret(), 
                                  (Element)saaj.getSOAPPart().importNode(ref, true));
        } else if (!isRequestor() && policyToken.isDerivedKeys()) { 
            // If the Encrypted key used to create the derived key is not
            // attached use key identifier as defined in WSS1.1 section
            // 7.7 Encrypted Key reference
            SecurityTokenReference tokenRef = new SecurityTokenReference(doc);
            if (tok.getSHA1() != null) {
                tokenRef.setKeyIdentifierEncKeySHA1(tok.getSHA1());
                tokenRef.addTokenType(WSConstants.WSS_ENC_KEY_VALUE_TYPE);
            }
            dkSign.setExternalKey(tok.getSecret(), tokenRef.getElement());
        } else {
            if (policyToken instanceof SecureConversationToken) {
                dkSign.setTokenIdDirectId(true);
            }
            dkSign.setExternalKey(tok.getSecret(), tok.getId());
        }

        //Set the algo info
        dkSign.setSignatureAlgorithm(sbinding.getAlgorithmSuite().getSymmetricSignature());
        dkSign.setDerivedKeyLength(sbinding.getAlgorithmSuite().getSignatureDerivedKeyLength() / 8);
        if (tok.getSHA1() != null) {
            //Set the value type of the reference
            dkSign.setCustomValueType(WSConstants.WSS_ENC_KEY_VALUE_TYPE);
        } else {
            dkSign.setCustomValueType(tok.getTokenType());
        }
        
        try {
            dkSign.prepare(doc, secHeader);
        } catch (ConversationException e) {
            throw new WSSecurityException(e.getMessage(), e);
        }
        
        if (sbinding.isTokenProtection()) {
            String sigTokId = tok.getId();
            if (included) {
                sigTokId = tok.getWsuId();
                if (sigTokId == null) {
                    sigTokId = tok.getId();
                }
                if (sigTokId.startsWith("#")) {
                    sigTokId = sigTokId.substring(1);
                }
            }
            sigs.add(new WSEncryptionPart(sigTokId));
        }
        
        dkSign.setParts(sigs);
        List<Reference> referenceList = dkSign.addReferencesToSign(sigs, secHeader);
        
        //Add elements to header
        Element el = dkSign.getdktElement();
        addDerivedKeyElement(el);
        
        //Do signature
        if (bottomUpElement == null) {
            dkSign.computeSignature(referenceList, false, null);
        } else {
            dkSign.computeSignature(referenceList, true, bottomUpElement);
        }
        bottomUpElement = dkSign.getSignatureElement();
        
        this.mainSigId = dkSign.getSignatureId();

        return dkSign.getSignatureValue();        
    }
    
    private byte[] doSignature(List<WSEncryptionPart> sigs,
                             TokenWrapper policyTokenWrapper, 
                             Token policyToken, 
                             SecurityToken tok,
                             boolean included) throws WSSecurityException {
        if (policyToken.isDerivedKeys()) {
            return doSignatureDK(sigs, policyTokenWrapper, policyToken, tok, included);
        } else {
            WSSecSignature sig = new WSSecSignature(wssConfig);
            sig.setWsConfig(wssConfig);
            // If a EncryptedKeyToken is used, set the correct value type to
            // be used in the wsse:Reference in ds:KeyInfo
            int type = included ? WSConstants.CUSTOM_SYMM_SIGNING 
                : WSConstants.CUSTOM_SYMM_SIGNING_DIRECT;
            if (policyToken instanceof X509Token) {
                if (isRequestor()) {
                    sig.setCustomTokenValueType(
                        WSConstants.SOAPMESSAGE_NS11 + "#" + WSConstants.ENC_KEY_VALUE_TYPE
                    );
                    sig.setKeyIdentifierType(type);
                } else {
                    //the tok has to be an EncryptedKey token
                    sig.setEncrKeySha1value(tok.getSHA1());
                    sig.setKeyIdentifierType(WSConstants.ENCRYPTED_KEY_SHA1_IDENTIFIER);
                }
            } else {
                String tokenType = tok.getTokenType();
                if (WSConstants.WSS_SAML_TOKEN_TYPE.equals(tokenType)) {
                    sig.setCustomTokenValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
                    sig.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
                } else if (WSConstants.WSS_SAML2_TOKEN_TYPE.equals(tokenType)) {
                    sig.setCustomTokenValueType(WSConstants.WSS_SAML2_KI_VALUE_TYPE);
                    sig.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
                } else {
                    sig.setCustomTokenValueType(tokenType);
                    sig.setKeyIdentifierType(type);
                }
            }
            
            String sigTokId;
            if (included) {
                sigTokId = tok.getWsuId();
                if (sigTokId == null) {
                    if (policyToken instanceof SecureConversationToken) {
                        sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING_DIRECT);
                    }
                    sigTokId = tok.getId();                    
                }
                if (sigTokId.startsWith("#")) {
                    sigTokId = sigTokId.substring(1);
                }
            } else {
                sigTokId = tok.getId();
            }
                           
            
            sig.setCustomTokenId(sigTokId);
            sig.setSecretKey(tok.getSecret());
            sig.setSignatureAlgorithm(sbinding.getAlgorithmSuite().getSymmetricSignature());
            Crypto crypto = null;
            if (sbinding.getProtectionToken() != null) {
                crypto = getEncryptionCrypto(sbinding.getProtectionToken());
            } else {
                crypto = getSignatureCrypto(policyTokenWrapper);
            }
            this.message.getExchange().put(SecurityConstants.SIGNATURE_CRYPTO, crypto);
            sig.prepare(saaj.getSOAPPart(), crypto, secHeader);
            sig.setParts(sigs);
            List<Reference> referenceList = sig.addReferencesToSign(sigs, secHeader);

            //Do signature
            if (bottomUpElement == null) {
                sig.computeSignature(referenceList, false, null);
            } else {
                sig.computeSignature(referenceList, true, bottomUpElement);
            }
            bottomUpElement = sig.getSignatureElement();

            this.mainSigId = sig.getId();
            return sig.getSignatureValue();
        }
    }

    private String setupEncryptedKey(TokenWrapper wrapper, Token sigToken) throws WSSecurityException {
        WSSecEncryptedKey encrKey = this.getEncryptedKeyBuilder(wrapper, sigToken);
        String id = encrKey.getId();
        byte[] secret = encrKey.getEphemeralKey();

        Date created = new Date();
        Date expires = new Date();
        expires.setTime(created.getTime() + 300000);
        SecurityToken tempTok = new SecurityToken(
                        id, 
                        encrKey.getEncryptedKeyElement(),
                        created, 
                        expires);
        
        
        tempTok.setSecret(secret);
        
        // Set the SHA1 value of the encrypted key, this is used when the encrypted
        // key is referenced via a key identifier of type EncryptedKeySHA1
        tempTok.setSHA1(getSHA1(encrKey.getEncryptedEphemeralKey()));
        
        tokenStore.add(tempTok);
        
        String bstTokenId = encrKey.getBSTTokenId();
        //If direct ref is used to refer to the cert
        //then add the cert to the sec header now
        if (bstTokenId != null && bstTokenId.length() > 0) {
            encrKey.prependBSTElementToHeader(secHeader);
        }
        return id;
    }
    
    private String getEncryptedKey() {
        
        List<WSHandlerResult> results = CastUtils.cast((List<?>)message.getExchange().getInMessage()
            .get(WSHandlerConstants.RECV_RESULTS));
        
        for (WSHandlerResult rResult : results) {
            List<WSSecurityEngineResult> wsSecEngineResults = rResult.getResults();
            
            for (WSSecurityEngineResult wser : wsSecEngineResults) {
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                String encryptedKeyID = (String)wser.get(WSSecurityEngineResult.TAG_ID);
                if (actInt.intValue() == WSConstants.ENCR
                    && encryptedKeyID != null
                    && encryptedKeyID.length() != 0) {
                    Date created = new Date();
                    Date expires = new Date();
                    expires.setTime(created.getTime() + 300000);
                    SecurityToken tempTok = new SecurityToken(encryptedKeyID, created, expires);
                    tempTok.setSecret((byte[])wser.get(WSSecurityEngineResult.TAG_SECRET));
                    tempTok.setSHA1(getSHA1((byte[])wser
                                            .get(WSSecurityEngineResult.TAG_ENCRYPTED_EPHEMERAL_KEY)));
                    tokenStore.add(tempTok);
                    
                    return encryptedKeyID;
                }
            }
        }
        return null;
    }
    
    private String getSHA1(byte[] input) {
        try {
            byte[] digestBytes = WSSecurityUtil.generateDigest(input);
            return Base64.encode(digestBytes);
        } catch (WSSecurityException e) {
            //REVISIT
        }
        return null;
    }

}
