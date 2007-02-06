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

package org.apache.cxf.tools.java2wsdl.processor.internal;

import junit.framework.TestCase;

import org.apache.cxf.tools.fortest.classnoanno.docbare.Stock;
import org.apache.cxf.tools.fortest.simple.Hello;
import org.apache.cxf.tools.java2wsdl.processor.FrontendFactory;
import org.apache.cxf.tools.java2wsdl.processor.internal.jaxws.JaxwsServiceBuilder;
import org.apache.cxf.tools.java2wsdl.processor.internal.simple.SimpleServiceBuilder;

public class ServiceBuilderFactoryTest extends TestCase {
    ServiceBuilderFactory factory = ServiceBuilderFactory.getInstance();
    
    public void testGetBuilderClassName() {
        assertNotNull(factory);
        assertEquals(JaxwsServiceBuilder.class.getName(),
                     factory.getBuilderClassName(FrontendFactory.Style.Jaxws));

        assertEquals(SimpleServiceBuilder.class.getName(),
                     factory.getBuilderClassName(FrontendFactory.Style.Simple));
    }

    public void testGetJaxwsBuilder() {
        factory.setServiceClass(Stock.class);
        ServiceBuilder builder = factory.newBuilder();
        assertNotNull(builder);
        assertTrue(builder instanceof JaxwsServiceBuilder);
    }

    public void testGetSimpleBuilder() {
        factory.setServiceClass(Hello.class);
        ServiceBuilder builder = factory.newBuilder();
        assertNotNull(builder);
        assertTrue(builder instanceof SimpleServiceBuilder);
    }
}
