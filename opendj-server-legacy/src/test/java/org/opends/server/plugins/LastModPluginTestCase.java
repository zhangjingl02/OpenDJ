/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2008 Sun Microsystems, Inc.
 *      Portions Copyright 2014-2016 ForgeRock AS
 */
package org.opends.server.plugins;

import static org.assertj.core.api.Assertions.*;
import static org.opends.server.protocols.internal.InternalClientConnection.*;
import static org.testng.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.ResultCode;
import org.opends.server.TestCaseUtils;
import org.opends.server.admin.server.AdminTestCaseUtils;
import org.opends.server.admin.std.meta.LastModPluginCfgDefn;
import org.opends.server.admin.std.server.LastModPluginCfg;
import org.opends.server.api.plugin.PluginType;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyDNOperation;
import org.opends.server.core.ModifyOperation;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.opends.server.types.Attributes;
import org.opends.server.types.DN;
import org.opends.server.types.DirectoryConfig;
import org.opends.server.types.Entry;
import org.opends.server.types.Modification;
import org.opends.server.types.RDN;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * This class defines a set of tests for the
 * org.opends.server.plugins.LastModPlugin class.
 */
public class LastModPluginTestCase
       extends PluginTestCase
{
  /**
   * Ensures that the Directory Server is running.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @BeforeClass
  public void startServer()
         throws Exception
  {
    TestCaseUtils.startServer();
  }



  /**
   * Retrieves a set of valid configuration entries that may be used to
   * initialize the plugin.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @DataProvider(name = "validConfigs")
  public Object[][] getValidConfigs()
         throws Exception
  {
    List<Entry> entries = TestCaseUtils.makeEntries(
         "dn: cn=LastMod,cn=Plugins,cn=config",
         "objectClass: top",
         "objectClass: ds-cfg-plugin",
         "objectClass: ds-cfg-last-mod-plugin",
         "cn: LastMod",
         "ds-cfg-java-class: org.opends.server.plugins.LastModPlugin",
         "ds-cfg-enabled: true",
         "ds-cfg-plugin-type: preOperationAdd",
         "ds-cfg-plugin-type: preOperationModify",
         "ds-cfg-plugin-type: preOperationModifyDN",
         "",
         "dn: cn=LastMod,cn=Plugins,cn=config",
         "objectClass: top",
         "objectClass: ds-cfg-plugin",
         "objectClass: ds-cfg-last-mod-plugin",
         "cn: LastMod",
         "ds-cfg-java-class: org.opends.server.plugins.LastModPlugin",
         "ds-cfg-enabled: true",
         "ds-cfg-plugin-type: preOperationAdd",
         "",
         "dn: cn=LastMod,cn=Plugins,cn=config",
         "objectClass: top",
         "objectClass: ds-cfg-plugin",
         "objectClass: ds-cfg-last-mod-plugin",
         "cn: LastMod",
         "ds-cfg-java-class: org.opends.server.plugins.LastModPlugin",
         "ds-cfg-enabled: true",
         "ds-cfg-plugin-type: preOperationModify",
         "",
         "dn: cn=LastMod,cn=Plugins,cn=config",
         "objectClass: top",
         "objectClass: ds-cfg-plugin",
         "objectClass: ds-cfg-last-mod-plugin",
         "cn: LastMod",
         "ds-cfg-java-class: org.opends.server.plugins.LastModPlugin",
         "ds-cfg-enabled: true",
         "ds-cfg-plugin-type: preOperationModifyDN");

    Object[][] array = new Object[entries.size()][1];
    for (int i=0; i < array.length; i++)
    {
      array[i] = new Object[] { entries.get(i) };
    }

    return array;
  }



  /**
   * Tests the process of initializing the server with valid configurations.
   *
   * @param  e  The configuration entry to use for the initialization.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "validConfigs")
  public void testInitializeWithValidConfigs(Entry e)
         throws Exception
  {
    HashSet<PluginType> pluginTypes = TestCaseUtils.getPluginTypes(e);

    LastModPluginCfg configuration =
         AdminTestCaseUtils.getConfiguration(
              LastModPluginCfgDefn.getInstance(), e);

    LastModPlugin plugin = new LastModPlugin();
    plugin.initializePlugin(pluginTypes, configuration);
    plugin.finalizePlugin();
  }



  /**
   * Tests the process of initializing the server with valid configurations but
   * without the lastmod schema defined in the server.
   *
   * @param  e  The configuration entry to use for the initialization.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "validConfigs")
  public void testInitializeWithValidConfigsWithoutSchema(Entry e)
         throws Exception
  {
    AttributeType ctType = DirectoryServer.getAttributeType("createtimestamp");
    AttributeType cnType = DirectoryServer.getAttributeType("creatorsname");
    AttributeType mtType = DirectoryServer.getAttributeType("modifytimestamp");
    AttributeType mnType = DirectoryServer.getAttributeType("modifiersname");

    DirectoryServer.deregisterAttributeType(ctType);
    DirectoryServer.deregisterAttributeType(cnType);
    DirectoryServer.deregisterAttributeType(mtType);
    DirectoryServer.deregisterAttributeType(mnType);


    HashSet<PluginType> pluginTypes = TestCaseUtils.getPluginTypes(e);

    LastModPluginCfg configuration =
         AdminTestCaseUtils.getConfiguration(
              LastModPluginCfgDefn.getInstance(), e);

    LastModPlugin plugin = new LastModPlugin();
    plugin.initializePlugin(pluginTypes, configuration);
    plugin.finalizePlugin();


    DirectoryServer.registerAttributeType(ctType, false);
    DirectoryServer.registerAttributeType(cnType, false);
    DirectoryServer.registerAttributeType(mtType, false);
    DirectoryServer.registerAttributeType(mnType, false);
  }



  /**
   * Retrieves a set of invalid configuration entries.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @DataProvider(name = "invalidConfigs")
  public Object[][] getInvalidConfigs()
         throws Exception
  {
    ArrayList<Entry> entries = new ArrayList<>();

    for (String s : PluginType.getPluginTypeNames())
    {
      if (s.equalsIgnoreCase("preOperationAdd") ||
          s.equalsIgnoreCase("preOperationModify") ||
          s.equalsIgnoreCase("preOperationModifyDN"))
      {
        continue;
      }

      Entry e = TestCaseUtils.makeEntry(
           "dn: cn=LastMod,cn=Plugins,cn=config",
           "objectClass: top",
           "objectClass: ds-cfg-plugin",
           "objectClass: ds-cfg-last-mod-plugin",
           "cn: LastMod",
           "ds-cfg-java-class: org.opends.server.plugins.LastModPlugin",
           "ds-cfg-enabled: true",
           "ds-cfg-plugin-type: " + s);
      entries.add(e);
    }

    Object[][] array = new Object[entries.size()][1];
    for (int i=0; i < array.length; i++)
    {
      array[i] = new Object[] { entries.get(i) };
    }

    return array;
  }



  /**
   * Tests the process of initializing the server with valid configurations.
   *
   * @param  e  The configuration entry to use for the initialization.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "invalidConfigs",
        expectedExceptions = { ConfigException.class })
  public void testInitializeWithInvalidConfigs(Entry e)
         throws Exception
  {
    HashSet<PluginType> pluginTypes = TestCaseUtils.getPluginTypes(e);

    LastModPluginCfg configuration =
         AdminTestCaseUtils.getConfiguration(
              LastModPluginCfgDefn.getInstance(), e);

    LastModPlugin plugin = new LastModPlugin();
    plugin.initializePlugin(pluginTypes, configuration);
    plugin.finalizePlugin();
  }



  /**
   * Tests the <CODE>doPreOperationAdd</CODE> method.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDoPreOperationAdd()
         throws Exception
  {
    TestCaseUtils.initializeTestBackend(true);
    Entry e = TestCaseUtils.addEntry("dn: cn=test,o=test",
                                      "objectClass: top",
                                      "objectClass: device",
                                      "cn: test");
    assertThat(e.getAttribute("creatorsname")).isNotEmpty();
    assertThat(e.getAttribute("createtimestamp")).isNotEmpty();
  }



  /**
   * Tests the <CODE>doPreOperationModify</CODE> method.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDoPreOperationModify()
         throws Exception
  {
    TestCaseUtils.initializeTestBackend(true);

    ArrayList<Modification> mods = new ArrayList<>();
    mods.add(new Modification(ModificationType.REPLACE,
                              Attributes.create("description", "foo")));

    InternalClientConnection conn =
         InternalClientConnection.getRootConnection();
    ModifyOperation modifyOperation =
         conn.processModify(DN.valueOf("o=test"), mods);
    assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);

    Entry e = DirectoryConfig.getEntry(DN.valueOf("o=test"));
    assertNotNull(e);
    assertThat(e.getAttribute("modifiersname")).isNotEmpty();
    assertThat(e.getAttribute("modifytimestamp")).isNotEmpty();
  }



  /**
   * Tests the <CODE>doPreOperationModifyDN</CODE> method.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDoPreOperationModifyDN()
         throws Exception
  {
    TestCaseUtils.initializeTestBackend(true);
    Entry e = TestCaseUtils.addEntry("dn: cn=test,o=test",
                                      "objectClass: top",
                                      "objectClass: device",
                                      "cn: test");

    ModifyDNOperation modifyDNOperation =
        getRootConnection().processModifyDN(e.getName(), RDN.decode("cn=test2"), false);
    assertEquals(modifyDNOperation.getResultCode(), ResultCode.SUCCESS);

    e = DirectoryConfig.getEntry(DN.valueOf("cn=test2,o=test"));
    assertNotNull(e);
    assertThat(e.getAttribute("modifiersname")).isNotEmpty();
    assertThat(e.getAttribute("modifytimestamp")).isNotEmpty();
  }
}
