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

package org.apache.cxf.ws.security.trust;


import java.util.List;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.message.Message;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.saml.ext.AssertionWrapper;
import org.apache.ws.security.validate.Credential;
import org.apache.ws.security.validate.Validator;

/**
 * 
 */
public class STSTokenValidator implements Validator {
    private STSSamlAssertionValidator samlValidator = new STSSamlAssertionValidator();
    private boolean alwaysValidateToSts;
    
    public STSTokenValidator() {
    }
    
    /**
     * Construct a new instance.
     * @param alwaysValidateToSts whether to always validate the token to the STS
     */
    public STSTokenValidator(boolean alwaysValidateToSts) {
        this.alwaysValidateToSts = alwaysValidateToSts;
    }
    
    public Credential validate(Credential credential, RequestData data) throws WSSecurityException {
        
        if (isValidatedLocally(credential, data)) {
            return credential;
        }
        
        return validateWithSTS(credential, (SoapMessage)data.getMsgContext());
    }
    
    public Credential validateWithSTS(Credential credential, Message message) throws WSSecurityException {
        
        SecurityToken token = new SecurityToken();
        
        try {
            if (credential.getAssertion() != null) {
                token.setToken(credential.getAssertion().getElement());
            } else if (credential.getUsernametoken() != null) {
                token.setToken(credential.getUsernametoken().getElement());
            } else if (credential.getBinarySecurityToken() != null) {
                token.setToken(credential.getBinarySecurityToken().getElement());
            }
            
            STSClient c = STSUtils.getClient(message, "sts");
            synchronized (c) {
                System.setProperty("noprint", "true");
                List<SecurityToken> tokens = c.validateSecurityToken(token);
                SecurityToken returnedToken = tokens.get(0);
                if (returnedToken != token) {
                    AssertionWrapper assertion = new AssertionWrapper(returnedToken.getToken());
                    credential.setTransformedToken(assertion);
                }
                return credential;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity", null, e);
        }
    }
    
    protected boolean isValidatedLocally(Credential credential, RequestData data) 
        throws WSSecurityException {
        
        if (!alwaysValidateToSts && credential.getAssertion() != null) {
            try {
                samlValidator.validate(credential, data);
                return samlValidator.isTrustVerificationSucceeded();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new WSSecurityException(WSSecurityException.FAILURE, "invalidSAMLsecurity", null, e);
            }
        }
        return false;
    }

}
