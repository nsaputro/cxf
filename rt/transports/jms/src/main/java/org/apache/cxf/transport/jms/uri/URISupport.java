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
package org.apache.cxf.transport.jms.uri;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * URI utilities.
 *
 * @version $Revision$
 */
public final class URISupport {
    
    private URISupport() {
        // Helper class
    }

    /**
     * Holder to get parts of the URI.
     */
    public static class CompositeData {
        private String host;

        private String scheme;
        private String path;
        private URI components[];
        private Map parameters;
        private String fragment;

        public URI[] getComponents() {
            return components;
        }

        public String getFragment() {
            return fragment;
        }

        public Map getParameters() {
            return parameters;
        }

        public String getScheme() {
            return scheme;
        }

        public String getPath() {
            return path;
        }

        public String getHost() {
            return host;
        }

        public URI toURI() throws URISyntaxException {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) {
                sb.append(scheme);
                sb.append(':');
            }

            if (host != null && host.length() != 0) {
                sb.append(host);
            } else {
                sb.append('(');
                for (int i = 0; i < components.length; i++) {
                    if (i != 0) {
                        sb.append(',');
                    }
                    sb.append(components[i].toString());
                }
                sb.append(')');
            }

            if (path != null) {
                sb.append('/');
                sb.append(path);
            }
            if (!parameters.isEmpty()) {
                sb.append("?");
                sb.append(createQueryString(parameters));
            }
            if (fragment != null) {
                sb.append("#");
                sb.append(fragment);
            }
            return new URI(sb.toString());
        }
    }

    @SuppressWarnings("unchecked")
    public static Map parseQuery(String uri) throws URISyntaxException {
        try {
            Map rc = new HashMap();
            if (uri != null) {
                String[] parameters = uri.split("&");
                for (String parameter : parameters) {
                    int p = parameter.indexOf("=");
                    if (p >= 0) {
                        String name = URLDecoder.decode(parameter.substring(0, p), "UTF-8");
                        String value = URLDecoder.decode(parameter.substring(p + 1), "UTF-8");
                        rc.put(name, value);
                    } else {
                        rc.put(parameter, null);
                    }
                }
            }
            return rc;
        } catch (UnsupportedEncodingException e) {
            URISyntaxException se = new URISyntaxException(e.toString(), "Invalid encoding");
            se.initCause(e);
            throw se;
        }
    }

    public static Map parseParameters(URI uri) throws URISyntaxException {
        String query = uri.getQuery();
        if (query == null) {
            String schemeSpecificPart = uri.getSchemeSpecificPart();
            int idx = schemeSpecificPart.lastIndexOf('?');
            if (idx < 0) {
                return Collections.EMPTY_MAP;
            } else {
                query = schemeSpecificPart.substring(idx + 1);
            }
        } else {
            query = stripPrefix(query, "?");
        }
        return parseQuery(query);
    }

    /**
     * Removes any URI query from the given uri
     */
    public static URI removeQuery(URI uri) throws URISyntaxException {
        return createURIWithQuery(uri, null);
    }

    /**
     * Creates a URI with the given query
     */
    public static URI createURIWithQuery(URI uri, String query) throws URISyntaxException {
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(),
                       query, uri.getFragment());
    }

    public static CompositeData parseComposite(URI uri) throws URISyntaxException {

        CompositeData rc = new CompositeData();
        rc.scheme = uri.getScheme();
        String ssp = stripPrefix(uri.getSchemeSpecificPart().trim(), "//").trim();

        parseComposite(uri, rc, ssp);

        rc.fragment = uri.getFragment();
        return rc;
    }

    private static void parseComposite(URI uri, CompositeData rc, String ssp) throws URISyntaxException {
        String params;

        if (!checkParenthesis(ssp)) {
            throw new URISyntaxException(uri.toString(), "Not a matching number of '(' and ')' parenthesis");
        }

        int p;
        int intialParen = ssp.indexOf("(");
        if (intialParen == 0) {
            rc.host = ssp.substring(0, intialParen);
            p = rc.host.indexOf("/");
            if (p >= 0) {
                rc.path = rc.host.substring(p);
                rc.host = rc.host.substring(0, p);
            }
            p = ssp.lastIndexOf(")");
            params = ssp.substring(p + 1).trim();
        } else {
            params = "";
        }

        p = params.indexOf("?");
        if (p >= 0) {
            if (p > 0) {
                rc.path = stripPrefix(params.substring(0, p), "/");
            }
            rc.parameters = parseQuery(params.substring(p + 1));
        } else {
            if (params.length() > 0) {
                rc.path = stripPrefix(params, "/");
            }
            rc.parameters = Collections.EMPTY_MAP;
        }
    }

    public static String stripPrefix(String value, String prefix) {
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length());
        }
        return value;
    }

    public static URI stripScheme(URI uri) throws URISyntaxException {
        return new URI(stripPrefix(uri.getSchemeSpecificPart().trim(), "//"));
    }

    public static String createQueryString(Map options) throws URISyntaxException {
        try {
            if (options.size() > 0) {
                StringBuilder rc = new StringBuilder();
                boolean first = true;
                for (Object o : options.keySet()) {
                    if (first) {
                        first = false;
                    } else {
                        rc.append("&");
                    }

                    String key = (String) o;
                    String value = (String) options.get(key);
                    rc.append(URLEncoder.encode(key, "UTF-8"));
                    rc.append("=");
                    rc.append(URLEncoder.encode(value, "UTF-8"));
                }
                return rc.toString();
            } else {
                return "";
            }
        } catch (UnsupportedEncodingException e) {
            URISyntaxException se = new URISyntaxException(e.toString(), "Invalid encoding");
            se.initCause(e);
            throw se;
        }
    }

    /**
     * Creates a URI from the original URI and the remaining parameters
     */
    public static URI createRemainingURI(URI originalURI, Map params) throws URISyntaxException {
        String s = createQueryString(params);
        if (s.length() == 0) {
            s = null;
        }
        return createURIWithQuery(originalURI, s);
    }

    public static URI changeScheme(URI bindAddr, String scheme) throws URISyntaxException {
        return new URI(scheme, bindAddr.getUserInfo(), bindAddr.getHost(), bindAddr.getPort(), bindAddr
            .getPath(), bindAddr.getQuery(), bindAddr.getFragment());
    }

    public static boolean checkParenthesis(String str) {
        boolean result = true;
        if (str != null) {
            int open = 0;
            int closed = 0;

            int i = 0;
            while ((i = str.indexOf('(', i)) >= 0) {
                i++;
                open++;
            }
            i = 0;
            while ((i = str.indexOf(')', i)) >= 0) {
                i++;
                closed++;
            }
            result = open == closed;
        }
        return result;
    }
   
}
