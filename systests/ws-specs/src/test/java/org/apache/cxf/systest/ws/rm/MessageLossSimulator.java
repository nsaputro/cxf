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

package org.apache.cxf.systest.ws.rm;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ListIterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.interceptor.InterceptorChain;
import org.apache.cxf.interceptor.MessageSenderInterceptor;
import org.apache.cxf.io.AbstractWrappedOutputStream;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.phase.PhaseInterceptor;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.rm.RMContextUtils;

/**
 * 
 */
public class MessageLossSimulator extends AbstractPhaseInterceptor<Message> {

    private static final Logger LOG = LogUtils.getLogger(MessageLossSimulator.class);
    private int appMessageCount; 
    
    public MessageLossSimulator() {
        super(Phase.PREPARE_SEND);
        addBefore(MessageSenderInterceptor.class.getName());
    }

    public void handleMessage(Message message) throws Fault {
        AddressingProperties maps =
            RMContextUtils.retrieveMAPs(message, false, true);
        RMContextUtils.ensureExposedVersion(maps);
        String action = null;
        if (maps != null && null != maps.getAction()) {
            action = maps.getAction().getValue();
        }
        if (RMContextUtils.isRMProtocolMessage(action)) {
            return;
        }
        if (MessageUtils.isPartialResponse(message)) {
            return;
        }
        appMessageCount++;
        if (0 != (appMessageCount % 2)) {
            return;
        }
        
        InterceptorChain chain = message.getInterceptorChain();
        ListIterator<Interceptor<? extends Message>> it = chain.getIterator();
        while (it.hasNext()) {
            PhaseInterceptor<?> pi = (PhaseInterceptor)it.next();
            if (MessageSenderInterceptor.class.getName().equals(pi.getId())) {
                chain.remove(pi);
                LOG.fine("Removed MessageSenderInterceptor from interceptor chain.");
                break;
            }
        }
        
        message.setContent(OutputStream.class, new WrappedOutputStream(message));     
        
        message.getInterceptorChain().add(new AbstractPhaseInterceptor<Message>(Phase.PREPARE_SEND_ENDING) {

            public void handleMessage(Message message) throws Fault {
                try {
                    message.getContent(OutputStream.class).close();
                } catch (IOException e) {
                    throw new Fault(e);
                }
            }
            
        });
    }
    
    private class WrappedOutputStream extends AbstractWrappedOutputStream {

        private Message outMessage;

        public WrappedOutputStream(Message m) {
            this.outMessage = m;
        }

        @Override
        protected void onFirstWrite() throws IOException {
            if (LOG.isLoggable(Level.FINE)) {
                Long nr = RMContextUtils.retrieveRMProperties(outMessage, true)
                    .getSequence().getMessageNumber();
                LOG.fine("Losing message " + nr);
            }
            wrappedStream = new DummyOutputStream();
        }
    }
    
    private class DummyOutputStream extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            // TODO Auto-generated method stub
            
        }
        
    }
    
}
