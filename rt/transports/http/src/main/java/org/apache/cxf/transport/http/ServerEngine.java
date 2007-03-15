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

package org.apache.cxf.transport.http;

import java.net.URL;

import org.mortbay.jetty.Handler;
import org.mortbay.jetty.handler.AbstractHandler;

public interface ServerEngine {
    /**
     * Register a servant.
     * 
     * @param url the URL associated with the servant
     * @param handler notified on incoming HTTP requests
     */
    void addServant(URL url, AbstractHandler handler);
    
    /**
     * Remove a previously registered servant.
     * 
     * @param url the URL the servant was registered against.
     */
    void removeServant(URL url);
    
    /**
     * Get a previously  registered servant.
     * 
     * @param url the associated URL
     * @return the HttpHandler if registered
     */
    Handler getServant(URL url);
}
