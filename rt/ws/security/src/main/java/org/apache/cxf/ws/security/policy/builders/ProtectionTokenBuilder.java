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
package org.apache.cxf.ws.security.policy.builders;

import java.util.Iterator;
import java.util.List;

import javax.xml.namespace.QName;

import org.w3c.dom.Element;

import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.ws.policy.PolicyBuilder;
import org.apache.cxf.ws.security.policy.SP11Constants;
import org.apache.cxf.ws.security.policy.SP12Constants;
import org.apache.cxf.ws.security.policy.SPConstants;
import org.apache.cxf.ws.security.policy.model.ProtectionToken;
import org.apache.cxf.ws.security.policy.model.Token;
import org.apache.neethi.Assertion;
import org.apache.neethi.AssertionBuilderFactory;
import org.apache.neethi.Policy;
import org.apache.neethi.builders.AssertionBuilder;


public class ProtectionTokenBuilder implements AssertionBuilder<Element> {
    private static final QName KNOWN_ELEMENTS[] 
        = {SP11Constants.PROTECTION_TOKEN, SP12Constants.PROTECTION_TOKEN};
    
    PolicyBuilder builder;
    public ProtectionTokenBuilder(PolicyBuilder b) {
        builder = b;
    }
    public QName[] getKnownElements() {
        return KNOWN_ELEMENTS;
    }
    
    
    public Assertion build(Element element, AssertionBuilderFactory factory)
        throws IllegalArgumentException {
        SPConstants consts = SP11Constants.SP_NS.equals(element.getNamespaceURI())
            ? SP11Constants.INSTANCE : SP12Constants.INSTANCE;
        
        
        ProtectionToken protectionToken = new ProtectionToken(consts, builder);

        Policy policy = builder.getPolicy(DOMUtils.getFirstElement(element));
        policy = (Policy)policy.normalize(builder.getPolicyRegistry(), false);

        for (Iterator iterator = policy.getAlternatives(); iterator.hasNext();) {
            processAlternative((List)iterator.next(), protectionToken);
            break; // since there should be only one alternative ..
        }

        return protectionToken;
    }


    private void processAlternative(List assertions, ProtectionToken parent) {
        Object token = assertions.get(0);

        if (token instanceof Token) {
            parent.setToken((Token)token);
        }
    }
}
