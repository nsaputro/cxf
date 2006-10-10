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

package org.apache.cxf.xjc.dv;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;

import org.xml.sax.ErrorHandler;

import com.sun.codemodel.JConditional;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldRef;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JOp;
import com.sun.codemodel.JType;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.util.NamespaceContextAdapter;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.XmlString;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.jaxb.DatatypeFactory;

/**
 * Modifies the JAXB code model to initialise fields mapped from schema elements 
 * with their default value.
 */
public class DefaultValuePlugin extends Plugin {
    
    private static final Logger LOG = LogUtils.getL7dLogger(DatatypeFactory.class);
    
    public DefaultValuePlugin() {
    }

    public String getOptionName() {
        return "Xdv";
    }

    public String getUsage() {
        return "-Xdv: Initialize fields mapped from elements with their default values";
    }

    public boolean run(Outline outline, Options opt, ErrorHandler errorHandler) {
        LOG.fine("Running default value plugin.");
        for (ClassOutline co : outline.getClasses()) {
            for (FieldOutline f : co.getDeclaredFields()) {

                // Use XML schema object model to determine if field is mapped
                // from an element (attributes default values are handled
                // natively) and get its default value.

                XmlString xmlDefaultValue = null;
                XSType xsType = null;
                boolean isElement = false;

                if (f.getPropertyInfo().getSchemaComponent() instanceof XSParticle) {
                    XSParticle particle = (XSParticle)f.getPropertyInfo().getSchemaComponent();
                    XSTerm term = particle.getTerm();
                    XSElementDecl element = null;

                    if (term.isElementDecl()) {
                        element = particle.getTerm().asElementDecl();
                        xmlDefaultValue = element.getDefaultValue();                        
                        xsType = element.getType();
                        isElement = true;
                    }
                } else if (f.getPropertyInfo().getSchemaComponent() instanceof XSAttributeUse) {
                    XSAttributeUse attributeUse = (XSAttributeUse)f.getPropertyInfo().getSchemaComponent();
                    XSAttributeDecl decl = attributeUse.getDecl();
                    xmlDefaultValue = decl.getDefaultValue();                        
                    xsType = decl.getType();
                }

                if (null == xmlDefaultValue || null == xmlDefaultValue.value) {
                    continue;
                }
                
                JExpression dvExpr = 
                    getDefaultValueExpression(f, co, outline, xsType, isElement, xmlDefaultValue);
                
                if (null == dvExpr) {
                    continue;
                }
                
                updateGetter(co, f, co.implClass, dvExpr);               
                
            }
        }

        return true;
    }
    
    
    JExpression getDefaultValueExpression(FieldOutline f,
                                          ClassOutline co,
                                          Outline outline,
                                          XSType xsType,
                                          boolean isElement,
                                          XmlString xmlDefaultValue
                                          ) {
        JType type = f.getRawType();
        String typeName = type.fullName();
        String defaultValue = xmlDefaultValue.value;

        JExpression dv = null;
        
        if ("java.lang.Boolean".equals(typeName) && isElement) {
            dv = JExpr.direct(Boolean.valueOf(defaultValue) ? "Boolean.TRUE" : "Boolean.FALSE");
        } else if ("java.lang.Byte".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                .arg(JExpr.cast(type.unboxify(), 
                    JExpr.lit(new Byte(Short.valueOf(defaultValue).byteValue()))));
        } else if ("java.lang.Double".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                .arg(JExpr.lit(new Double(Double.valueOf(defaultValue).doubleValue())));
        } else if ("java.lang.Float".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                     .arg(JExpr.lit(new Float(Float.valueOf(defaultValue).floatValue())));
        } else if ("java.lang.Integer".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                .arg(JExpr.lit(new Integer(Integer.valueOf(defaultValue).intValue())));
        } else if ("java.lang.Long".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                .arg(JExpr.lit(new Long(Long.valueOf(defaultValue).longValue())));
        } else if ("java.lang.Short".equals(typeName) && isElement) {
            dv = JExpr._new(type)
                .arg(JExpr.cast(type.unboxify(), 
                    JExpr.lit(new Short(Short.valueOf(defaultValue).shortValue()))));
        } else if ("java.lang.String".equals(type.fullName()) && isElement) {
            dv = JExpr.lit(defaultValue);
        } else if ("java.math.BigInteger".equals(type.fullName()) && isElement) {
            dv = JExpr._new(type).arg(JExpr.lit(defaultValue));
        } else if ("java.math.BigDecimal".equals(type.fullName()) && isElement) {
            dv = JExpr._new(type).arg(JExpr.lit(defaultValue));
        } else if ("byte[]".equals(type.fullName()) && xsType.isSimpleType() && isElement) {
            while (!"anySimpleType".equals(xsType.getBaseType().getName())) {
                xsType = xsType.getBaseType();
            }
            if ("base64Binary".equals(xsType.getName())) {
                dv = outline.getCodeModel().ref(DatatypeConverter.class)
                   .staticInvoke("parseBase64Binary").arg(defaultValue);
            } else if ("hexBinary".equals(xsType.getName())) {
                dv = JExpr._new(outline.getCodeModel().ref(HexBinaryAdapter.class))
                    .invoke("unmarshal").arg(defaultValue);
            }
        } else if ("javax.xml.namespace.QName".equals(typeName)) {
            NamespaceContext nsc = new NamespaceContextAdapter(xmlDefaultValue);
            QName qn = DatatypeConverter.parseQName(xmlDefaultValue.value, nsc);
            dv = JExpr._new(outline.getCodeModel().ref(QName.class))
                .arg(qn.getNamespaceURI())
                .arg(qn.getLocalPart())
                .arg(qn.getPrefix());
        } else if ("javax.xml.datatype.Duration".equals(typeName)) {
            dv = outline.getCodeModel().ref(org.apache.cxf.jaxb.DatatypeFactory.class)
            .staticInvoke("createDuration").arg(defaultValue);
        }
        // TODO: GregorianCalendar, ...
        return dv;
    }
    
    private void updateGetter(ClassOutline co, FieldOutline fo, JDefinedClass dc, JExpression dvExpr) {

        String fieldName = fo.getPropertyInfo().getName(false);
        JType type = fo.getRawType();
        String typeName = type.fullName();

        String getterName = ("java.lang.Boolean".equals(typeName) ? "is" : "get")
                            + fo.getPropertyInfo().getName(true);

        JMethod method = dc.getMethod(getterName, new JType[0]);
        JDocComment doc = method.javadoc();
        int mods = method.mods().getValue();
        JType mtype = method.type();

        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Updating getter: " + getterName);
        }
        // remove existing method and define new one

        dc.methods().remove(method);

        method = dc.method(mods, mtype, getterName);
        method.javadoc().append(doc);

        JFieldRef fr = JExpr.ref(fieldName);

        JExpression test = JOp.eq(JExpr._null(), fr);
        JConditional jc =  method.body()._if(test);
        jc._then()._return(dvExpr);
        jc._else()._return(fr);
    }

}
