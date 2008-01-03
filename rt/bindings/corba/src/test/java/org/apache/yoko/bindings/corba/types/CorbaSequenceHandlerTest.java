/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.yoko.bindings.corba.types;

import javax.xml.namespace.QName;

import junit.framework.TestCase;

import org.apache.schemas.yoko.bindings.corba.Sequence;
import org.apache.yoko.wsdl.CorbaConstants;
import org.omg.CORBA.ORB;
import org.omg.CORBA.TCKind;
import org.omg.CORBA.TypeCode;

public class CorbaSequenceHandlerTest extends TestCase {

    private ORB orb;
    private CorbaSequenceHandler obj;
    private QName objName;
    private QName objIdlType;
    private TypeCode objTypeCode;
    
    public CorbaSequenceHandlerTest(String arg0) {
        super(arg0);
    }

    public static void main(String[] args) {
        junit.textui.TestRunner.run(CorbaArrayHandlerTest.class);
    }
    
    protected void setUp() throws Exception {
        super.setUp();
        
        java.util.Properties props = System.getProperties();
        props.put("org.omg.CORBA.ORBClass", "org.apache.yoko.orb.CORBA.ORB");
        props.put("org.omg.CORBA.ORBSingletonClass", "org.apache.yoko.orb.CORBA.ORBSingleton");
        props.put("yoko.orb.id", "Yoko-Server-Binding");
        orb = ORB.init(new String[0], props);
        obj = null;
        objName = null;
        objIdlType = null;
        objTypeCode = null;
    }
    
    protected void tearDown() throws Exception {
        if (orb != null) {
            try {
                orb.destroy();
            } catch (Exception ex) {
                // Do nothing.  Throw an Exception?
            }
        }
    }

    public void testCorbaSequenceHandler() {
        objName = new QName("object");
        objIdlType = new QName(CorbaConstants.NU_WSDL_CORBA, "sequenceType", CorbaConstants.NP_WSDL_CORBA);
        objTypeCode = orb.create_sequence_tc(5, orb.get_primitive_tc(TCKind.tk_long));
        Sequence sequenceType = new Sequence();
        sequenceType.setName("sequenceType");
        sequenceType.setElemtype(CorbaConstants.NT_CORBA_LONG);
        sequenceType.setBound(5);
        sequenceType.setRepositoryID("IDL:SequenceType:1.0");
        obj = new CorbaSequenceHandler(objName, objIdlType, objTypeCode, sequenceType);
        assertNotNull(obj);

        int sequenceData[] = { 2, 4, 6, 8, 10 };
        for (int i = 0 ; i < sequenceData.length; ++i) {
            QName elName = new QName("item");
            QName elIdlType = CorbaConstants.NT_CORBA_LONG;
            TypeCode elTC = orb.get_primitive_tc(TCKind.tk_long);
            CorbaPrimitiveHandler el = new CorbaPrimitiveHandler(elName, elIdlType, elTC, null);
            el.setValue(Integer.valueOf(sequenceData[i]));
            obj.addElement(el);
        }

        QName nameResult = obj.getName();
        assertNotNull(nameResult);
        assertTrue(objName.equals(nameResult));

        QName idlTypeResult = obj.getIdlType();
        assertNotNull(idlTypeResult);
        assertTrue(idlTypeResult.equals(objIdlType));

        TypeCode tcResult = obj.getTypeCode();
        assertNotNull(tcResult);
        assertTrue(tcResult.kind().value() == objTypeCode.kind().value());

        Object objDefResult = obj.getType();
        assertNotNull(objDefResult);
        assertTrue(objDefResult instanceof Sequence);

        int countResult = obj.getNumberOfElements();
        for (int i = 0; i < countResult; ++i) {
            CorbaObjectHandler elResult = obj.getElement(i);
            assertNotNull(elResult);
        }
    }
}
