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
package org.apache.cxf.interceptor.security;

import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.cxf.common.i18n.BundleUtils;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.security.SecurityToken;
import org.apache.cxf.common.security.TokenType;
import org.apache.cxf.common.security.UsernameToken;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.security.SecurityContext;

public class JAASLoginInterceptor extends AbstractPhaseInterceptor<Message> {

    private static final ResourceBundle BUNDLE = BundleUtils.getBundle(JAASLoginInterceptor.class);
    private static final Logger LOG = LogUtils.getL7dLogger(JAASLoginInterceptor.class);
    
    private String contextName;
    private String rolePrefix;
    
    public JAASLoginInterceptor() {
        super(Phase.UNMARSHAL);
    }
    
    public void setContextName(String name) {
        contextName = name;
    }
    
    public String getContextName() {
        return contextName;
    }
    
    public void setRolePrefix(String name) {
        rolePrefix = name;
    }
    
    public String getRolePrefix() {
        return rolePrefix;
    }
    
    public void handleMessage(Message message) throws Fault {

        String name = null;
        String password = null;
        
        AuthorizationPolicy policy = (AuthorizationPolicy)message.get(AuthorizationPolicy.class);
        if (policy != null) {
            name = policy.getUserName();
            password = policy.getPassword();
        } else {
            // try the UsernameToken
            SecurityToken token = message.get(SecurityToken.class);
            if (token != null && token.getTokenType() == TokenType.UsernameToken) {
                UsernameToken ut = (UsernameToken)token;
                name = ut.getName();
                password = ut.getPassword();
            }
        }
        
        if (name == null || password == null) {
            org.apache.cxf.common.i18n.Message errorMsg = 
                new org.apache.cxf.common.i18n.Message("NO_USER_PASSWORD", 
                                                       BUNDLE, 
                                                       name, password);
            LOG.warning(errorMsg.toString());
            throw new SecurityException(errorMsg.toString());
        }
        
        try {
            CallbackHandler handler = getCallbackHandler(name, password);  
            LoginContext ctx = new LoginContext(getContextName(), handler);  
            ctx.login();
            
            Subject subject = ctx.getSubject();
            
            message.put(SecurityContext.class, createSecurityContext(subject)); 
        } catch (LoginException ex) {
            String errorMessage = "Unauthorized : " + ex.getMessage();
            LOG.fine(errorMessage.toString());
            throw new AuthenticationException(errorMessage);
        }
    }

    protected CallbackHandler getCallbackHandler(String name, String password) {
        return new NamePasswordCallbackHandler(name, password);
    }
    
    protected SecurityContext createSecurityContext(Subject subject) {
        if (getRolePrefix() != null) {
            return new RolePrefixSecurityContextImpl(subject, getRolePrefix());
        } else {
            return new DefaultSecurityContext(subject);
        }
    }
    
    
}
