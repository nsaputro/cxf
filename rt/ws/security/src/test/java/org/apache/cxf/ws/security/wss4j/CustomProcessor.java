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
package org.apache.cxf.ws.security.wss4j;

import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSDocInfo;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.message.token.SecurityContextToken;
import org.apache.ws.security.processor.Processor;
import org.apache.ws.security.validate.Validator;

/**
 * a custom processor that inserts itself into the results vector
 */
public class CustomProcessor implements Processor {
    
    public final java.util.List<WSSecurityEngineResult> 
    handleToken(
        final org.w3c.dom.Element elem, 
        final RequestData data, 
        final WSDocInfo wsDocInfo 
    ) throws WSSecurityException {
        final WSSecurityEngineResult result = 
            new WSSecurityEngineResult(
                WSConstants.SIGN, 
                (SecurityContextToken) null
            );
        result.put("foo", this);
        return java.util.Collections.singletonList(result);
    }
    
    public void setValidator(Validator validator) {
        //
    }

}
