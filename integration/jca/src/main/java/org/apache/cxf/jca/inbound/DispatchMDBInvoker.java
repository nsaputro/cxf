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
package org.apache.cxf.jca.inbound;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.resource.spi.endpoint.MessageEndpoint;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.message.Exchange;

/**
 * DispatchMDBInvoker is executed in the context of a Message Driven Bean
 * that dispatches calls to the target Session Bean where the service logic is
 * implemented.  The target must be a Stateless Session Bean.  Since 
 * DispatchMDBInvoker makes EJB local invocation to the target bean, the 
 * Message Driven Bean must be configured to include a local reference to
 * the target bean in the deployment descriptor.  The advantage of using
 * DispatchMDBInvoker is that no modification to the resource adapter's 
 * deployment descriptor (ra.xml) is required to add or remove inbound endpoints.
 */
public class DispatchMDBInvoker extends MDBInvoker {
    
    private static final Logger LOG = LogUtils.getL7dLogger(DispatchMDBInvoker.class);

    private String targetJndiName;

    /**
     * @param messageEndpoint
     */
    public DispatchMDBInvoker(MessageEndpoint messageEndpoint, String targetJndiName) {
        super(messageEndpoint);
        this.targetJndiName = targetJndiName;
    }
    
    @Override
    public Object getServiceObject(Exchange context) {
        Object target = null;
        try {
            target = ((DispatchMDBMessageListener)getMessageEndpoint())
                .lookupTargetObject(targetJndiName);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to obtain service object " + targetJndiName, e);
            return null;
        }
        
        return target;
    }

}
