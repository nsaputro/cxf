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
package org.apache.cxf.binding.jbi;

import java.util.Arrays;
import java.util.Collection;

import javax.xml.namespace.QName;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.AbstractBindingFactory;
import org.apache.cxf.binding.Binding;
import org.apache.cxf.binding.jbi.interceptor.JBIFaultInInterceptor;
import org.apache.cxf.binding.jbi.interceptor.JBIFaultOutInterceptor;
import org.apache.cxf.binding.jbi.interceptor.JBIOperationInInterceptor;
import org.apache.cxf.binding.jbi.interceptor.JBIWrapperInInterceptor;
import org.apache.cxf.binding.jbi.interceptor.JBIWrapperOutInterceptor;
import org.apache.cxf.common.injection.NoJSR250Annotations;
import org.apache.cxf.interceptor.AttachmentInInterceptor;
import org.apache.cxf.interceptor.AttachmentOutInterceptor;
import org.apache.cxf.interceptor.StaxInInterceptor;
import org.apache.cxf.interceptor.StaxOutInterceptor;
import org.apache.cxf.service.model.BindingInfo;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.OperationInfo;
import org.apache.cxf.service.model.ServiceInfo;

@NoJSR250Annotations(unlessNull = { "bus" })
public class JBIBindingFactory extends AbstractBindingFactory {
    public static final Collection<String> DEFAULT_NAMESPACES 
        = Arrays.asList("http://cxf.apache.org/bindings/jbi",
                        "http://java.sun.com/xml/ns/jbi/binding/service+engine");

    public JBIBindingFactory() {
    }
    public JBIBindingFactory(Bus b) {
        super(b, DEFAULT_NAMESPACES);
    }
    
    public Binding createBinding(BindingInfo binding) {
        JBIBindingInfo bindingInfo = (JBIBindingInfo) binding;
        JBIBinding jb = new JBIBinding(bindingInfo);
        
        jb.getInInterceptors().add(new StaxInInterceptor());
        jb.getInInterceptors().add(new JBIOperationInInterceptor());
        jb.getInInterceptors().add(new JBIWrapperInInterceptor());
        jb.getOutInterceptors().add(new StaxOutInterceptor());
        jb.getOutInterceptors().add(new JBIWrapperOutInterceptor());
        jb.getOutFaultInterceptors().add(new StaxOutInterceptor());
        jb.getOutFaultInterceptors().add(new JBIFaultOutInterceptor());
        
        jb.getInFaultInterceptors().add(new JBIFaultInInterceptor());
        
        if (bindingInfo.getJBIBindingConfiguration().isMtomEnabled()) {
            jb.getInInterceptors().add(new AttachmentInInterceptor());
            jb.getOutInterceptors().add(new AttachmentOutInterceptor());
        }
        return jb;
    }

    public BindingInfo createBindingInfo(ServiceInfo service, String namespace, Object config) {
        JBIBindingConfiguration configuration;
        if (config instanceof JBIBindingConfiguration) {
            configuration = (JBIBindingConfiguration) config;
        } else {
            configuration = new JBIBindingConfiguration();
        }
        JBIBindingInfo info = new JBIBindingInfo(service, JBIConstants.NS_JBI_BINDING);
        info.setJBIBindingConfiguration(configuration);
        info.setName(new QName(service.getName().getNamespaceURI(), 
                               service.getName().getLocalPart() + "JBIBinding"));

        for (OperationInfo op : service.getInterface().getOperations()) {                       
            BindingOperationInfo bop = 
                info.buildOperation(op.getName(), op.getInputName(), op.getOutputName());
            info.addOperation(bop);
        }
        
        return info;
    }

}
